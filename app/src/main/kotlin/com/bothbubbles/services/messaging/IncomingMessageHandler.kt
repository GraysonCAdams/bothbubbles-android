package com.bothbubbles.services.messaging

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.MessageEditHistoryDao
import com.bothbubbles.data.local.db.dao.SocialMediaLinkDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.core.model.entity.MessageEditHistoryEntity
import com.bothbubbles.core.model.entity.SocialMediaLinkEntity
import com.bothbubbles.core.model.entity.SocialMediaPlatform
import com.bothbubbles.core.model.entity.UnifiedChatEntity
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.ReactionClassifier
import com.bothbubbles.data.local.db.entity.TransferState
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.core.network.api.dto.MessageDto
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.services.nameinference.NameInferenceService
import com.bothbubbles.services.socialmedia.SocialMediaDownloadService
import com.bothbubbles.util.UnifiedChatIdGenerator
import com.bothbubbles.util.parsing.HtmlEntityDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service responsible for handling incoming messages from the server.
 *
 * This service processes messages received via Socket.IO or FCM push notifications,
 * ensuring proper deduplication and coordination with the local database.
 *
 * Key responsibilities:
 * - Message deduplication (handles race conditions between FCM and Socket.IO)
 * - Attachment synchronization for received messages
 * - Chat metadata updates (last message, unread counts)
 * - Name inference from incoming message patterns
 */
