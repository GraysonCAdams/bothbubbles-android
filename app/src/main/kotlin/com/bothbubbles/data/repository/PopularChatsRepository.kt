package com.bothbubbles.data.repository

import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.PopularChatsDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for accessing popular (most engaged) chats.
 *
 * Popularity is calculated based on message count within a configurable time window.
 * This repository combines both:
 * - Unified groups (1:1 chats merged across iMessage/SMS)
 * - Group chats (multi-participant conversations)
 *
 * Used by:
 * - [com.bothbubbles.services.shortcut.AppShortcutManager] for launcher shortcuts
 * - [com.bothbubbles.ui.chatcreator.delegates.ContactLoadDelegate] for recipient suggestions
 *
 * Filters applied:
 * - Excludes archived chats
 * - Excludes soft-deleted chats
 * - Only counts non-reaction messages
 */
@Singleton
class PopularChatsRepository @Inject constructor(
    private val popularChatsDao: PopularChatsDao,
    private val chatDao: ChatDao,
    private val unifiedChatGroupDao: UnifiedChatGroupDao,
    private val chatRepository: ChatRepository
) {
    companion object {
        /** Default time window for popularity calculation (30 days) */
        val DEFAULT_TIME_WINDOW_MS = TimeUnit.DAYS.toMillis(30)

        /** Maximum number of popular chats to return */
        const val DEFAULT_LIMIT = 10

        /** Number of chats for launcher shortcuts */
        const val LAUNCHER_SHORTCUT_LIMIT = 5
    }

    /**
     * Represents a popular chat with its metadata and engagement score.
     */
    data class PopularChat(
        /** The primary chat GUID (for unified groups, this is the primary member) */
        val chatGuid: String,
        /** Display name for the chat */
        val displayName: String,
        /** Whether this is a group chat */
        val isGroup: Boolean,
        /** Message count in the time window (engagement score) */
        val messageCount: Int,
        /** Timestamp of most recent message */
        val latestMessageDate: Long,
        /** Custom avatar path if set */
        val avatarPath: String?,
        /** Identifier (phone number or email) for non-group chats */
        val identifier: String?
    )

    /**
     * Get the most popular chats based on message engagement.
     *
     * Combines unified groups (1:1 chats) and group chats, sorted by message count.
     *
     * @param limit Maximum number of chats to return
     * @param timeWindowMs Time window in milliseconds for counting messages
     * @return List of popular chats sorted by engagement (message count)
     */
    suspend fun getPopularChats(
        limit: Int = DEFAULT_LIMIT,
        timeWindowMs: Long = DEFAULT_TIME_WINDOW_MS
    ): List<PopularChat> {
        val since = System.currentTimeMillis() - timeWindowMs

        // Get popular unified groups (1:1 chats)
        val popularUnified = popularChatsDao.getPopularUnifiedGroups(since, limit)

        // Get popular group chats
        val popularGroups = popularChatsDao.getPopularGroupChats(since, limit)

        // Combine and sort by message count
        val combined = (popularUnified + popularGroups)
            .sortedByDescending { it.messageCount }
            .take(limit)

        // Fetch full chat/group data for display names
        return combined.mapNotNull { popularity ->
            resolvePopularChat(popularity)
        }
    }

    /**
     * Observe popular chats reactively.
     *
     * Emits a new list whenever:
     * - Messages are sent/received
     * - Chats are archived/unarchived
     * - Chats are deleted
     *
     * @param limit Maximum number of chats to return
     * @param timeWindowMs Time window in milliseconds for counting messages
     */
    fun observePopularChats(
        limit: Int = DEFAULT_LIMIT,
        timeWindowMs: Long = DEFAULT_TIME_WINDOW_MS
    ): Flow<List<PopularChat>> {
        val since = System.currentTimeMillis() - timeWindowMs

        return combine(
            popularChatsDao.observePopularUnifiedGroups(since, limit),
            popularChatsDao.observePopularGroupChats(since, limit)
        ) { unifiedPopularity, groupPopularity ->
            val combined = (unifiedPopularity + groupPopularity)
                .sortedByDescending { it.messageCount }
                .take(limit)

            combined.mapNotNull { popularity ->
                resolvePopularChat(popularity)
            }
        }
    }

    /**
     * Get popular chats for launcher shortcuts (top 5).
     */
    suspend fun getPopularChatsForLauncher(): List<PopularChat> {
        return getPopularChats(limit = LAUNCHER_SHORTCUT_LIMIT)
    }

    /**
     * Observe popular chats for launcher shortcuts (top 5).
     */
    fun observePopularChatsForLauncher(): Flow<List<PopularChat>> {
        return observePopularChats(limit = LAUNCHER_SHORTCUT_LIMIT)
    }

    /**
     * Get popular contacts (1:1 chats only) for recipient suggestions.
     * Excludes group chats since we're suggesting individual recipients.
     *
     * @param limit Maximum number of contacts to return
     * @param timeWindowMs Time window for calculating popularity
     */
    suspend fun getPopularContacts(
        limit: Int = DEFAULT_LIMIT,
        timeWindowMs: Long = DEFAULT_TIME_WINDOW_MS
    ): List<PopularChat> {
        val since = System.currentTimeMillis() - timeWindowMs

        val popularUnified = popularChatsDao.getPopularUnifiedGroups(since, limit)

        return popularUnified.mapNotNull { popularity ->
            resolvePopularChat(popularity)
        }
    }

    /**
     * Observe popular contacts for recipient suggestions.
     */
    fun observePopularContacts(
        limit: Int = DEFAULT_LIMIT,
        timeWindowMs: Long = DEFAULT_TIME_WINDOW_MS
    ): Flow<List<PopularChat>> {
        val since = System.currentTimeMillis() - timeWindowMs

        return popularChatsDao.observePopularUnifiedGroups(since, limit)
            .map { popularityList ->
                popularityList.mapNotNull { popularity ->
                    resolvePopularChat(popularity)
                }
            }
    }

    /**
     * Resolve a popularity entry to a full PopularChat with display info.
     */
    private suspend fun resolvePopularChat(
        popularity: PopularChatsDao.ChatPopularity
    ): PopularChat? {
        // Try as unified group first
        val unifiedGroup = unifiedChatGroupDao.getGroupByPrimaryChatGuid(popularity.chatGuid)
        if (unifiedGroup != null) {
            return PopularChat(
                chatGuid = popularity.chatGuid,
                displayName = unifiedGroup.displayName
                    ?: PhoneNumberFormatter.format(unifiedGroup.identifier),
                isGroup = false,
                messageCount = popularity.messageCount,
                latestMessageDate = popularity.latestMessageDate,
                avatarPath = null,
                identifier = unifiedGroup.identifier
            )
        }

        // Try as regular chat (group or non-group)
        val chat = chatDao.getChatByGuid(popularity.chatGuid) ?: return null

        // Get participants for display name resolution
        val participants = chatRepository.getParticipantsForChat(chat.guid)
        val displayName = chatRepository.resolveChatTitle(chat, participants)

        return PopularChat(
            chatGuid = popularity.chatGuid,
            displayName = displayName,
            isGroup = chat.isGroup,
            messageCount = popularity.messageCount,
            latestMessageDate = popularity.latestMessageDate,
            avatarPath = chat.effectiveGroupPhotoPath,
            identifier = chat.chatIdentifier
        )
    }
}
