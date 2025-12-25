package com.bothbubbles.services.notifications

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages refreshing conversation shortcuts when contact information changes.
 *
 * When contact photos or names change:
 * 1. AvatarResolver cache is invalidated
 * 2. All conversation shortcuts are rebuilt with updated avatars
 * 3. ShortcutManagerCompat is used to push updates
 *
 * This ensures that:
 * - Bubble icons show updated contact photos
 * - Share sheet shows correct contact avatars
 * - Launcher shortcuts have current photos
 */
@Singleton
class ShortcutRefreshManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatDao: ChatDao,
    private val unifiedChatDao: UnifiedChatDao,
    private val bubbleMetadataHelper: BubbleMetadataHelper,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "ShortcutRefresh"
        private const val MAX_SHORTCUTS_TO_REFRESH = 50 // Limit to avoid ANR
    }

    /**
     * Refresh all conversation shortcuts with updated avatar data.
     *
     * Called when:
     * - Contact photos are updated
     * - Contact names change
     * - Contacts are added/removed
     */
    suspend fun refreshAllShortcuts() = withContext(ioDispatcher) {
        try {
            Timber.tag(TAG).d("Starting shortcut refresh for all conversations")

            // Get active unified chats (most recent conversations)
            val unifiedChats = unifiedChatDao.getActiveChats(limit = MAX_SHORTCUTS_TO_REFRESH, offset = 0)

            var refreshed = 0
            for (unifiedChat in unifiedChats) {
                try {
                    refreshShortcutForUnifiedChat(unifiedChat.id, unifiedChat.sourceId)
                    refreshed++
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to refresh shortcut for unified chat ${unifiedChat.id}")
                }
            }

            Timber.tag(TAG).i("Refreshed $refreshed conversation shortcuts")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error refreshing shortcuts")
        }
    }

    /**
     * Refresh shortcut for a specific unified chat.
     */
    private suspend fun refreshShortcutForUnifiedChat(unifiedChatId: String, sourceId: String) {
        // Get the chat entity for participants
        val chat = chatDao.getChatByGuid(sourceId) ?: return
        val participants = chatDao.getParticipantsForChat(sourceId)

        val participantNames = participants.map { it.displayName }
        val participantAvatarPaths = participants.map { it.cachedAvatarPath }
        val participantHasContactInfo = participants.map { it.cachedDisplayName != null }

        val unifiedChat = unifiedChatDao.getById(unifiedChatId) ?: return
        val displayName = unifiedChat.displayName
            ?: participantNames.joinToString(", ").takeIf { it.isNotBlank() }
            ?: "Unknown"

        // Rebuild the shortcut with fresh avatar data
        bubbleMetadataHelper.createConversationShortcut(
            chatGuid = sourceId,
            chatTitle = displayName,
            isGroup = chat.isGroup,
            participantNames = participantNames,
            chatAvatarPath = unifiedChat.effectiveAvatarPath,
            senderAvatarPath = if (!chat.isGroup) participants.firstOrNull()?.cachedAvatarPath else null,
            senderHasContactInfo = if (!chat.isGroup) participants.firstOrNull()?.cachedDisplayName != null else false,
            participantAvatarPaths = participantAvatarPaths,
            participantHasContactInfo = participantHasContactInfo
        )
    }

    /**
     * Refresh shortcut for a specific chat by GUID.
     *
     * @param chatGuid The chat's unique identifier
     */
    suspend fun refreshShortcutForChat(chatGuid: String) = withContext(ioDispatcher) {
        try {
            val chat = chatDao.getChatByGuid(chatGuid) ?: return@withContext
            val unifiedChatId = chat.unifiedChatId ?: return@withContext

            refreshShortcutForUnifiedChat(unifiedChatId, chatGuid)
            Timber.tag(TAG).d("Refreshed shortcut for chat $chatGuid")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to refresh shortcut for chat $chatGuid")
        }
    }

    /**
     * Remove all dynamic shortcuts.
     * Called during cleanup or logout.
     */
    fun removeAllShortcuts() {
        try {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            Timber.tag(TAG).i("Removed all dynamic shortcuts")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error removing shortcuts")
        }
    }
}