@Singleton
class IncomingMessageHandler @Inject constructor(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val attachmentDao: AttachmentDao,
    private val unifiedChatDao: UnifiedChatDao,
    private val messageEditHistoryDao: MessageEditHistoryDao,
    private val socialMediaLinkDao: SocialMediaLinkDao,
    private val settingsDataStore: SettingsDataStore,
    private val nameInferenceService: NameInferenceService,
    private val socialMediaDownloadService: SocialMediaDownloadService,
    private val api: BothBubblesApi,
    @ApplicationScope private val applicationScope: CoroutineScope
) : IncomingMessageProcessor {

    companion object {
        // Instagram URL patterns
        private val INSTAGRAM_PATTERN = Regex(
            """https?://(?:www\.)?instagram\.com/(?:reel|reels|p|share/reel|share/p)/[A-Za-z0-9_-]+[^\s]*"""
        )

        // TikTok URL patterns
        private val TIKTOK_PATTERN = Regex(
            """https?://(?:www\.|vm\.|vt\.)?tiktok\.com/[^\s]+"""
        )
    }
    /**
     * Handle a new message from server (via Socket.IO or push)
     *
     * This method is safe against duplicate processing - if the same message arrives
     * via both FCM and Socket.IO, the unread count will only be incremented once.
     */
    override suspend fun handleIncomingMessage(messageDto: MessageDto, chatGuid: String): MessageEntity {
        val localHandleId = resolveLocalHandleId(messageDto)

        // Resolve or create unified chat for this protocol channel
        val unifiedChatId = resolveUnifiedChatId(chatGuid, messageDto)

        // Create message with unified_chat_id
        val message = messageDto.toEntity(chatGuid, localHandleId, unifiedChatId)

        // CRITICAL: Check if message already exists BEFORE any side effects
        // This prevents duplicate unread count increments when message arrives via both FCM and Socket.IO
        val existingMessage = messageDao.getMessageByGuid(message.guid)
        if (existingMessage != null) {
            Timber.d("Message ${message.guid} already exists, skipping duplicate processing")
            return existingMessage
        }

        // Insert the message - use insertOrIgnore to handle race conditions
        // If another thread beats us, the insert will be ignored
        val insertResult = messageDao.insertMessage(message)
        if (insertResult == -1L) {
            // Another thread inserted this message first - return the existing one
            Timber.d("Message ${message.guid} was inserted by another thread")
            return messageDao.getMessageByGuid(message.guid) ?: message
        }

        // We successfully inserted - safe to update chat metadata
        // Only this thread will execute this block for this message
        if (!message.isFromMe) {
            // Update unified chat's unread count and latest message
            if (unifiedChatId != null) {
                unifiedChatDao.incrementUnreadCount(unifiedChatId)
                unifiedChatDao.updateLatestMessageIfNewer(
                    id = unifiedChatId,
                    date = message.dateCreated,
                    text = message.text,
                    guid = message.guid,
                    isFromMe = false,
                    hasAttachments = message.hasAttachments,
                    source = message.messageSource,
                    dateDelivered = message.dateDelivered,
                    dateRead = message.dateRead,
                    error = message.error
                )
            }

            // Try to infer sender name from self-introduction patterns (e.g., "Hey it's John")
            message.handleId?.let { handleId ->
                nameInferenceService.processIncomingMessage(handleId.toLong(), message.text)
            }
        } else if (unifiedChatId != null) {
            // For sent messages, still update latest message
            unifiedChatDao.updateLatestMessageIfNewer(
                id = unifiedChatId,
                date = message.dateCreated,
                text = message.text,
                guid = message.guid,
                isFromMe = true,
                hasAttachments = message.hasAttachments,
                source = message.messageSource,
                dateDelivered = message.dateDelivered,
                dateRead = message.dateRead,
                error = message.error
            )
        }

        // Sync attachments
        syncIncomingAttachments(messageDto)

        // Store social media links for Reels feed (only for non-reaction messages)
        // Reactions are excluded because they quote the URL but weren't the sender
        val messageText = message.text
        if (!messageText.isNullOrBlank() && messageDto.associatedMessageType == null) {
            storeSocialMediaLinks(
                message = message,
                chatGuid = chatGuid,
                senderAddress = messageDto.handle?.address
            )
        }

        // Background download social media videos if enabled
        // Only process non-reaction messages to avoid caching with wrong sender info
        // Reactions quote the URL but the reactor isn't the original sender
        if (!messageText.isNullOrBlank() && messageDto.associatedMessageType == null) {
            triggerSocialMediaBackgroundDownload(
                messageGuid = message.guid,
                chatGuid = chatGuid,
                text = messageText,
                senderName = if (message.isFromMe) "You" else messageDto.handle?.formattedAddress,
                senderAddress = if (message.isFromMe) null else messageDto.handle?.address,
                timestamp = message.dateCreated
            )
        }

        return message
    }

    /**
     * Detects and stores social media links from message text.
     * This populates the social_media_links table for the Reels feed.
     *
     * Only called for non-reaction messages to ensure correct sender attribution.
     */
    private suspend fun storeSocialMediaLinks(
        message: MessageEntity,
        chatGuid: String,
        senderAddress: String?
    ) {
        val text = message.text ?: return
        val links = mutableListOf<SocialMediaLinkEntity>()

        // Find Instagram URLs
        for (match in INSTAGRAM_PATTERN.findAll(text)) {
            val url = match.value
            links.add(
                SocialMediaLinkEntity(
                    urlHash = hashUrl(url),
                    url = url,
                    messageGuid = message.guid,
                    chatGuid = chatGuid,
                    platform = SocialMediaPlatform.INSTAGRAM.name,
                    senderAddress = if (message.isFromMe) null else senderAddress,
                    isFromMe = message.isFromMe,
                    sentTimestamp = message.dateCreated,
                    isDownloaded = false,
                    createdAt = System.currentTimeMillis()
                )
            )
        }

        // Find TikTok URLs
        for (match in TIKTOK_PATTERN.findAll(text)) {
            val url = match.value
            links.add(
                SocialMediaLinkEntity(
                    urlHash = hashUrl(url),
                    url = url,
                    messageGuid = message.guid,
                    chatGuid = chatGuid,
                    platform = SocialMediaPlatform.TIKTOK.name,
                    senderAddress = if (message.isFromMe) null else senderAddress,
                    isFromMe = message.isFromMe,
                    sentTimestamp = message.dateCreated,
                    isDownloaded = false,
                    createdAt = System.currentTimeMillis()
                )
            )
        }

        if (links.isNotEmpty()) {
            try {
                socialMediaLinkDao.insertAll(links)
                Timber.d("[SocialMedia] Stored ${links.size} social media link(s) from message ${message.guid}")
            } catch (e: Exception) {
                Timber.w(e, "[SocialMedia] Failed to store social media links")
            }
        }
    }

    /**
     * Hash URL for deduplication (matches SocialMediaCacheManager.hashUrl).
     */
    private fun hashUrl(url: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Triggers background download of social media videos from message text.
     * Only runs if background downloading is enabled in settings.
     */
    private fun triggerSocialMediaBackgroundDownload(
        messageGuid: String,
        chatGuid: String,
        text: String,
        senderName: String?,
        senderAddress: String?,
        timestamp: Long
    ) {
        applicationScope.launch {
            try {
                // Check if background downloading is enabled
                if (!socialMediaDownloadService.isBackgroundDownloadEnabled()) {
                    return@launch
                }

                // Check network conditions
                val permission = socialMediaDownloadService.canDownload()
                if (permission is com.bothbubbles.services.socialmedia.DownloadPermission.Blocked) {
                    Timber.d("[SocialMedia] Background download blocked: ${permission.reason}")
                    return@launch
                }

                // Extract URLs from the message text
                val urlPattern = Regex("""https?://[^\s<>"]+""")
                val urls = urlPattern.findAll(text).map { it.value }.toList()

                for (url in urls) {
                    // Check if this URL is a supported social media platform
                    val platform = socialMediaDownloadService.detectPlatform(url) ?: continue

                    // Check if link was dismissed by user
                    if (socialMediaDownloadService.isLinkDismissed(messageGuid, url)) {
                        continue
                    }

                    // Check if downloading is enabled for this platform
                    if (!socialMediaDownloadService.isDownloadEnabled(platform)) {
                        continue
                    }

                    Timber.d("[SocialMedia] Starting background download for $platform: $url")

                    // Extract video URL
                    val result = socialMediaDownloadService.extractVideoUrl(url, platform)
                    if (result is com.bothbubbles.services.socialmedia.SocialMediaResult.Success) {
                        // Download and cache the video
                        socialMediaDownloadService.downloadAndCacheVideo(
                            result = result,
                            originalUrl = url,
                            messageGuid = messageGuid,
                            chatGuid = chatGuid,
                            platform = platform,
                            senderName = senderName,
                            senderAddress = senderAddress,
                            sentTimestamp = timestamp
                        )
                        Timber.d("[SocialMedia] Background download initiated for $platform: $url")
                    } else {
                        Timber.d("[SocialMedia] Failed to extract video URL: $result")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "[SocialMedia] Error during background download")
            }
        }
    }

    /**
     * Resolve or create the unified chat ID for a chat.
     *
     * Strategy for 1:1 chats:
     * 1. Check if chat already has a unified_chat_id
     * 2. Try to find existing unified chat by normalized address (enables iMessage/SMS merging)
     * 3. Create new unified chat if none exists
     *
     * Strategy for group chats:
     * 1. Check if chat already has a unified_chat_id
     * 2. Create new unified chat using chat GUID as identifier (groups don't merge)
     */
    private suspend fun resolveUnifiedChatId(chatGuid: String, messageDto: MessageDto): String? {
        // Try to get chat with its unified_chat_id
        val chat = chatDao.getChatByGuid(chatGuid)

        // If chat already has a unified_chat_id, use it
        chat?.unifiedChatId?.let { return it }

        // For group chats, use the chat GUID as the normalized address
        // Group chats don't merge (each group is unique), but they still need unified IDs
        // for consistent identification in contacts sync and deep links
        val isGroup = chat?.isGroup == true
        val normalizedAddress = if (isGroup) {
            // Use chat GUID as identifier for groups (they're already unique)
            chatGuid
        } else {
            // For 1:1 chats, use the normalized phone/email for potential iMessage/SMS merging
            resolveNormalizedAddress(chat?.chatIdentifier, messageDto)
                ?: return null // Can't create unified chat without an address
        }

        // Get or create unified chat for this address
        val unifiedChat = unifiedChatDao.getOrCreate(
            UnifiedChatEntity(
                id = UnifiedChatIdGenerator.generate(),
                normalizedAddress = normalizedAddress,
                sourceId = chatGuid // The first chat becomes the source
            )
        )

        // For 1:1 chats: if existing unified chat has SMS source but this chat is iMessage, prefer iMessage
        // This ensures users navigate to iMessage when available for better experience
        if (!isGroup) {
            val currentSourceId = unifiedChat.sourceId
            val isCurrentSourceSms = currentSourceId.startsWith("sms;", ignoreCase = true) ||
                                      currentSourceId.startsWith("mms;", ignoreCase = true)
            val isNewChatIMessage = !chatGuid.startsWith("sms;", ignoreCase = true) &&
                                     !chatGuid.startsWith("mms;", ignoreCase = true)

            if (isCurrentSourceSms && isNewChatIMessage) {
                unifiedChatDao.updateSourceId(unifiedChat.id, chatGuid)
                Timber.i("Upgraded unified chat ${unifiedChat.id} source from SMS to iMessage: $chatGuid")
            }
        }

        // Link the chat to this unified chat
        chatDao.setUnifiedChatId(chatGuid, unifiedChat.id)

        return unifiedChat.id
    }

    /**
     * Normalize an address for unified chat lookup.
     * Phone numbers are stripped to digits only, emails are lowercased.
     */
    private fun resolveNormalizedAddress(chatIdentifier: String?, messageDto: MessageDto): String? {
        // Prefer chat identifier
        val address = chatIdentifier
            ?: messageDto.handle?.address
            ?: return null

        return normalizeAddress(address)
    }

    private fun normalizeAddress(address: String): String {
        return if (address.contains("@")) {
            address.lowercase()
        } else {
            address.replace(Regex("[^0-9+]"), "")
        }
    }

    /**
     * Handle message update (read receipt, delivery, edit, etc.)
     *
     * When a message is edited (new dateEdited > existing dateEdited), the previous
     * text is saved to the edit history table before updating the message.
     */
    override suspend fun handleMessageUpdate(messageDto: MessageDto, chatGuid: String) {
        val existingMessage = messageDao.getMessageByGuid(messageDto.guid)
        if (existingMessage != null) {
            val localHandleId = resolveLocalHandleId(messageDto)
            // Preserve the existing unified_chat_id
            val updated = messageDto.toEntity(chatGuid, localHandleId, existingMessage.unifiedChatId)
                .copy(id = existingMessage.id)

            // Check if this is a message edit (new dateEdited that differs from existing)
            val incomingDateEdited = messageDto.dateEdited
            val existingDateEdited = existingMessage.dateEdited

            if (incomingDateEdited != null &&
                (existingDateEdited == null || incomingDateEdited > existingDateEdited)) {
                // This is a new edit - save the previous text to history
                Timber.d("Message edited: ${messageDto.guid}, saving previous text to history")
                messageEditHistoryDao.insert(
                    MessageEditHistoryEntity(
                        messageGuid = messageDto.guid,
                        previousText = existingMessage.text,
                        editedAt = incomingDateEdited
                    )
                )
            }

            messageDao.updateMessage(updated)
        }
    }

    /**
     * Sync attachments for an incoming message to local database.
     * Incoming attachments are marked as PENDING for auto-download.
     * Blurhash is fetched asynchronously to not block message processing.
     */
    override suspend fun syncIncomingAttachments(messageDto: MessageDto, tempMessageGuid: String?) {
        // Delete any temp attachments that were created for immediate display
        tempMessageGuid?.let { tempGuid ->
            attachmentDao.deleteAttachmentsForMessage(tempGuid)
        }

        val attachments = messageDto.attachments
        if (attachments.isNullOrEmpty()) return

        val serverAddress = settingsDataStore.serverAddress.first()
        val attachmentGuidsForBlurhash = mutableListOf<String>()

        attachments.forEach { attachmentDto ->
            // webUrl is base download URL - AuthInterceptor adds guid param, AttachmentRepository adds original=true for stickers
            val webUrl = "$serverAddress/api/v1/attachment/${attachmentDto.guid}/download"

            // Check if this attachment already exists (e.g., from IMessageSenderStrategy)
            val existingAttachment = attachmentDao.getAttachmentByGuid(attachmentDto.guid)

            Timber.d("[AttachmentSync] syncIncomingAttachments: guid=${attachmentDto.guid}, " +
                "existing=${existingAttachment != null}, existingMessageGuid=${existingAttachment?.messageGuid}, " +
                "incomingMessageGuid=${messageDto.guid}, existingLocalPath=${existingAttachment?.localPath}")

            if (existingAttachment != null) {
                // Attachment already exists - check if it's for the same message (duplicate) or different (self-message)
                if (existingAttachment.messageGuid == messageDto.guid) {
                    // Same message - true duplicate, skip
                    Timber.d("[AttachmentSync] syncIncomingAttachments: duplicate for same message, skipping")
                    return@forEach
                }

                // Different message (self-message case) - create a copy for this message
                // Use a modified GUID since attachment.guid must be unique
                Timber.d("[AttachmentSync] syncIncomingAttachments: self-message detected, creating copy for inbound")
                val inboundGuid = "${attachmentDto.guid}-inbound"

                // Check if we already created the inbound copy
                val existingInboundCopy = attachmentDao.getAttachmentByGuid(inboundGuid)
                if (existingInboundCopy != null) {
                    Timber.d("[AttachmentSync] syncIncomingAttachments: inbound copy already exists, skipping")
                    return@forEach
                }

                val inboundAttachment = AttachmentEntity(
                    guid = inboundGuid,
                    messageGuid = messageDto.guid,
                    originalRowId = attachmentDto.originalRowId,
                    uti = attachmentDto.uti,
                    mimeType = attachmentDto.mimeType,
                    transferName = attachmentDto.transferName,
                    totalBytes = attachmentDto.totalBytes,
                    isOutgoing = false,  // This is the received copy
                    hideAttachment = attachmentDto.hideAttachment,
                    width = attachmentDto.width,
                    height = attachmentDto.height,
                    hasLivePhoto = attachmentDto.hasLivePhoto,
                    isSticker = attachmentDto.isSticker,
                    webUrl = webUrl,
                    localPath = existingAttachment.localPath,  // Reuse existing local file!
                    transferState = if (existingAttachment.localPath != null) TransferState.DOWNLOADED.name else TransferState.PENDING.name,
                    transferProgress = if (existingAttachment.localPath != null) 1f else 0f
                )
                attachmentDao.insertAttachment(inboundAttachment)
                return@forEach
            }

            // Determine transfer state based on direction:
            // - Outbound (isOutgoing=true): Already uploaded, mark as UPLOADED
            // - Inbound: Needs download, mark as PENDING for auto-download
            val transferState = when {
                attachmentDto.isOutgoing -> TransferState.UPLOADED.name
                else -> TransferState.PENDING.name
            }

            val attachment = AttachmentEntity(
                guid = attachmentDto.guid,
                messageGuid = messageDto.guid,
                originalRowId = attachmentDto.originalRowId,
                uti = attachmentDto.uti,
                mimeType = attachmentDto.mimeType,
                transferName = attachmentDto.transferName,
                totalBytes = attachmentDto.totalBytes,
                isOutgoing = attachmentDto.isOutgoing,
                hideAttachment = attachmentDto.hideAttachment,
                width = attachmentDto.width,
                height = attachmentDto.height,
                hasLivePhoto = attachmentDto.hasLivePhoto,
                isSticker = attachmentDto.isSticker,
                webUrl = webUrl,
                localPath = null,  // New inbound attachment - will be downloaded
                transferState = transferState,
                transferProgress = if (attachmentDto.isOutgoing) 1f else 0f
            )
            attachmentDao.insertAttachment(attachment)

            // Collect image/video attachments for blurhash fetching
            val mimeType = attachmentDto.mimeType ?: ""
            if (!attachmentDto.isOutgoing && (mimeType.startsWith("image/") || mimeType.startsWith("video/"))) {
                attachmentGuidsForBlurhash.add(attachmentDto.guid)
            }
        }

        // Fetch blurhash asynchronously for inbound media attachments
        // This doesn't block message processing but provides colorful placeholders
        if (attachmentGuidsForBlurhash.isNotEmpty()) {
            applicationScope.launch {
                fetchBlurhashesForAttachments(attachmentGuidsForBlurhash)
            }
        }
    }

    /**
     * Fetch blurhash from server for the given attachments and update the database.
     * Called asynchronously to not block message processing.
     */
    private suspend fun fetchBlurhashesForAttachments(guids: List<String>) {
        for (guid in guids) {
            try {
                val response = api.getAttachmentBlurhash(guid)
                if (response.isSuccessful) {
                    val blurhash = response.body()?.data
                    if (!blurhash.isNullOrBlank()) {
                        attachmentDao.updateBlurhash(guid, blurhash)
                        Timber.d("Updated blurhash for attachment $guid")
                    }
                }
            } catch (e: Exception) {
                // Blurhash is optional - don't fail on errors
                Timber.w(e, "Failed to fetch blurhash for attachment $guid")
            }
        }
    }

    /**
     * Resolve the local handle ID from server data.
     *
     * The server sends its internal handle row ID (handleId), but our local database
     * may have a different ID for the same handle. We need to map server ID -> local ID.
     *
     * Strategy:
     * 1. If embedded handle has address+service, look up by those (most reliable)
     * 2. Otherwise, look up by server's original_row_id
     * 3. Fall back to null if no match found
     */
    private suspend fun resolveLocalHandleId(messageDto: MessageDto): Long? {
        // First try: embedded handle with address and service (most reliable)
        messageDto.handle?.let { handleDto ->
            val localHandle = handleDao.getHandleByAddressAndService(
                handleDto.address,
                handleDto.service
            )
            if (localHandle != null) {
                return localHandle.id
            }
            // Try any service if exact match not found
            val anyHandle = handleDao.getHandleByAddressAny(handleDto.address)
            if (anyHandle != null) {
                return anyHandle.id
            }
        }

        // Second try: look up by server's original_row_id
        messageDto.handleId?.let { serverHandleId ->
            val localHandle = handleDao.getHandleByOriginalRowId(serverHandleId.toInt())
            if (localHandle != null) {
                return localHandle.id
            }
        }

        // No match found - this is okay for sent messages (isFromMe=true)
        return null
    }

    /**
     * Convert a MessageDto to a MessageEntity
     */
    private fun MessageDto.toEntity(chatGuid: String, localHandleId: Long?, unifiedChatId: String?): MessageEntity {
        // Determine message source based on handle's service or chat GUID prefix
        val source = when {
            handle?.service?.equals("SMS", ignoreCase = true) == true -> MessageSource.SERVER_SMS.name
            handle?.service?.equals("RCS", ignoreCase = true) == true -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("sms;-;", ignoreCase = true) -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("SMS;-;") -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("RCS;-;", ignoreCase = true) -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("mms;-;", ignoreCase = true) -> MessageSource.SERVER_SMS.name
            else -> MessageSource.IMESSAGE.name
        }

        return MessageEntity(
            guid = guid,
            chatGuid = chatGuid,
            unifiedChatId = unifiedChatId,
            handleId = localHandleId,
            senderAddress = handle?.address,
            text = HtmlEntityDecoder.decode(text),
            subject = HtmlEntityDecoder.decode(subject),
            dateCreated = dateCreated ?: System.currentTimeMillis(),
            dateRead = dateRead,
            dateDelivered = dateDelivered,
            dateEdited = dateEdited,
            datePlayed = datePlayed,
            isFromMe = isFromMe,
            error = error,
            itemType = itemType,
            groupTitle = groupTitle,
            groupActionType = groupActionType,
            balloonBundleId = balloonBundleId,
            associatedMessageGuid = associatedMessageGuid,
            associatedMessagePart = associatedMessagePart,
            associatedMessageType = associatedMessageType,
            expressiveSendStyleId = expressiveSendStyleId,
            threadOriginatorGuid = threadOriginatorGuid,
            threadOriginatorPart = threadOriginatorPart,
            hasAttachments = attachments?.isNotEmpty() == true,
            hasReactions = hasReactions,
            bigEmoji = bigEmoji,
            wasDeliveredQuietly = wasDeliveredQuietly,
            didNotifyRecipient = didNotifyRecipient,
            messageSource = source,
            // Compute is_reaction using centralized logic for efficient SQL queries
            isReactionDb = ReactionClassifier.isReaction(associatedMessageGuid, associatedMessageType)
        )
    }
}
