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
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatMember
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.sound.SoundManager
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
    private val unifiedChatGroupDao: UnifiedChatGroupDao,
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
                    // Get or create chat
                    val chatGuid = "sms;-;$normalizedAddress"
                    ensureChatExists(chatGuid, normalizedAddress, date, body)

                    // Create message entity
                    val message = com.bothbubbles.data.local.db.entity.MessageEntity(
                        guid = existingGuid,
                        chatGuid = chatGuid,
                        text = body,
                        dateCreated = date,
                        isFromMe = isFromMe,
                        messageSource = MessageSource.LOCAL_SMS.name,
                        smsId = id,
                        smsThreadId = threadId
                    )
                    messageDao.insertMessage(message)

                    // Update unified group timestamp so conversation appears in correct position
                    updateUnifiedGroupTimestamp(chatGuid, date, body)

                    // Show notification for incoming messages
                    if (!isFromMe) {
                        val chat = chatDao.getChatByGuid(chatGuid)

                        // Check if notifications are disabled for this chat
                        if (chat?.notificationsEnabled == false) {
                            Timber.i("Notifications disabled for chat $chatGuid, skipping SMS notification")
                        } else if (chat?.isSnoozed == true) {
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
                                    chatTitle = chat?.displayName ?: senderName ?: address,
                                    messageText = body ?: "",
                                    messageGuid = existingGuid,
                                    senderName = senderName,
                                    senderAddress = normalizedAddress,
                                    isGroup = false,
                                    avatarUri = senderAvatarUri,
                                    groupAvatarPath = chat?.effectiveGroupPhotoPath
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

                    // Ensure chat exists
                    ensureChatExists(chatGuid, primaryAddress, date, textContent, isGroup)

                    // Create message entity
                    val message = mmsMessage.toMessageEntity(chatGuid)
                    messageDao.insertMessage(message)

                    // Update unified group timestamp so conversation appears in correct position
                    updateUnifiedGroupTimestamp(chatGuid, date, textContent)

                    // Show notification for incoming messages
                    if (!isFromMe) {
                        val chat = chatDao.getChatByGuid(chatGuid)

                        // Check if notifications are disabled for this chat
                        if (chat?.notificationsEnabled == false) {
                            Timber.i("Notifications disabled for chat $chatGuid, skipping MMS notification")
                        } else if (chat?.isSnoozed == true) {
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
                                    chatTitle = chat?.displayName ?: senderName ?: primaryAddress,
                                    messageText = textContent ?: "[MMS]",
                                    messageGuid = existingGuid,
                                    senderName = if (isGroup) (senderName ?: primaryAddress) else senderName,
                                    senderAddress = primaryAddress,
                                    isGroup = isGroup,
                                    avatarUri = senderAvatarUri,
                                    groupAvatarPath = chat?.effectiveGroupPhotoPath,
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
    ) {
        val existingChat = chatDao.getChatByGuid(chatGuid)
        val isNewChat = existingChat == null

        if (isNewChat) {
            val chat = ChatEntity(
                guid = chatGuid,
                chatIdentifier = address,
                displayName = null,
                isGroup = isGroup,
                lastMessageDate = date,
                lastMessageText = lastMessage,
                unreadCount = 1
            )
            chatDao.insertChat(chat)
        } else {
            chatDao.updateLastMessage(chatGuid, date, lastMessage)
            if (!existingChat.isGroup && isGroup) {
                // Update to group if needed
                chatDao.insertChat(existingChat.copy(isGroup = true))
            }
        }

        // Link new 1:1 SMS chats to unified groups for conversation merging
        // This enables messages sent via Android Auto to appear in merged iMessage/SMS views
        if (isNewChat && !isGroup) {
            linkChatToUnifiedGroup(chatGuid, address)
        }
    }

    /**
     * Link a chat to a unified group, creating the group if necessary.
     * This enables merging SMS and iMessage conversations for the same contact.
     */
    private suspend fun linkChatToUnifiedGroup(chatGuid: String, normalizedPhone: String) {
        // Skip if identifier is empty or looks like an email/RCS address (not a phone)
        if (normalizedPhone.isBlank() || normalizedPhone.contains("@")) {
            Timber.d("linkChatToUnifiedGroup: Skipping $chatGuid - invalid identifier '$normalizedPhone'")
            return
        }

        Timber.d("linkChatToUnifiedGroup: Linking $chatGuid to group for '$normalizedPhone'")

        try {
            // Check if this chat is already in a unified group
            if (unifiedChatGroupDao.isChatInUnifiedGroup(chatGuid)) {
                Timber.d("linkChatToUnifiedGroup: $chatGuid is already in a unified group")
                return
            }

            // Check if unified group already exists for this phone number
            var group = unifiedChatGroupDao.getGroupByIdentifier(normalizedPhone)
            Timber.d("linkChatToUnifiedGroup: Existing group for '$normalizedPhone': ${group?.id}")

            if (group == null) {
                // Check if there's an existing iMessage chat for this phone
                val existingIMessageChat = findIMessageChatForPhone(normalizedPhone)
                Timber.d("linkChatToUnifiedGroup: Found existing iMessage chat: ${existingIMessageChat?.guid}")

                // Create new unified group - prefer iMessage as primary if it exists
                val primaryGuid = existingIMessageChat?.guid ?: chatGuid
                val newGroup = UnifiedChatGroupEntity(
                    identifier = normalizedPhone,
                    primaryChatGuid = primaryGuid,
                    displayName = existingIMessageChat?.displayName
                )

                // Use atomic method to prevent FOREIGN KEY errors
                group = unifiedChatGroupDao.getOrCreateGroupAndAddMember(newGroup, chatGuid)
                Timber.d("linkChatToUnifiedGroup: Created/got group id=${group.id} and added member $chatGuid")

                // Add existing iMessage chat to group if found (separate from SMS chat)
                if (existingIMessageChat != null && existingIMessageChat.guid != chatGuid) {
                    Timber.d("linkChatToUnifiedGroup: Adding iMessage chat ${existingIMessageChat.guid} to group ${group.id}")
                    unifiedChatGroupDao.insertMember(
                        UnifiedChatMember(groupId = group.id, chatGuid = existingIMessageChat.guid)
                    )
                }
            } else {
                // Group exists, just add this SMS chat to it
                Timber.d("linkChatToUnifiedGroup: Adding $chatGuid to existing group ${group.id}")
                unifiedChatGroupDao.insertMember(
                    UnifiedChatMember(groupId = group.id, chatGuid = chatGuid)
                )
            }

            Timber.d("linkChatToUnifiedGroup: Successfully linked $chatGuid to group ${group.id}")
        } catch (e: Exception) {
            Timber.e(e, "linkChatToUnifiedGroup: FAILED to link $chatGuid to group for '$normalizedPhone'")
            // Don't rethrow - unified group linking is non-critical for message storage
        }
    }

    /**
     * Find an existing iMessage chat for a given phone number.
     * Searches through non-group iMessage chats and matches by participant phone.
     */
    private suspend fun findIMessageChatForPhone(normalizedPhone: String): ChatEntity? {
        val nonGroupChats = chatDao.getAllNonGroupIMessageChats()
        if (nonGroupChats.isEmpty()) return null

        // Batch fetch all participants for all chats in a single query
        val chatGuids = nonGroupChats.map { it.guid }
        val participantsByChat = chatDao.getParticipantsWithChatGuids(chatGuids)
            .groupBy({ it.chatGuid }, { it.handle })

        for (chat in nonGroupChats) {
            val participants = participantsByChat[chat.guid] ?: emptyList()
            for (participant in participants) {
                val normalized = PhoneAndCodeParsingUtils.normalizePhoneNumber(participant.address)
                if (phonesMatch(normalized, normalizedPhone)) {
                    return chat
                }
            }
        }
        return null
    }

    /**
     * Compare two phone numbers for equality, handling different formats.
     */
    private fun phonesMatch(phone1: String, phone2: String): Boolean {
        if (phone1 == phone2) return true
        // Strip all non-digits and compare last 10 digits
        val digits1 = phone1.filter { it.isDigit() }.takeLast(10)
        val digits2 = phone2.filter { it.isDigit() }.takeLast(10)
        return digits1.length >= 7 && digits1 == digits2
    }

    /**
     * Update the unified group's latestMessageDate when a new message is inserted.
     * This ensures the conversation appears in the correct position in the list.
     */
    private suspend fun updateUnifiedGroupTimestamp(chatGuid: String, messageDate: Long, messageText: String?) {
        try {
            val group = unifiedChatGroupDao.getGroupForChat(chatGuid)
            if (group != null) {
                val currentLatest = group.latestMessageDate ?: 0L
                if (messageDate > currentLatest) {
                    unifiedChatGroupDao.updateLatestMessage(group.id, messageDate, messageText)
                    Timber.d("Updated unified group ${group.id} latestMessageDate to $messageDate")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to update unified group timestamp for $chatGuid")
        }
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

    private fun MmsMessage.toMessageEntity(chatGuid: String): com.bothbubbles.data.local.db.entity.MessageEntity {
        return com.bothbubbles.data.local.db.entity.MessageEntity(
            guid = "mms-$id",
            chatGuid = chatGuid,
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
