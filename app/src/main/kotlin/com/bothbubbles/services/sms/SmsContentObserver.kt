package com.bothbubbles.services.sms

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import timber.log.Timber
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.core.model.entity.UnifiedChatEntity
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.sound.SoundManager
import com.bothbubbles.util.UnifiedChatIdGenerator
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Content observer for monitoring SMS/MMS database changes.
 * Used to detect:
 * - MMS messages (which can't be received via broadcast)
 * - Messages sent from other apps
 * - Message status changes
 */
@Singleton
class SmsContentObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smsContentProvider: SmsContentProvider,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val unifiedChatDao: UnifiedChatDao,
    private val notificationService: NotificationService,
    private val activeConversationManager: ActiveConversationManager,
    private val androidContactsService: AndroidContactsService,
    private val soundManager: SoundManager,
    private val smsPermissionHelper: SmsPermissionHelper,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "SmsContentObserver"
        private const val DEBOUNCE_MS = 500L
    }

    private var smsObserver: ContentObserver? = null
    private var mmsObserver: ContentObserver? = null
    private var handler: Handler? = null

    private val _isObserving = MutableStateFlow(false)
    val isObserving: StateFlow<Boolean> = _isObserving.asStateFlow()

    private var lastSmsId: Long = 0
    private var lastMmsId: Long = 0
    private var debounceJob: Job? = null

    /**
     * Start observing SMS/MMS content changes
     */
    fun startObserving() {
        if (_isObserving.value) return

        Timber.d("Starting SMS/MMS content observers")

        // Initialize last IDs
        applicationScope.launch(ioDispatcher) {
            lastSmsId = getLatestSmsId()
            lastMmsId = getLatestMmsId()
        }

        handler = handler ?: Handler(Looper.getMainLooper())

        // SMS observer
        smsObserver = object : ContentObserver(handler!!) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Timber.d("SMS content changed: $uri")
                debounceAndProcess { processSmsChanges() }
            }
        }

        // MMS observer
        mmsObserver = object : ContentObserver(handler!!) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Timber.d("MMS content changed: $uri")
                debounceAndProcess { processMmsChanges() }
            }
        }

        smsObserver?.let { observer ->
            context.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                observer
            )
        }

        mmsObserver?.let { observer ->
            context.contentResolver.registerContentObserver(
                Telephony.Mms.CONTENT_URI,
                true,
                observer
            )
        }

        _isObserving.value = true
    }

    /**
     * Stop observing SMS/MMS content changes
     */
    fun stopObserving() {
        Timber.d("Stopping SMS/MMS content observers")

        handler?.removeCallbacksAndMessages(null)
        smsObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        mmsObserver?.let { context.contentResolver.unregisterContentObserver(it) }

        smsObserver = null
        mmsObserver = null
        handler = null
        debounceJob?.cancel()

        _isObserving.value = false
    }

    private fun debounceAndProcess(action: suspend () -> Unit) {
        debounceJob?.cancel()
        debounceJob = applicationScope.launch(ioDispatcher) {
            delay(DEBOUNCE_MS)
            action()
        }
    }

    private suspend fun processSmsChanges() {
        try {
            val latestId = getLatestSmsId()
            if (latestId <= lastSmsId) return

            Timber.d("Processing new SMS messages (last: $lastSmsId, current: $latestId)")

            // Query for new messages
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                null,
                "${Telephony.Sms._ID} > ?",
                arrayOf(lastSmsId.toString()),
                "${Telephony.Sms._ID} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID))
                    val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
                    val address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: continue
                    val body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY))
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE))
                    val type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))

                    // Check if we already have this message
                    val existingGuid = "sms-$id"
                    if (messageDao.getMessageByGuid(existingGuid) != null) continue

                    // Determine if this is an incoming or outgoing message
                    val isFromMe = type == Telephony.Sms.MESSAGE_TYPE_SENT ||
                            type == Telephony.Sms.MESSAGE_TYPE_OUTBOX ||
                            type == Telephony.Sms.MESSAGE_TYPE_QUEUED

                    // Normalize address to prevent duplicate conversations
                    val normalizedAddress = PhoneAndCodeParsingUtils.normalizePhoneNumber(address)
                    // Get or create chat and unified chat
                    val chatGuid = "sms;-;$normalizedAddress"
                    val unifiedChat = ensureChatExists(chatGuid, normalizedAddress, date, body)

                    // Create message entity with unified_chat_id
                    val message = com.bothbubbles.data.local.db.entity.MessageEntity(
                        guid = existingGuid,
                        chatGuid = chatGuid,
                        unifiedChatId = unifiedChat?.id,
                        text = body,
                        dateCreated = date,
                        isFromMe = isFromMe,
                        messageSource = MessageSource.LOCAL_SMS.name,
                        smsId = id,
                        smsThreadId = threadId
                    )
                    messageDao.insertMessage(message)

                    // Show notification for incoming messages
                    if (!isFromMe) {
                        // Check if notifications are disabled for this chat (from unified chat)
                        if (unifiedChat?.notificationsEnabled == false) {
                            Timber.i("Notifications disabled for chat $chatGuid, skipping SMS notification")
                        } else if (unifiedChat?.isSnoozed == true) {
                            // Check if chat is snoozed
                            Timber.i("Chat $chatGuid is snoozed, skipping SMS notification")
                        } else if (activeConversationManager.isConversationActive(chatGuid)) {
                            // Check if user is currently viewing this conversation
                            // Only play sound if we're NOT the default SMS app (when default, SmsBroadcastReceiver handles it)
                            if (!smsPermissionHelper.isDefaultSmsApp()) {
                                Timber.i("Chat $chatGuid is currently active, playing in-app sound and skipping SMS notification")
                                soundManager.playReceiveSound(chatGuid)
                            } else {
                                Timber.i("Chat $chatGuid is currently active, skipping SMS notification (broadcast receiver handled sound)")
                            }
                        } else {
                            // Resolve sender name and avatar from contacts
                            val senderName = androidContactsService.getContactDisplayName(address)
                            val senderAvatarUri = androidContactsService.getContactPhotoUri(address)

                            notificationService.showMessageNotification(
                                com.bothbubbles.services.notifications.MessageNotificationParams(
                                    chatGuid = chatGuid,
                                    chatTitle = unifiedChat?.displayName ?: senderName ?: address,
                                    messageText = body ?: "",
                                    messageGuid = existingGuid,
                                    senderName = senderName,
                                    senderAddress = normalizedAddress,
                                    isGroup = false,
                                    avatarUri = senderAvatarUri,
                                    groupAvatarPath = unifiedChat?.effectiveAvatarPath
                                )
                            )
                        }
                    }

                    Timber.d("Imported SMS $id from content observer")
                }
            }

            lastSmsId = latestId
        } catch (e: Exception) {
            Timber.e(e, "Error processing SMS changes")
        }
    }

    private suspend fun processMmsChanges() {
        try {
            val latestId = getLatestMmsId()
            if (latestId <= lastMmsId) return

            Timber.d("Processing new MMS messages (last: $lastMmsId, current: $latestId)")

            // Query for new MMS messages
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                null,
                "${Telephony.Mms._ID} > ?",
                arrayOf(lastMmsId.toString()),
                "${Telephony.Mms._ID} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms._ID))
                    val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID))
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)) * 1000 // MMS uses seconds
                    val messageBox = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX))

                    // Check if we already have this message
                    val existingGuid = "mms-$id"
                    if (messageDao.getMessageByGuid(existingGuid) != null) continue

                    val isFromMe = messageBox == Telephony.Mms.MESSAGE_BOX_SENT ||
                            messageBox == Telephony.Mms.MESSAGE_BOX_OUTBOX

                    // Get MMS details using content provider
                    val mmsMessages = smsContentProvider.getMmsMessages(threadId, limit = 1)
                    val mmsMessage = mmsMessages.find { it.id == id } ?: continue

                    // Filter addresses to only valid phone numbers (exclude RCS, email, etc.)
                    val validAddresses = mmsMessage.addresses.filter { isValidPhoneAddress(it.address) }
                    if (validAddresses.isEmpty()) {
                        Timber.d("Skipping MMS $id - no valid phone addresses (RCS or email-only message)")
                        continue
                    }

                    // Get primary address (for chat creation)
                    val rawPrimaryAddress = if (isFromMe) {
                        validAddresses.find { it.type == 151 }?.address // TO
                            ?: validAddresses.find { it.type == 130 }?.address // BCC (other recipient)
                    } else {
                        validAddresses.find { it.type == 137 }?.address // FROM
                    }

                    if (rawPrimaryAddress == null) {
                        Timber.d("Skipping MMS $id - no valid primary address found")
                        continue
                    }

                    // Normalize to prevent duplicate conversations
                    val primaryAddress = PhoneAndCodeParsingUtils.normalizePhoneNumber(rawPrimaryAddress)

                    // Determine if this is a group chat by counting unique phone numbers
                    // A 1:1 MMS has 2 unique numbers (sender + recipient)
                    // A group MMS has 3+ unique numbers
                    val uniquePhoneNumbers = validAddresses
                        .map { PhoneAndCodeParsingUtils.normalizePhoneNumber(it.address) }
                        .distinct()
                    val isGroup = uniquePhoneNumbers.size > 2

                    val chatGuid = if (isGroup) {
                        "mms;-;${uniquePhoneNumbers.sorted().joinToString(",")}"
                    } else {
                        "sms;-;$primaryAddress"
                    }

                    // Get text content
                    val textContent = mmsMessage.textParts.joinToString("\n").takeIf { it.isNotBlank() }

                    // Check for duplicate SMS message with matching content and timestamp
                    // This prevents the same message from appearing twice when recorded as both SMS and MMS
                    if (textContent != null) {
                        val matchingMessage = messageDao.findMatchingMessage(
                            chatGuid = chatGuid,
                            text = textContent,
                            isFromMe = isFromMe,
                            dateCreated = date,
                            toleranceMs = 10000 // 10 second window for MMS which can have delayed timestamps
                        )
                        if (matchingMessage != null) {
                            Timber.d("Skipping duplicate MMS $id - matches existing message ${matchingMessage.guid}")
                            continue
                        }
                    }

                    // Ensure chat exists and get unified chat
                    val unifiedChat = ensureChatExists(chatGuid, primaryAddress, date, textContent, isGroup)

                    // Create message entity with unified_chat_id
                    val message = mmsMessage.toMessageEntity(chatGuid, unifiedChat?.id)
                    messageDao.insertMessage(message)

                    // Show notification for incoming messages
                    if (!isFromMe) {
                        // Check if notifications are disabled for this chat (from unified chat)
                        if (unifiedChat?.notificationsEnabled == false) {
                            Timber.i("Notifications disabled for chat $chatGuid, skipping MMS notification")
                        } else if (unifiedChat?.isSnoozed == true) {
                            // Check if chat is snoozed
                            Timber.i("Chat $chatGuid is snoozed, skipping MMS notification")
                        } else if (activeConversationManager.isConversationActive(chatGuid)) {
                            // Check if user is currently viewing this conversation
                            // Only play sound if we're NOT the default SMS app (when default, MmsBroadcastReceiver handles it)
                            if (!smsPermissionHelper.isDefaultSmsApp()) {
                                Timber.i("Chat $chatGuid is currently active, playing in-app sound and skipping MMS notification")
                                soundManager.playReceiveSound(chatGuid)
                            } else {
                                Timber.i("Chat $chatGuid is currently active, skipping MMS notification (broadcast receiver handled sound)")
                            }
                        } else {
                            // Resolve sender name and avatar from contacts
                            val senderName = androidContactsService.getContactDisplayName(primaryAddress)
                            val senderAvatarUri = androidContactsService.getContactPhotoUri(primaryAddress)

                            // Get first image/video attachment for inline preview
                            val firstMediaAttachment = mmsMessage.imageParts.firstOrNull { part ->
                                val mimeType = part.contentType.lowercase()
                                mimeType.startsWith("image/") || mimeType.startsWith("video/")
                            }

                            notificationService.showMessageNotification(
                                com.bothbubbles.services.notifications.MessageNotificationParams(
                                    chatGuid = chatGuid,
                                    chatTitle = unifiedChat?.displayName ?: senderName ?: primaryAddress,
                                    messageText = textContent ?: "[MMS]",
                                    messageGuid = existingGuid,
                                    senderName = if (isGroup) (senderName ?: primaryAddress) else senderName,
                                    senderAddress = primaryAddress,
                                    isGroup = isGroup,
                                    avatarUri = senderAvatarUri,
                                    groupAvatarPath = unifiedChat?.effectiveAvatarPath,
                                    attachmentUri = firstMediaAttachment?.dataUri,
                                    attachmentMimeType = firstMediaAttachment?.contentType
                                )
                            )
                        }
                    }

                    Timber.d("Imported MMS $id from content observer")
                }
            }

            lastMmsId = latestId
        } catch (e: Exception) {
            Timber.e(e, "Error processing MMS changes")
        }
    }

    /**
     * Check if an address is a valid phone number (not RCS, email, or other non-phone format)
     */
    private fun isValidPhoneAddress(address: String): Boolean {
        if (address.isBlank()) return false
        // Filter out RCS addresses
        if (address.contains("@")) return false
        if (address.contains("rcs.google.com")) return false
        if (address.contains("rbm.goog")) return false
        // Filter out "insert-address-token" placeholder
        if (address.contains("insert-address-token")) return false
        // Should have at least some digits to be a phone number
        if (address.count { it.isDigit() } < 3) return false
        return true
    }

    private suspend fun ensureChatExists(
        chatGuid: String,
        address: String,
        date: Long,
        lastMessage: String?,
        isGroup: Boolean = false
    ): UnifiedChatEntity? {
        val normalizedAddress = address.replace(Regex("[^0-9+]"), "")

        // Get or create unified chat for this address (skip for groups)
        val unifiedChat = if (!isGroup && normalizedAddress.isNotBlank() && !normalizedAddress.contains("@")) {
            try {
                unifiedChatDao.getOrCreate(
                    UnifiedChatEntity(
                        id = UnifiedChatIdGenerator.generate(),
                        normalizedAddress = normalizedAddress,
                        sourceId = chatGuid
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to get or create unified chat for $address")
                null
            }
        } else {
            null
        }

        val existingChat = chatDao.getChatByGuid(chatGuid)

        if (existingChat == null) {
            val chat = ChatEntity(
                guid = chatGuid,
                chatIdentifier = address,
                displayName = null,
                isGroup = isGroup,
                latestMessageDate = date,
                unifiedChatId = unifiedChat?.id
            )
            chatDao.insertChat(chat)
        } else {
            chatDao.updateLatestMessageDate(chatGuid, date)
            if (!existingChat.isGroup && isGroup) {
                // Update to group if needed
                chatDao.insertChat(existingChat.copy(isGroup = true))
            }

            // Link chat to unified chat if not already linked
            if (existingChat.unifiedChatId == null && unifiedChat != null) {
                chatDao.setUnifiedChatId(chatGuid, unifiedChat.id)
            }
        }

        // Update unified chat's unread count and latest message
        unifiedChat?.let { uc ->
            unifiedChatDao.incrementUnreadCount(uc.id)
            unifiedChatDao.updateLatestMessageIfNewer(
                id = uc.id,
                date = date,
                text = lastMessage,
                guid = null,
                isFromMe = false,
                hasAttachments = false,
                source = MessageSource.LOCAL_SMS.name,
                dateDelivered = null,
                dateRead = null,
                error = 0
            )
        }

        return unifiedChat
    }

    private fun getLatestSmsId(): Long {
        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            null,
            null,
            "${Telephony.Sms._ID} DESC LIMIT 1"
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }

    private fun getLatestMmsId(): Long {
        return context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID),
            null,
            null,
            "${Telephony.Mms._ID} DESC LIMIT 1"
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }

    private fun MmsMessage.toMessageEntity(chatGuid: String, unifiedChatId: String?): com.bothbubbles.data.local.db.entity.MessageEntity {
        return com.bothbubbles.data.local.db.entity.MessageEntity(
            guid = "mms-$id",
            chatGuid = chatGuid,
            unifiedChatId = unifiedChatId,
            text = textParts.joinToString("\n").takeIf { it.isNotBlank() },
            subject = subject,
            dateCreated = date,
            isFromMe = isFromMe,
            hasAttachments = imageParts.isNotEmpty(),
            messageSource = MessageSource.LOCAL_MMS.name,
            smsId = id,
            smsThreadId = threadId
        )
    }
}
