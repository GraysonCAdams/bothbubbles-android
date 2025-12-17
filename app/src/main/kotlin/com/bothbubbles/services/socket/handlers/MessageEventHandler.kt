package com.bothbubbles.services.socket.handlers

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.data.local.db.entity.rawDisplayName
import com.bothbubbles.core.network.api.dto.MessageDto
import com.bothbubbles.data.repository.LinkPreviewRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.messaging.IncomingMessageHandler
import com.bothbubbles.services.autoresponder.AutoResponderService
import com.bothbubbles.services.categorization.CategorizationRepository
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.socket.SocketEvent
import com.bothbubbles.services.socket.UiRefreshEvent
import com.bothbubbles.services.spam.SpamRepository
import com.bothbubbles.ui.effects.MessageEffect
import com.bothbubbles.util.MessageDeduplicator
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.util.parsing.UrlParsingUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles message-related socket events:
 * - New message received
 * - Message updated (read receipt, delivery, edit, reaction)
 * - Message deleted/unsent
 * - Message send error
 */
@Singleton
class MessageEventHandler @Inject constructor(
    private val messageRepository: MessageRepository,
    private val incomingMessageHandler: IncomingMessageHandler,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val notificationService: NotificationService,
    private val linkPreviewRepository: LinkPreviewRepository,
    private val spamRepository: SpamRepository,
    private val categorizationRepository: CategorizationRepository,
    private val androidContactsService: AndroidContactsService,
    private val messageDeduplicator: MessageDeduplicator,
    private val activeConversationManager: ActiveConversationManager,
    private val autoResponderService: AutoResponderService
) {
    companion object {
        private const val TAG = "MessageEventHandler"
        // Cached regex for whitespace splitting
        private val WHITESPACE_REGEX = Regex("\\s+")
        // Contact lookup cache TTL (5 minutes)
        private const val CONTACT_CACHE_TTL_MS = 5 * 60 * 1000L
    }

    /**
     * Cached contact lookup result to avoid repeated Android Contacts queries
     * during message bursts (e.g., after reconnect).
     */
    private data class CachedContactInfo(
        val displayName: String?,
        val avatarUri: String?,
        val timestamp: Long
    )

    // In-memory contact lookup cache with mutex for thread-safe access
    private val contactCacheMutex = Mutex()
    private val contactCache = mutableMapOf<String, CachedContactInfo>()

    suspend fun handleNewMessage(
        event: SocketEvent.NewMessage,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>,
        scope: CoroutineScope
    ) {
        Timber.d("Handling new message: ${event.message.guid}")

        // Save message to database via IncomingMessageHandler (services layer)
        val savedMessage = incomingMessageHandler.handleIncomingMessage(event.message, event.chatGuid)

        // Emit UI refresh events for immediate updates
        uiRefreshEvents.tryEmit(UiRefreshEvent.NewMessage(event.chatGuid, savedMessage.guid))
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("new_message"))

        // Show notification if not from me
        if (!savedMessage.isFromMe) {
            // Check for duplicate notification (message may arrive via both socket and FCM)
            if (!messageDeduplicator.shouldNotifyForMessage(savedMessage.guid)) {
                Timber.i("Message ${savedMessage.guid} already notified, skipping duplicate notification")
                return
            }

            // Check if user is currently viewing this conversation
            if (activeConversationManager.isConversationActive(event.chatGuid)) {
                Timber.i("Chat ${event.chatGuid} is currently active, skipping notification")
                return
            }

            val chat = chatDao.getChatByGuid(event.chatGuid)
            val senderAddress = event.message.handle?.address ?: ""
            val messageText = savedMessage.text ?: ""

            // Check if notifications are disabled for this chat
            if (chat?.notificationsEnabled == false) {
                Timber.i("Notifications disabled for chat ${event.chatGuid}, skipping notification")
                return
            }

            // Check if chat is snoozed - if snoozed, skip notification
            if (chat?.isSnoozed == true) {
                Timber.i("Chat ${event.chatGuid} is snoozed, skipping notification")
                return
            }

            // Check for spam - if spam, skip notification
            val spamResult = spamRepository.evaluateAndMarkSpam(event.chatGuid, senderAddress, messageText)
            if (spamResult.isSpam) {
                Timber.i("iMessage from $senderAddress detected as spam (score: ${spamResult.score}), skipping notification")
                return
            }

            // Auto-responder check - send greeting to first-time iMessage contacts
            // Runs asynchronously to not delay notification
            if (senderAddress.isNotBlank()) {
                scope.launch {
                    autoResponderService.maybeAutoRespond(
                        chatGuid = event.chatGuid,
                        senderAddress = senderAddress,
                        isFromMe = false
                    )
                }
            }

            // Categorize the message for filtering purposes
            categorizationRepository.evaluateAndCategorize(event.chatGuid, senderAddress, messageText)

            val (senderName, senderAvatarUri) = resolveSenderNameAndAvatar(event.message)

            // Check for invisible ink effect - hide actual content in notification
            val isInvisibleInk = MessageEffect.fromStyleId(savedMessage.expressiveSendStyleId) == MessageEffect.Bubble.InvisibleInk
            val notificationText = if (isInvisibleInk) {
                if (savedMessage.hasAttachments) {
                    "Image sent with Invisible Ink"
                } else {
                    "Message sent with Invisible Ink"
                }
            } else {
                messageText
            }

            // Fetch link preview data if message contains a URL (skip for invisible ink)
            val (linkTitle, linkDomain) = if (!isInvisibleInk && (messageText.contains("http://") || messageText.contains("https://"))) {
                val detectedUrl = UrlParsingUtils.getFirstUrl(messageText)
                if (detectedUrl != null) {
                    val preview = linkPreviewRepository.getLinkPreview(detectedUrl.url)
                    val title = preview?.title?.takeIf { it.isNotBlank() }
                    val domain = preview?.domain ?: detectedUrl.domain
                    title to domain
                } else {
                    null to null
                }
            } else {
                null to null
            }

            // For group chats, extract first name for cleaner notification display
            val displaySenderName = if (chat?.isGroup == true && senderName != null) {
                extractFirstName(senderName)
            } else {
                senderName
            }

            // For 1:1 chats, use sender's contact name as title; for groups, use group name
            val chatTitle = if (chat?.isGroup == true) {
                chat.displayName ?: chat.chatIdentifier?.let { PhoneNumberFormatter.format(it) } ?: ""
            } else {
                senderName
                    ?: chat?.displayName
                    ?: chat?.chatIdentifier?.let { PhoneNumberFormatter.format(it) }
                    ?: ""
            }

            // For group chats, fetch participant names for the group avatar collage
            val participantNames = if (chat?.isGroup == true) {
                chatDao.getParticipantsForChat(event.chatGuid).map { it.rawDisplayName }
            } else {
                emptyList()
            }

            notificationService.showMessageNotification(
                chatGuid = event.chatGuid,
                chatTitle = chatTitle,
                messageText = notificationText,
                messageGuid = savedMessage.guid,
                senderName = displaySenderName,
                senderAddress = senderAddress,
                isGroup = chat?.isGroup ?: false,
                avatarUri = senderAvatarUri,
                linkPreviewTitle = linkTitle,
                linkPreviewDomain = linkDomain,
                participantNames = participantNames,
                subject = savedMessage.subject
            )
        }
    }

    suspend fun handleMessageUpdated(
        event: SocketEvent.MessageUpdated,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Timber.d("Handling message update: ${event.message.guid}")
        incomingMessageHandler.handleMessageUpdate(event.message, event.chatGuid)

        // Emit UI refresh events for immediate updates (read receipts, delivery status, edits)
        uiRefreshEvents.tryEmit(UiRefreshEvent.MessageUpdated(event.chatGuid, event.message.guid))
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("message_updated"))
    }

    suspend fun handleMessageDeleted(
        event: SocketEvent.MessageDeleted,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Timber.d("Handling message deletion: ${event.messageGuid}")
        messageRepository.deleteMessageLocally(event.messageGuid)

        // Emit UI refresh events
        uiRefreshEvents.tryEmit(UiRefreshEvent.MessageDeleted(event.chatGuid, event.messageGuid))
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("message_deleted"))
    }

    suspend fun handleMessageSendError(
        event: SocketEvent.MessageSendError,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Timber.e("Message send error: ${event.tempGuid} - ${event.errorMessage}")

        // Update the message in database to mark as failed
        messageRepository.markMessageAsFailed(event.tempGuid, event.errorMessage)

        // Emit UI refresh event so ChatViewModel can update the message state
        uiRefreshEvents.tryEmit(UiRefreshEvent.MessageSendFailed(event.tempGuid, event.errorMessage))
    }

    // ===== Private Helper Methods =====

    /**
     * Extract the first name from a full name, excluding emojis and non-letter characters.
     */
    private fun extractFirstName(fullName: String): String {
        val words = fullName.trim().split(WHITESPACE_REGEX)
        for (word in words) {
            val cleaned = word.filter { it.isLetterOrDigit() }
            if (cleaned.isNotEmpty() && cleaned.any { it.isLetter() }) {
                return cleaned
            }
        }
        return words.firstOrNull()?.filter { it.isLetterOrDigit() } ?: fullName
    }

    /**
     * Look up contact info from Android Contacts with in-memory caching.
     */
    private suspend fun lookupContactWithCache(address: String): Pair<String?, String?> {
        val now = System.currentTimeMillis()

        // Check in-memory cache first (mutex-protected)
        contactCacheMutex.withLock {
            contactCache[address]?.let { cached ->
                if (now - cached.timestamp < CONTACT_CACHE_TTL_MS) {
                    return cached.displayName to cached.avatarUri
                }
                contactCache.remove(address)
            }
        }

        // Not in cache - do the Android Contacts lookup
        val contactName = androidContactsService.getContactDisplayName(address)
        val photoUri = if (contactName != null) {
            androidContactsService.getContactPhotoUri(address)
        } else {
            null
        }

        // Store result in cache
        contactCacheMutex.withLock {
            contactCache[address] = CachedContactInfo(contactName, photoUri, now)
        }

        return contactName to photoUri
    }

    /**
     * Resolve the sender's display name and avatar from the message.
     */
    private suspend fun resolveSenderNameAndAvatar(message: MessageDto): Pair<String?, String?> {
        // Try to get from embedded handle
        message.handle?.let { handleDto ->
            val address = handleDto.address

            // Look up local handle entity for cached contact info
            val localHandle = handleDao.getHandlesByAddress(address).firstOrNull()

            if (localHandle?.cachedDisplayName != null) {
                return localHandle.cachedDisplayName to localHandle.cachedAvatarPath
            }

            // Use in-memory cached lookup
            val (contactName, photoUri) = lookupContactWithCache(address)
            if (contactName != null) {
                localHandle?.let { handle ->
                    handleDao.updateCachedContactInfo(handle.id, contactName, photoUri)
                }
                return contactName to photoUri
            }

            // Fall back to inferred name
            if (localHandle?.inferredName != null) {
                return localHandle.displayName to localHandle.cachedAvatarPath
            }

            return (handleDto.formattedAddress ?: address) to null
        }

        // No embedded handle - try by handleId
        message.handleId?.let { handleId ->
            val handle = handleDao.getHandleById(handleId)
            if (handle != null) {
                if (handle.cachedDisplayName != null) {
                    return handle.cachedDisplayName to handle.cachedAvatarPath
                }

                val (contactName, photoUri) = lookupContactWithCache(handle.address)
                if (contactName != null) {
                    handleDao.updateCachedContactInfo(handle.id, contactName, photoUri)
                    return contactName to photoUri
                }

                return handle.displayName to handle.cachedAvatarPath
            }
        }

        return null to null
    }
}
