package com.bothbubbles.services.auto

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.bothbubbles.R
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.prefs.FeaturePreferences
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.messaging.MessageSendingService
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.util.PhoneNumberFormatter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android Auto screen displaying messages in a conversation.
 * Shows recent messages with pagination and provides actions to reply or mark as read.
 */
class ConversationDetailScreen(
    carContext: CarContext,
    private val chat: ChatEntity,
    private val messageDao: MessageDao,
    private val handleDao: HandleDao,
    private val chatRepository: ChatRepository,
    private val messageSendingService: MessageSendingService,
    private val syncService: SyncService? = null,
    private val socketConnection: SocketConnection? = null,
    private val featurePreferences: FeaturePreferences? = null,
    private val attachmentRepository: AttachmentRepository? = null,
    private val attachmentDao: AttachmentDao? = null,
    private val onRefresh: () -> Unit
) : Screen(carContext) {

    // Screen-local scope that follows screen lifecycle
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Audio player for voice messages
    private val audioPlayer by lazy { AutoAudioPlayer(carContext) }

    // TTS for reading messages aloud
    private val textToSpeech by lazy { AutoTextToSpeech(carContext) }

    // Cache for audio attachments by message guid
    private val audioAttachmentCache = mutableMapOf<String, AttachmentEntity?>()

    @Volatile
    private var cachedMessages: List<MessageEntity> = emptyList()

    @Volatile
    private var isLoadingMessages: Boolean = true

    // Pagination state
    @Volatile
    private var currentPage = 0

    @Volatile
    private var hasMoreMessages = true

    @Volatile
    private var totalMessageCount = 0

    // Privacy mode - hide message content when enabled
    @Volatile
    private var privacyModeEnabled = false

    // Cache for sender names to avoid blocking calls
    private val senderNameCache = mutableMapOf<Long, String>()

    private val dateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    init {
        // Register lifecycle observer to cancel scope when screen is destroyed
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                audioPlayer.stop()
                textToSpeech.shutdown()
                screenScope.cancel()
            }
        })

        // Load privacy mode preference
        screenScope.launch {
            featurePreferences?.androidAutoPrivacyMode?.collect { enabled ->
                if (enabled != privacyModeEnabled) {
                    privacyModeEnabled = enabled
                    invalidate()
                }
            }
        }

        refreshMessages()

        // Auto mark-as-read when entering conversation
        // This ensures the notification is cleared on the phone
        if (chat.hasUnreadMessage) {
            screenScope.launch {
                try {
                    chatRepository.markChatAsRead(chat.guid)
                    Timber.d("Auto-marked chat ${chat.guid} as read")
                    onRefresh() // Refresh conversation list to update unread indicator
                } catch (e: Exception) {
                    Timber.e(e, "Failed to auto mark as read")
                }
            }
        }
    }

    private fun refreshMessages() {
        screenScope.launch {
            try {
                isLoadingMessages = true

                // Get total count to determine if there are more messages
                totalMessageCount = messageDao.getMessageCountForChat(chat.guid)

                val messageLimit = (currentPage + 1) * PAGE_SIZE
                val messages = messageDao.observeMessagesForChat(
                    chatGuid = chat.guid,
                    limit = messageLimit,
                    offset = 0
                ).first()

                cachedMessages = messages
                hasMoreMessages = messages.size < totalMessageCount
                isLoadingMessages = false

                // Pre-fetch sender names for all messages (batch query for efficiency)
                val handleIds = messages.mapNotNull { it.handleId }.distinct()
                if (handleIds.isNotEmpty()) {
                    val handles = handleDao.getHandlesByIds(handleIds)
                    handles.forEach { handle ->
                        val displayName = handle.cachedDisplayName
                            ?: handle.inferredName
                            ?: handle.formattedAddress
                            ?: handle.address.let { PhoneNumberFormatter.format(it) }
                        senderNameCache[handle.id] = displayName
                    }
                }

                // If no messages found, trigger priority sync to load them immediately
                if (messages.isEmpty() && syncService != null) {
                    Timber.i("No messages found, triggering priority sync for ${chat.guid}")
                    syncService.prioritizeChatSync(chat.guid)
                    // Re-fetch after a short delay to pick up synced messages
                    kotlinx.coroutines.delay(2000)
                    val refreshedMessages = messageDao.observeMessagesForChat(
                        chatGuid = chat.guid,
                        limit = messageLimit,
                        offset = 0
                    ).first()
                    cachedMessages = refreshedMessages
                    hasMoreMessages = refreshedMessages.size < totalMessageCount

                    // Also pre-fetch sender names for refreshed messages
                    val refreshedHandleIds = refreshedMessages.mapNotNull { it.handleId }.distinct()
                    if (refreshedHandleIds.isNotEmpty()) {
                        val refreshedHandles = handleDao.getHandlesByIds(refreshedHandleIds)
                        refreshedHandles.forEach { handle ->
                            val displayName = handle.cachedDisplayName
                                ?: handle.inferredName
                                ?: handle.formattedAddress
                                ?: handle.address.let { PhoneNumberFormatter.format(it) }
                            senderNameCache[handle.id] = displayName
                        }
                    }
                }

                Timber.d("Loaded ${cachedMessages.size} messages, total=$totalMessageCount, hasMore=$hasMoreMessages")

                // Pre-fetch audio attachments for messages with attachments
                prefetchAudioAttachments(cachedMessages)

                invalidate()
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh messages")
                isLoadingMessages = false
                invalidate()
            }
        }
    }

    /**
     * Pre-fetch audio attachments for messages to enable playback.
     */
    private suspend fun prefetchAudioAttachments(messages: List<MessageEntity>) {
        if (attachmentDao == null) return

        val messagesWithAttachments = messages.filter { it.hasAttachments }
        if (messagesWithAttachments.isEmpty()) return

        val messageGuids = messagesWithAttachments.map { it.guid }
        try {
            val attachments = attachmentDao.getAttachmentsForMessages(messageGuids)
            for (attachment in attachments) {
                // Check if it's an audio attachment
                if (attachment.mimeType?.startsWith("audio/") == true) {
                    audioAttachmentCache[attachment.messageGuid] = attachment
                    Timber.d("Found audio attachment: ${attachment.guid} for message ${attachment.messageGuid}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to prefetch audio attachments")
        }
    }

    private fun loadMoreMessages() {
        if (!hasMoreMessages || isLoadingMessages) return
        currentPage++
        Timber.d("Loading more messages, page=$currentPage")
        refreshMessages()
    }

    override fun onGetTemplate(): Template {
        val displayTitle = chat.displayName ?: "Conversation"

        val itemListBuilder = ItemList.Builder()

        // Add "Load Older" row at the top if there are more messages
        if (hasMoreMessages && cachedMessages.isNotEmpty()) {
            val loadOlderRow = Row.Builder()
                .setTitle("Load Older Messages...")
                .setOnClickListener { loadMoreMessages() }
                .build()
            itemListBuilder.addItem(loadOlderRow)
        }

        // Add messages (newest first in DB, but displayed oldest first for reading order)
        val messagesToShow = cachedMessages.reversed()
        for (message in messagesToShow) {
            val row = buildMessageRow(message)
            if (row != null) {
                itemListBuilder.addItem(row)
            }
        }

        // Add "Read Aloud" row if there are messages and privacy mode is off
        if (cachedMessages.isNotEmpty() && !privacyModeEnabled) {
            val readAloudRow = Row.Builder()
                .setTitle(if (textToSpeech.isSpeaking) "Stop Reading" else "Read Aloud")
                .setImage(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_btn_speak_now)
                    ).build()
                )
                .setOnClickListener {
                    readMessagesAloud()
                }
                .build()
            itemListBuilder.addItem(readAloudRow)
        }

        if (cachedMessages.isEmpty()) {
            itemListBuilder.setNoItemsMessage(
                if (isLoadingMessages) "Loading messages..." else "No messages yet"
            )
        }

        // Create action strip with reply and mark as read
        val replyAction = Action.Builder()
            .setTitle("Reply")
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_send)
                ).build()
            )
            .setOnClickListener {
                // Push to voice reply screen
                screenManager.push(
                    VoiceReplyScreen(
                        carContext = carContext,
                        chat = chat,
                        messageSendingService = messageSendingService,
                        onMessageSent = {
                            refreshMessages()
                            onRefresh()
                        },
                        socketConnection = socketConnection
                    )
                )
            }
            .build()

        val markReadAction = Action.Builder()
            .setTitle("Mark Read")
            .setOnClickListener {
                screenScope.launch {
                    try {
                        chatRepository.markChatAsRead(chat.guid)
                        CarToast.makeText(carContext, "Marked as read", CarToast.LENGTH_SHORT).show()
                        onRefresh()
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to mark as read")
                    }
                }
            }
            .build()

        return ListTemplate.Builder()
            .setTitle(displayTitle)
            .setHeaderAction(Action.BACK)
            .setSingleList(itemListBuilder.build())
            .addAction(replyAction)
            .addAction(markReadAction)
            .build()
    }

    private fun buildMessageRow(message: MessageEntity): Row? {
        // Check if this is an audio message
        val audioAttachment = audioAttachmentCache[message.guid]
        val isAudioMessage = audioAttachment != null

        val rawText = message.text
        val hasText = !rawText.isNullOrBlank()

        // If no text and not an audio message with attachment, skip
        if (!hasText && !isAudioMessage) return null

        // Privacy mode: show generic message instead of content
        val text = when {
            privacyModeEnabled -> if (isAudioMessage) "🔊 Voice Message" else "Message"
            isAudioMessage -> "🔊 Voice Message" + if (hasText) " - ${AutoUtils.parseReactionText(rawText!!)}" else ""
            hasText -> AutoUtils.parseReactionText(rawText!!)
            else -> return null
        }

        val senderName = if (message.isFromMe) {
            "You"
        } else {
            getSenderName(message)
        }

        val time = dateFormat.format(Date(message.dateCreated))

        return try {
            val rowBuilder = Row.Builder()
                .setTitle("$senderName - $time")
                .addText(text)

            // Add click handler for audio messages to play them
            if (isAudioMessage && attachmentRepository != null) {
                rowBuilder.setOnClickListener {
                    playAudioMessage(audioAttachment!!)
                }
            }

            rowBuilder.build()
        } catch (e: Exception) {
            Timber.e(e, "Failed to build message row")
            null
        }
    }

    /**
     * Play an audio message attachment.
     * Downloads if not available locally, then plays with audio focus.
     */
    private fun playAudioMessage(attachment: AttachmentEntity) {
        if (attachmentRepository == null) return

        // If already playing, stop
        if (audioPlayer.isPlaying) {
            audioPlayer.stop()
            CarToast.makeText(carContext, "Stopped", CarToast.LENGTH_SHORT).show()
            return
        }

        CarToast.makeText(carContext, "Loading audio...", CarToast.LENGTH_SHORT).show()

        screenScope.launch {
            try {
                // Download if not available locally
                val file = attachmentRepository.getAttachmentFile(attachment.guid).getOrThrow()

                audioPlayer.playAudio(
                    filePath = file.absolutePath,
                    onComplete = {
                        CarToast.makeText(carContext, "Playback complete", CarToast.LENGTH_SHORT).show()
                    },
                    onError = { e ->
                        Timber.e(e, "Audio playback error")
                        CarToast.makeText(carContext, "Playback failed", CarToast.LENGTH_SHORT).show()
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to play audio message")
                CarToast.makeText(carContext, "Failed to load audio", CarToast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Read recent messages aloud using TTS.
     */
    private fun readMessagesAloud() {
        // If already speaking, stop
        if (textToSpeech.isSpeaking) {
            textToSpeech.stop()
            CarToast.makeText(carContext, "Stopped", CarToast.LENGTH_SHORT).show()
            invalidate()
            return
        }

        // Get recent messages to read (last 5, oldest first)
        val messagesToRead = cachedMessages
            .reversed()
            .takeLast(MAX_MESSAGES_TO_READ)
            .filter { !it.text.isNullOrBlank() }

        if (messagesToRead.isEmpty()) {
            CarToast.makeText(carContext, "No messages to read", CarToast.LENGTH_SHORT).show()
            return
        }

        // Build list of (sender, text) pairs
        val messages = messagesToRead.map { message ->
            val sender = if (message.isFromMe) "You" else getSenderName(message).ifEmpty { "Someone" }
            val text = message.text ?: ""
            sender to text
        }

        CarToast.makeText(carContext, "Reading ${messages.size} messages...", CarToast.LENGTH_SHORT).show()
        invalidate()

        textToSpeech.speakMessages(
            messages = messages,
            onComplete = {
                invalidate()
            }
        )
    }

    private fun getSenderName(message: MessageEntity): String {
        val handleId = message.handleId ?: return ""
        // Use pre-fetched cache to avoid blocking calls
        return senderNameCache[handleId] ?: ""
    }

    companion object {
        private const val PAGE_SIZE = 15  // Show 15 messages per page
        private const val MAX_MESSAGES_TO_READ = 5  // Limit TTS to recent messages
    }
}
