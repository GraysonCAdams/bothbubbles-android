package com.bothbubbles.services.notifications

import android.net.Uri
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.data.local.db.entity.rawDisplayName
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.LinkPreviewRepository
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.contacts.DisplayNameResolver
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.util.parsing.UrlParsingUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolved sender information including avatar and contact status.
 */
data class SenderInfo(
    val name: String?,
    val avatarUri: String?,
    val hasContactInfo: Boolean
)

/**
 * Central builder for notification parameters.
 *
 * This service provides a single source of truth for building [MessageNotificationParams]
 * across all notification entry points (Socket, FCM, MediaUpdater, LinkPreviewUpdater).
 *
 * Key responsibilities:
 * - Cached contact lookups (5-minute TTL, prevents duplicate Android Contacts queries)
 * - Consistent hasContactInfo resolution (prevents incorrect business icon display)
 * - Chat/participant data fetching
 * - Optional link preview fetching
 */
@Singleton
class NotificationParamsBuilder @Inject constructor(
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val messageDao: MessageDao,
    private val unifiedChatDao: UnifiedChatDao,
    private val chatRepository: ChatRepository,
    private val linkPreviewRepository: LinkPreviewRepository,
    private val androidContactsService: AndroidContactsService,
    private val displayNameResolver: DisplayNameResolver,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "NotificationParamsBuilder"
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
        val hasContactInfo: Boolean,
        val timestamp: Long
    )

    // In-memory contact lookup cache with mutex for thread-safe access
    private val contactCacheMutex = Mutex()
    private val contactCache = mutableMapOf<String, CachedContactInfo>()

    // Track lookups in progress to prevent duplicate expensive queries
    private val lookupInProgress = mutableSetOf<String>()

    /**
     * Build notification params for a message.
     *
     * This is the single source of truth for all notification paths.
     * All callers should use this method to ensure consistent behavior.
     *
     * @param messageGuid The message GUID (used for notification ID and lookup)
     * @param chatGuid The chat GUID
     * @param messageText Override message text (if already computed with effects/attachments)
     * @param subject Override subject (if already extracted)
     * @param senderAddress Override sender address (if already known from DTO)
     * @param attachmentUri Optional attachment URI for inline media preview
     * @param attachmentMimeType MIME type of attachment (required if attachmentUri provided)
     * @param linkPreviewTitle Override link preview title (if already fetched)
     * @param linkPreviewDomain Override link preview domain (if already fetched)
     * @param fetchLinkPreview Whether to fetch link preview if not provided (default true)
     * @param isInvisibleInk Whether message has invisible ink effect (skip link preview)
     * @return MessageNotificationParams or null if chat/message not found
     */
    suspend fun buildParams(
        messageGuid: String,
        chatGuid: String,
        messageText: String? = null,
        subject: String? = null,
        senderAddress: String? = null,
        attachmentUri: Uri? = null,
        attachmentMimeType: String? = null,
        linkPreviewTitle: String? = null,
        linkPreviewDomain: String? = null,
        fetchLinkPreview: Boolean = true,
        isInvisibleInk: Boolean = false
    ): MessageNotificationParams? {
        // Get chat info
        val chat = chatDao.getChatByGuid(chatGuid)
        if (chat == null) {
            Timber.w("$TAG: Chat not found: $chatGuid")
            return null
        }

        // Get unified chat for avatar and notification settings
        val unifiedChat = chat.unifiedChatId?.let { unifiedChatDao.getById(it) }

        // Get message if we need data from it
        val message = if (messageText == null || senderAddress == null) {
            messageDao.getMessageByGuid(messageGuid)
        } else null

        // Resolve sender address
        val resolvedSenderAddress = senderAddress
            ?: message?.senderAddress
            ?: ""

        // Resolve sender info with caching
        val senderInfo = resolveSenderInfo(resolvedSenderAddress)

        // Get participants for chat title and group avatar
        val participants = chatRepository.getParticipantsForChat(chatGuid)
        val participantNames = participants.map { it.rawDisplayName }
        val participantAvatarPaths = participants.map { it.cachedAvatarPath }
        val participantHasContactInfo = participants.map { it.cachedDisplayName != null }

        // Resolve chat title
        val chatTitle = chatRepository.resolveChatTitle(chat, participants)

        // For group chats, extract first name for cleaner notification display
        val displaySenderName = if (chat.isGroup && senderInfo.name != null) {
            displayNameResolver.extractFirstName(senderInfo.name)
        } else {
            senderInfo.name
        }

        // Resolve message text
        val resolvedMessageText = messageText
            ?: message?.text?.takeIf { it.isNotBlank() }
            ?: ""

        // Resolve subject
        val resolvedSubject = subject ?: message?.subject

        // Fetch link preview if needed and not already provided
        val (finalLinkTitle, finalLinkDomain) = if (linkPreviewTitle != null || linkPreviewDomain != null) {
            // Already have preview data
            linkPreviewTitle to linkPreviewDomain
        } else if (fetchLinkPreview && !isInvisibleInk && resolvedMessageText.contains("http")) {
            // Try to fetch link preview
            fetchLinkPreviewData(resolvedMessageText, messageGuid, chatGuid)
        } else {
            null to null
        }

        return MessageNotificationParams(
            chatGuid = chatGuid,
            chatTitle = chatTitle,
            messageText = resolvedMessageText,
            messageGuid = messageGuid,
            senderName = displaySenderName,
            senderAddress = resolvedSenderAddress,
            isGroup = chat.isGroup,
            avatarUri = senderInfo.avatarUri,
            senderHasContactInfo = senderInfo.hasContactInfo,
            linkPreviewTitle = finalLinkTitle,
            linkPreviewDomain = finalLinkDomain,
            participantNames = participantNames,
            participantAvatarPaths = participantAvatarPaths,
            participantHasContactInfo = participantHasContactInfo,
            // Priority: UnifiedChatEntity avatar > ChatEntity serverGroupPhotoPath (fallback for group chats)
            groupAvatarPath = unifiedChat?.effectiveAvatarPath ?: chat.serverGroupPhotoPath,
            subject = resolvedSubject,
            attachmentUri = attachmentUri,
            attachmentMimeType = attachmentMimeType
        )
    }

    /**
     * Resolve sender info from address with caching.
     *
     * This method is public so callers can use it independently when they need
     * sender info but don't need full notification params.
     *
     * Priority:
     * 1. Cached contact lookup (if within TTL)
     * 2. Live Android Contacts lookup (with deduplication)
     * 3. Cached contact name from HandleEntity
     * 4. Inferred name from HandleEntity
     * 5. Formatted address as fallback
     */
    suspend fun resolveSenderInfo(address: String): SenderInfo {
        if (address.isBlank()) {
            return SenderInfo(name = null, avatarUri = null, hasContactInfo = false)
        }

        val now = System.currentTimeMillis()

        // Check in-memory cache first and register lookup intent (mutex-protected)
        contactCacheMutex.withLock {
            // Check cache
            contactCache[address]?.let { cached ->
                if (now - cached.timestamp < CONTACT_CACHE_TTL_MS) {
                    return SenderInfo(
                        name = cached.displayName,
                        avatarUri = cached.avatarUri,
                        hasContactInfo = cached.hasContactInfo
                    )
                }
                contactCache.remove(address)
            }

            // Check if another thread is already looking up this address
            if (address in lookupInProgress) {
                // Another thread is querying - check handle cache as fallback
                val localHandle = handleDao.getHandlesByAddress(address).firstOrNull()
                return if (localHandle?.cachedDisplayName != null) {
                    SenderInfo(
                        name = localHandle.cachedDisplayName,
                        avatarUri = localHandle.cachedAvatarPath,
                        hasContactInfo = true
                    )
                } else {
                    SenderInfo(
                        name = localHandle?.displayName ?: PhoneNumberFormatter.format(address),
                        avatarUri = localHandle?.cachedAvatarPath,
                        hasContactInfo = false
                    )
                }
            }

            // Mark lookup as in progress
            lookupInProgress.add(address)
        }

        // Perform Android Contacts lookup (expensive I/O operation)
        val contactName: String?
        val photoUri: String?
        try {
            contactName = androidContactsService.getContactDisplayName(address)
            photoUri = if (contactName != null) {
                androidContactsService.getContactPhotoUri(address)
            } else {
                null
            }

            // Update handle cache if we found contact info
            if (contactName != null) {
                val localHandle = handleDao.getHandlesByAddress(address).firstOrNull()
                localHandle?.let { handle ->
                    handleDao.updateCachedContactInfo(handle.id, contactName, photoUri)
                }
            }
        } finally {
            // Always clean up lookup-in-progress flag
            contactCacheMutex.withLock {
                lookupInProgress.remove(address)
            }
        }

        // Store result in cache and return
        return if (contactName != null) {
            contactCacheMutex.withLock {
                contactCache[address] = CachedContactInfo(
                    displayName = contactName,
                    avatarUri = photoUri,
                    hasContactInfo = true,
                    timestamp = now
                )
            }
            SenderInfo(name = contactName, avatarUri = photoUri, hasContactInfo = true)
        } else {
            // Check handle entity for cached/inferred name
            val localHandle = handleDao.getHandlesByAddress(address).firstOrNull()

            val (name, hasContactInfo) = if (localHandle?.cachedDisplayName != null) {
                localHandle.cachedDisplayName to true
            } else if (localHandle?.inferredName != null) {
                localHandle.displayName to false // Inferred names are not "contact info"
            } else {
                PhoneNumberFormatter.format(address) to false
            }

            val avatarUri = localHandle?.cachedAvatarPath

            contactCacheMutex.withLock {
                contactCache[address] = CachedContactInfo(
                    displayName = name,
                    avatarUri = avatarUri,
                    hasContactInfo = hasContactInfo,
                    timestamp = now
                )
            }
            SenderInfo(name = name, avatarUri = avatarUri, hasContactInfo = hasContactInfo)
        }
    }

    /**
     * Fetch link preview data for a message containing a URL.
     * Returns (title, domain) pair or (null, null) if no preview available.
     */
    private suspend fun fetchLinkPreviewData(
        messageText: String,
        messageGuid: String,
        chatGuid: String
    ): Pair<String?, String?> {
        val detectedUrl = UrlParsingUtils.getFirstUrl(messageText) ?: return null to null

        val preview = linkPreviewRepository.getLinkPreviewForNotification(
            url = detectedUrl.url,
            messageGuid = messageGuid,
            chatGuid = chatGuid
        )

        val title = preview?.title?.takeIf { it.isNotBlank() }
        val domain = preview?.domain ?: detectedUrl.domain

        return title to domain
    }

    /**
     * Clear the contact cache.
     * Useful for testing or when contacts have been updated.
     */
    suspend fun clearCache() {
        contactCacheMutex.withLock {
            contactCache.clear()
        }
    }
}
