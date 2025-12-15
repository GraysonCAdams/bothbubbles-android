package com.bothbubbles.services.auto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.car.app.CarContext
import androidx.car.app.ScreenManager
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.bothbubbles.R
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.prefs.FeaturePreferences
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.messaging.MessageSendingService
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Content delegate for the Chats tab in Android Auto.
 *
 * Uses ConversationItem API (Car App Library 1.1+) for semantic messaging UI:
 * - Dedicated slots for title (contact), subtitle (snippet), and icon (avatar)
 * - Native unread state handling via setUnreadConversation()
 * - Standardized click target sizes for driver safety
 *
 * Implements async image loading with invalidation pattern:
 * 1. Initial render with placeholder icons
 * 2. Background coroutine loads contact images
 * 3. Cache results and call invalidate()
 * 4. Subsequent render uses cached bitmaps
 */
class ConversationListContent(
    private val carContext: CarContext,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val handleDao: HandleDao,
    private val chatRepository: ChatRepository,
    private val messageSendingService: MessageSendingService,
    private val syncService: SyncService?,
    private val socketConnection: SocketConnection?,
    private val featurePreferences: FeaturePreferences?,
    private val attachmentRepository: AttachmentRepository?,
    private val attachmentDao: AttachmentDao?,
    private val screenManager: ScreenManager,
    private val onInvalidate: () -> Unit
) {
    private val contentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cache for conversations
    @Volatile
    private var cachedConversations: List<ChatEntity> = emptyList()

    @Volatile
    private var cachedMessages: Map<String, MessageEntity?> = emptyMap()

    // Pagination state
    @Volatile
    private var currentPage = 0

    @Volatile
    private var hasMoreConversations = true

    @Volatile
    private var isLoading = false

    // Privacy mode - hide message content when enabled
    @Volatile
    private var privacyModeEnabled = false

    // Name caches
    private val senderNameCache = mutableMapOf<Long, String>()
    private val participantNameCache = mutableMapOf<String, String>()

    // Avatar bitmap cache for async loading pattern
    private val avatarCache = mutableMapOf<String, Bitmap?>()

    @Volatile
    private var avatarsLoading = false

    init {
        // Load privacy mode preference
        contentScope.launch {
            featurePreferences?.androidAutoPrivacyMode?.collect { enabled ->
                if (enabled != privacyModeEnabled) {
                    privacyModeEnabled = enabled
                    onInvalidate()
                }
            }
        }
        refreshData()
    }

    fun refreshData() {
        if (isLoading) return
        isLoading = true

        contentScope.launch {
            try {
                val chats = chatDao.getActiveChats().first()

                val sortedChats = chats.sortedWith(
                    compareByDescending<ChatEntity> { it.hasUnreadMessage && it.isPinned }
                        .thenByDescending { it.hasUnreadMessage }
                        .thenByDescending { it.isPinned }
                        .thenByDescending { it.lastMessageDate ?: 0L }
                )

                val displayCount = minOf((currentPage + 1) * PAGE_SIZE, sortedChats.size)
                val chatsToDisplay = sortedChats.take(displayCount)
                hasMoreConversations = displayCount < sortedChats.size

                cachedConversations = chatsToDisplay

                // Pre-fetch latest messages
                val chatGuids = chatsToDisplay.map { it.guid }
                val latestMessages = messageDao.getLatestMessagesForChats(chatGuids)
                cachedMessages = latestMessages.associateBy { it.chatGuid }

                // Pre-fetch sender names
                val handleIds = latestMessages.mapNotNull { it.handleId }.distinct()
                if (handleIds.isNotEmpty()) {
                    val handles = handleDao.getHandlesByIds(handleIds)
                    handles.forEach { handle ->
                        val displayName = handle.cachedDisplayName
                            ?: handle.inferredName
                            ?: handle.formattedAddress?.take(10)
                            ?: ""
                        senderNameCache[handle.id] = displayName
                    }
                }

                // Pre-fetch participant names
                val participantsByChat = chatDao.getParticipantsWithChatGuids(chatGuids)
                    .groupBy({ it.chatGuid }, { it.handle })
                for (chat in chatsToDisplay) {
                    val participants = participantsByChat[chat.guid] ?: emptyList()
                    val participantNames = when {
                        participants.isEmpty() -> chat.chatIdentifier?.let { PhoneNumberFormatter.format(it) } ?: ""
                        participants.size == 1 -> {
                            participants.first().cachedDisplayName
                                ?: participants.first().formattedAddress
                                ?: participants.first().address
                        }
                        else -> {
                            participants.take(3).joinToString(", ") { handle ->
                                handle.cachedDisplayName
                                    ?: handle.formattedAddress?.take(10)
                                    ?: handle.address.take(10)
                            }
                        }
                    }
                    participantNameCache[chat.guid] = participantNames
                }

                isLoading = false
                onInvalidate()

                // Start async avatar loading
                loadAvatarsAsync(chatsToDisplay, participantsByChat)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to refresh data", e)
                isLoading = false
            }
        }
    }

    /**
     * Async avatar loading with invalidation pattern.
     * Loads contact photos in background, then invalidates to update UI.
     */
    private fun loadAvatarsAsync(
        chats: List<ChatEntity>,
        participantsByChat: Map<String, List<com.bothbubbles.data.local.db.entity.HandleEntity>>
    ) {
        if (avatarsLoading) return
        avatarsLoading = true

        contentScope.launch {
            var anyLoaded = false

            for (chat in chats) {
                if (avatarCache.containsKey(chat.guid)) continue

                val participants = participantsByChat[chat.guid] ?: emptyList()
                val primaryParticipant = participants.firstOrNull()

                // Try to load avatar from cached avatar path
                val avatarBitmap = primaryParticipant?.cachedAvatarPath?.let { path ->
                    try {
                        val file = java.io.File(path)
                        if (file.exists()) {
                            BitmapFactory.decodeFile(path)?.let { bitmap ->
                                // Scale to reasonable size for car display
                                Bitmap.createScaledBitmap(bitmap, AVATAR_SIZE, AVATAR_SIZE, true)
                            }
                        } else null
                    } catch (e: Exception) {
                        android.util.Log.d(TAG, "Failed to load avatar for ${chat.guid}", e)
                        null
                    }
                }

                avatarCache[chat.guid] = avatarBitmap
                if (avatarBitmap != null) anyLoaded = true
            }

            avatarsLoading = false

            // Invalidate to refresh UI with loaded avatars
            if (anyLoaded) {
                onInvalidate()
            }
        }
    }

    private fun loadMoreConversations() {
        if (!hasMoreConversations || isLoading) return
        currentPage++
        refreshData()
    }

    fun buildContent(): Template {
        val itemListBuilder = ItemList.Builder()

        for ((index, chat) in cachedConversations.withIndex()) {
            val item = buildConversationRow(chat)
            if (item != null) {
                itemListBuilder.addItem(item)
            }
        }

        // Add "Load More" item if there are more conversations
        if (hasMoreConversations && cachedConversations.isNotEmpty()) {
            val loadMoreItem = Row.Builder()
                .setTitle(if (isLoading) "Loading..." else "Load More Conversations...")
                .setOnClickListener {
                    if (!isLoading) loadMoreConversations()
                }
                .build()
            itemListBuilder.addItem(loadMoreItem)
        }

        // Add visibility listener for infinite scroll
        // When the last few items become visible, auto-trigger load
        itemListBuilder.setOnItemsVisibilityChangedListener { startIndex, endIndex ->
            val totalItems = cachedConversations.size
            // If we're showing items near the end and there are more, auto-load
            if (hasMoreConversations && !isLoading && endIndex >= totalItems - SCROLL_THRESHOLD) {
                android.util.Log.d(TAG, "Auto-loading more conversations (visible: $startIndex-$endIndex, total: $totalItems)")
                loadMoreConversations()
            }
        }

        if (cachedConversations.isEmpty()) {
            itemListBuilder.setNoItemsMessage(
                if (isLoading) "Loading conversations..." else "No conversations yet"
            )
        }

        return ListTemplate.Builder()
            .setTitle("Messages")
            .setSingleList(itemListBuilder.build())
            .build()
    }

    /**
     * Build Row with async-loaded avatar and unread indicator.
     *
     * Uses the invalidation pattern for avatar loading:
     * 1. Initial render uses placeholder
     * 2. Avatar loaded in background
     * 3. Screen invalidates to show loaded avatar
     *
     * Unread state shown via title prefix (● indicator).
     */
    private fun buildConversationRow(chat: ChatEntity): Row? {
        val latestMessage = cachedMessages[chat.guid]
        val displayTitle = chat.displayName ?: participantNameCache[chat.guid] ?: ""
        val messagePreview = buildMessagePreview(latestMessage, chat)

        // Build title with unread indicator
        val title = if (chat.hasUnreadMessage) {
            "● $displayTitle"
        } else {
            displayTitle
        }

        // Use cached avatar bitmap or fall back to placeholder
        val icon = avatarCache[chat.guid]?.let { bitmap ->
            CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
        } ?: CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.mipmap.ic_launcher)
        ).build()

        return try {
            Row.Builder()
                .setTitle(title)
                .addText(messagePreview)
                .setImage(icon)
                .setOnClickListener {
                    screenManager.push(
                        ConversationDetailScreen(
                            carContext = carContext,
                            chat = chat,
                            messageDao = messageDao,
                            handleDao = handleDao,
                            chatRepository = chatRepository,
                            messageSendingService = messageSendingService,
                            syncService = syncService,
                            socketConnection = socketConnection,
                            featurePreferences = featurePreferences,
                            attachmentRepository = attachmentRepository,
                            attachmentDao = attachmentDao,
                            onRefresh = { refreshData() }
                        )
                    )
                }
                .build()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to build Row for ${chat.guid}", e)
            null
        }
    }

    private fun buildMessagePreview(message: MessageEntity?, chat: ChatEntity): String {
        if (message == null) return "No messages"

        // Privacy mode: show generic message instead of content
        if (privacyModeEnabled) {
            return if (chat.hasUnreadMessage) "New Message" else "Message"
        }

        val rawText = message.text?.take(50) ?: ""
        // Parse reaction text to emoji format for better glanceability
        val text = AutoUtils.parseReactionText(rawText)
        val hasAttachment = message.hasAttachments

        val isGroupChat = chat.displayName != null
        val senderPrefix = when {
            message.isFromMe -> "You: "
            isGroupChat -> {
                val senderName = message.handleId?.let { senderNameCache[it] } ?: ""
                if (senderName.isNotBlank()) "$senderName: " else ""
            }
            else -> ""
        }

        val content = when {
            text.isNotBlank() -> text
            hasAttachment -> "📎 Attachment"
            else -> "No content"
        }

        return senderPrefix + content
    }

    companion object {
        private const val TAG = "ConversationListContent"
        private const val PAGE_SIZE = 15
        private const val AVATAR_SIZE = 64 // Pixels, suitable for car displays
        private const val SCROLL_THRESHOLD = 3 // Load more when within 3 items of end
    }
}
