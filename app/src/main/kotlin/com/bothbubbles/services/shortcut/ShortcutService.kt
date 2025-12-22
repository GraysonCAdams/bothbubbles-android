package com.bothbubbles.services.shortcut

import android.content.Context
import android.content.Intent
import timber.log.Timber
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.bothbubbles.MainActivity
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.ChatParticipantDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.util.GroupAvatarRenderer
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.core.model.entity.UnifiedChatEntity
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.notifications.NotificationChannelManager
import com.bothbubbles.util.AvatarGenerator
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for managing Android Sharing Shortcuts.
 *
 * Publishes recent conversations as share targets that appear directly
 * in the Android share sheet, enabling one-tap sharing to frequent contacts.
 *
 * Follows the pattern of ContactsContentObserver with:
 * - Reactive Flow-based observation of conversation changes
 * - Debounced updates to prevent excessive shortcut publishing
 * - Automatic cleanup when conversations are deleted
 */
@Singleton
class ShortcutService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val unifiedChatDao: UnifiedChatDao,
    private val chatDao: ChatDao,
    private val chatParticipantDao: ChatParticipantDao,
    private val handleDao: HandleDao,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "ShortcutService"
        private const val DEBOUNCE_MS = 3000L // 3 seconds debounce
        private const val MAX_SHORTCUTS = 5
        private const val SHORTCUT_ID_PREFIX = "share_"
        private const val SHARE_TARGET_CATEGORY = "com.bothbubbles.category.SHARE_TARGET"
    }

    private var observationJob: Job? = null
    private var debounceJob: Job? = null

    private val _isObserving = MutableStateFlow(false)
    val isObserving: StateFlow<Boolean> = _isObserving.asStateFlow()

    /**
     * Start observing conversation changes and publishing shortcuts.
     */
    fun startObserving() {
        if (_isObserving.value) return

        Timber.d("Starting share target observation")

        observationJob = applicationScope.launch(ioDispatcher) {
            // Combine unified chats (1:1 chats) and group chats into a single stream
            combine(
                unifiedChatDao.observeActiveChats(),
                chatDao.observeGroupChats()
            ) { unifiedChats, groupChats ->
                Pair(unifiedChats, groupChats)
            }
            .distinctUntilChanged()
            .collect { (unifiedChats, groupChats) ->
                debounceAndUpdate(unifiedChats, groupChats)
            }
        }

        _isObserving.value = true
    }

    /**
     * Stop observing conversation changes.
     */
    fun stopObserving() {
        Timber.d("Stopping share target observation")

        observationJob?.cancel()
        observationJob = null
        debounceJob?.cancel()
        debounceJob = null

        _isObserving.value = false
    }

    // Separate job for the actual update work (not cancelled by debounce)
    private var updateJob: Job? = null

    /**
     * Debounce updates to prevent rapid shortcut publishing during bulk operations.
     * Only cancels the delay, not a running update.
     */
    private fun debounceAndUpdate(
        unifiedChats: List<UnifiedChatEntity>,
        groupChats: List<ChatEntity>
    ) {
        // Cancel pending debounce, but let any running update complete
        debounceJob?.cancel()
        debounceJob = applicationScope.launch(ioDispatcher) {
            delay(DEBOUNCE_MS)
            // Wait for any running update to complete before starting a new one
            updateJob?.join()
            updateJob = applicationScope.launch(ioDispatcher) {
                updateShortcuts(unifiedChats, groupChats)
            }
        }
    }

    /**
     * Update sharing shortcuts based on current conversations.
     */
    private suspend fun updateShortcuts(
        unifiedChats: List<UnifiedChatEntity>,
        groupChats: List<ChatEntity>
    ) {
        try {
            // Check if rate limiting is active
            if (ShortcutManagerCompat.isRateLimitingActive(context)) {
                Timber.w("Shortcut rate limiting active, skipping update")
                return
            }

            // Build combined list of conversations sorted by recency
            val conversations = buildConversationList(unifiedChats, groupChats)

            if (conversations.isEmpty()) {
                Timber.d("No conversations to publish as shortcuts")
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                return
            }

            // Take top N conversations
            val topConversations = conversations.take(MAX_SHORTCUTS)

            Timber.tag(TAG).d("Publishing ${topConversations.size} share target shortcuts")

            // Build and publish shortcuts
            topConversations.forEachIndexed { index, conversation ->
                Timber.tag(TAG).d("Building shortcut #$index: ${conversation.displayName} (avatarPath=${conversation.avatarPath})")
                val shortcut = buildShortcut(conversation, index)
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
                Timber.tag(TAG).d("Pushed shortcut for ${conversation.displayName}")
            }

        } catch (e: Exception) {
            Timber.e(e, "Error updating sharing shortcuts")
        }
    }

    /**
     * Build a combined list of conversations from unified chats and group chats.
     * Looks up avatar paths from handles (for 1:1 chats) and chat entities (for groups).
     */
    private suspend fun buildConversationList(
        unifiedChats: List<UnifiedChatEntity>,
        groupChats: List<ChatEntity>
    ): List<ConversationInfo> {
        val conversations = mutableListOf<ConversationInfo>()

        // Add unified chats (1:1 chats)
        for (chat in unifiedChats) {
            val displayName = chat.displayName
                ?: PhoneNumberFormatter.format(chat.normalizedAddress)

            // Use effective avatar path from unified chat, or look up from handle
            val handle = handleDao.getHandleByAddressAny(chat.normalizedAddress)
            val avatarPath = chat.effectiveAvatarPath ?: handle?.cachedAvatarPath

            Timber.tag(TAG).d(
                "Unified chat: identifier=${chat.normalizedAddress}, " +
                "displayName=$displayName, " +
                "handleFound=${handle != null}, " +
                "avatarPath=$avatarPath"
            )

            conversations.add(
                ConversationInfo(
                    chatGuid = chat.sourceId,
                    displayName = displayName,
                    isGroup = false,
                    latestMessageDate = chat.latestMessageDate ?: 0L,
                    avatarPath = avatarPath
                )
            )
        }

        // Add group chats - batch fetch participants for all group chats
        Timber.tag(TAG).d("Processing ${groupChats.size} group chats")
        val groupChatGuids = groupChats.map { it.guid }
        val allParticipants = if (groupChatGuids.isNotEmpty()) {
            chatParticipantDao.getParticipantsWithChatGuids(groupChatGuids)
                .groupBy { it.chatGuid }
        } else {
            emptyMap()
        }
        Timber.tag(TAG).d("Fetched participants for ${allParticipants.size} group chats")

        // Batch fetch unified chats for group avatar lookup
        val unifiedChatIds = groupChats.mapNotNull { it.unifiedChatId }
        val unifiedChatsMap = if (unifiedChatIds.isNotEmpty()) {
            unifiedChatDao.getByIds(unifiedChatIds).associateBy { it.id }
        } else {
            emptyMap()
        }

        for (chat in groupChats) {
            val displayName = chat.displayName
                ?: chat.chatIdentifier
                ?: "Group"

            // Get participants for this group chat (for collage avatar)
            val participants = allParticipants[chat.guid] ?: emptyList()
            val participantNames = participants.map { it.handle.displayName }
            val participantAvatarPaths = participants.map { it.handle.cachedAvatarPath }

            // Get avatar from unified chat
            val unifiedChat = chat.unifiedChatId?.let { unifiedChatsMap[it] }
            val avatarPath = unifiedChat?.effectiveAvatarPath

            Timber.tag(TAG).d(
                "Group chat: guid=${chat.guid.take(20)}, " +
                "displayName=$displayName, " +
                "participantCount=${participants.size}, " +
                "participantNames=${participantNames.take(3)}"
            )

            conversations.add(
                ConversationInfo(
                    chatGuid = chat.guid,
                    displayName = displayName,
                    isGroup = true,
                    latestMessageDate = chat.latestMessageDate ?: 0L,
                    avatarPath = avatarPath,
                    participantNames = participantNames,
                    participantAvatarPaths = participantAvatarPaths
                )
            )
        }

        // Sort by latest message date (most recent first)
        return conversations.sortedByDescending { it.latestMessageDate }
    }

    /**
     * Build a ShortcutInfoCompat for a conversation.
     * Uses actual contact photo when available, falls back to generated avatar.
     */
    private fun buildShortcut(conversation: ConversationInfo, rank: Int): ShortcutInfoCompat {
        val shortcutId = "$SHORTCUT_ID_PREFIX${conversation.chatGuid}"

        // Create intent for direct share - includes chatGuid to skip picker
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            putExtra(NotificationChannelManager.EXTRA_CHAT_GUID, conversation.chatGuid)
            // Type is required for share intents
            type = "text/plain"
        }

        // Generate avatar icon based on conversation type
        val avatarIcon: IconCompat = when {
            // Has custom avatar path (contact photo or custom group photo)
            conversation.avatarPath != null -> {
                Timber.tag(TAG).d("Loading photo for ${conversation.displayName}: ${conversation.avatarPath}")
                val photoBitmap = AvatarGenerator.loadContactPhotoBitmap(
                    context,
                    conversation.avatarPath,
                    128,
                    circleCrop = false  // Use adaptive icon format
                )
                if (photoBitmap != null) {
                    Timber.tag(TAG).d("Photo loaded successfully for ${conversation.displayName}")
                    IconCompat.createWithAdaptiveBitmap(photoBitmap)
                } else {
                    Timber.tag(TAG).w("Photo load FAILED for ${conversation.displayName}, using fallback")
                    if (conversation.isGroup && conversation.participantNames.isNotEmpty()) {
                        GroupAvatarRenderer.generateGroupAdaptiveIconCompatWithPhotos(
                            context, conversation.participantNames, conversation.participantAvatarPaths, 128
                        )
                    } else {
                        AvatarGenerator.generateAdaptiveIconCompat(context, conversation.displayName, 128)
                    }
                }
            }
            // Group chat without custom avatar - use participant collage
            conversation.isGroup && conversation.participantNames.isNotEmpty() -> {
                Timber.tag(TAG).d("Generating group collage for ${conversation.displayName} with ${conversation.participantNames.size} participants")
                GroupAvatarRenderer.generateGroupAdaptiveIconCompatWithPhotos(
                    context, conversation.participantNames, conversation.participantAvatarPaths, 128
                )
            }
            // 1:1 chat without avatar - generate initials avatar
            else -> {
                Timber.tag(TAG).d("No avatarPath for ${conversation.displayName}, using generated avatar")
                AvatarGenerator.generateAdaptiveIconCompat(context, conversation.displayName, 128)
            }
        }

        // Create Person for the conversation
        val person = Person.Builder()
            .setName(conversation.displayName)
            .setKey(conversation.chatGuid)
            .setIcon(avatarIcon)
            .build()

        return ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(conversation.displayName)
            .setLongLabel(conversation.displayName)
            .setIcon(avatarIcon)
            .setIntent(intent)
            .setLongLived(true)
            .setIsConversation()
            .setLocusId(LocusIdCompat(conversation.chatGuid))
            .setPerson(person)
            .setCategories(setOf(SHARE_TARGET_CATEGORY))
            .setRank(rank)
            .build()
    }

    /**
     * Data class to hold conversation info for shortcut generation.
     */
    private data class ConversationInfo(
        val chatGuid: String,
        val displayName: String,
        val isGroup: Boolean,
        val latestMessageDate: Long,
        val avatarPath: String? = null,  // Contact photo or custom group photo
        // For group chats without custom avatar: participant names and their avatar paths
        val participantNames: List<String> = emptyList(),
        val participantAvatarPaths: List<String?> = emptyList()
    )
}
