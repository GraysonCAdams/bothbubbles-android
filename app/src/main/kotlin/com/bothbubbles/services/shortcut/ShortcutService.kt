package com.bothbubbles.services.shortcut

import android.content.Context
import android.content.Intent
import timber.log.Timber
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.bothbubbles.MainActivity
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
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
    private val unifiedChatGroupDao: UnifiedChatGroupDao,
    private val chatDao: ChatDao,
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
            // Combine unified groups (1:1 chats) and group chats into a single stream
            combine(
                unifiedChatGroupDao.observeActiveGroups(),
                chatDao.observeActiveGroupChats()
            ) { unifiedGroups, groupChats ->
                Pair(unifiedGroups, groupChats)
            }
            .distinctUntilChanged()
            .collect { (unifiedGroups, groupChats) ->
                debounceAndUpdate(unifiedGroups, groupChats)
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

    /**
     * Debounce updates to prevent rapid shortcut publishing during bulk operations.
     */
    private fun debounceAndUpdate(
        unifiedGroups: List<UnifiedChatGroupEntity>,
        groupChats: List<ChatEntity>
    ) {
        debounceJob?.cancel()
        debounceJob = applicationScope.launch(ioDispatcher) {
            delay(DEBOUNCE_MS)
            updateShortcuts(unifiedGroups, groupChats)
        }
    }

    /**
     * Update sharing shortcuts based on current conversations.
     */
    private fun updateShortcuts(
        unifiedGroups: List<UnifiedChatGroupEntity>,
        groupChats: List<ChatEntity>
    ) {
        try {
            // Check if rate limiting is active
            if (ShortcutManagerCompat.isRateLimitingActive(context)) {
                Timber.w("Shortcut rate limiting active, skipping update")
                return
            }

            // Build combined list of conversations sorted by recency
            val conversations = buildConversationList(unifiedGroups, groupChats)

            if (conversations.isEmpty()) {
                Timber.d("No conversations to publish as shortcuts")
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                return
            }

            // Take top N conversations
            val topConversations = conversations.take(MAX_SHORTCUTS)

            Timber.d("Publishing ${topConversations.size} share target shortcuts")

            // Build and publish shortcuts
            topConversations.forEachIndexed { index, conversation ->
                val shortcut = buildShortcut(conversation, index)
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            }

        } catch (e: Exception) {
            Timber.e(e, "Error updating sharing shortcuts")
        }
    }

    /**
     * Build a combined list of conversations from unified groups and group chats.
     */
    private fun buildConversationList(
        unifiedGroups: List<UnifiedChatGroupEntity>,
        groupChats: List<ChatEntity>
    ): List<ConversationInfo> {
        val conversations = mutableListOf<ConversationInfo>()

        // Add unified groups (1:1 chats)
        for (group in unifiedGroups) {
            val displayName = group.displayName
                ?: PhoneNumberFormatter.format(group.identifier)
            conversations.add(
                ConversationInfo(
                    chatGuid = group.primaryChatGuid,
                    displayName = displayName,
                    isGroup = false,
                    latestMessageDate = group.latestMessageDate ?: 0L
                )
            )
        }

        // Add group chats
        for (chat in groupChats) {
            val displayName = chat.displayName
                ?: chat.chatIdentifier
                ?: "Group"
            conversations.add(
                ConversationInfo(
                    chatGuid = chat.guid,
                    displayName = displayName,
                    isGroup = true,
                    latestMessageDate = chat.lastMessageDate ?: 0L
                )
            )
        }

        // Sort by latest message date (most recent first)
        return conversations.sortedByDescending { it.latestMessageDate }
    }

    /**
     * Build a ShortcutInfoCompat for a conversation.
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

        // Generate avatar icon
        val avatarIcon = AvatarGenerator.generateIconCompat(context, conversation.displayName, 128)

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
        val latestMessageDate: Long
    )
}
