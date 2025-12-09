package com.bothbubbles.data.repository

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.ChatHandleCrossRef
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatMember
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.sms.*
import com.bothbubbles.ui.components.PhoneAndCodeParsingUtils
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for SMS/MMS operations.
 * Provides a unified interface for local SMS messaging functionality.
 */
@Singleton
class SmsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val messageDao: MessageDao,
    private val unifiedChatGroupDao: UnifiedChatGroupDao,
    private val smsContentProvider: SmsContentProvider,
    private val smsSendService: SmsSendService,
    private val mmsSendService: MmsSendService,
    private val smsContentObserver: SmsContentObserver,
    private val androidContactsService: AndroidContactsService
) {
    companion object {
        private const val TAG = "SmsRepository"
    }

    // ===== App State =====

    /**
     * Check if app is set as default SMS app
     */
    fun isDefaultSmsApp(): Boolean {
        val defaultPackage = Telephony.Sms.getDefaultSmsPackage(context)
        return defaultPackage == context.packageName
    }

    /**
     * Mark existing MMS drafts in the database.
     * This is a one-time migration to properly mark drafts that were imported before
     * draft detection was added.
     */
    suspend fun markExistingMmsDrafts() = withContext(Dispatchers.IO) {
        try {
            val mmsWithoutStatus = messageDao.getLocalMmsWithoutStatus()
            Log.d(TAG, "Checking ${mmsWithoutStatus.size} MMS messages for draft status")

            for (message in mmsWithoutStatus) {
                // Extract MMS ID from guid (format: "mms-{id}")
                val mmsId = message.guid.removePrefix("mms-").toLongOrNull() ?: continue

                // Query system MMS provider to check messageBox
                context.contentResolver.query(
                    Uri.parse("content://mms/$mmsId"),
                    arrayOf(Telephony.Mms.MESSAGE_BOX),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val messageBox = cursor.getInt(0)
                        val isDraft = messageBox == Telephony.Mms.MESSAGE_BOX_DRAFTS
                        val status = if (isDraft) "draft" else "complete"
                        messageDao.updateSmsStatus(message.guid, status)
                        if (isDraft) {
                            Log.d(TAG, "Marked MMS ${message.guid} as draft")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error marking MMS drafts", e)
        }
    }

    /**
     * Check if device has SMS capability
     */
    fun hasSmsCapability(): Boolean {
        return context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)
    }

    // ===== Content Observer =====

    /**
     * Start observing SMS/MMS changes
     */
    fun startObserving() {
        smsContentObserver.startObserving()
    }

    /**
     * Stop observing SMS/MMS changes
     */
    fun stopObserving() {
        smsContentObserver.stopObserving()
    }

    // ===== Import Operations =====

    /**
     * Import all SMS/MMS threads from the system
     * @param limit Maximum number of threads to import
     * @param onProgress Progress callback (current, total)
     */
    suspend fun importAllThreads(
        limit: Int = 500,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val threads = smsContentProvider.getThreads(limit = limit)
            var imported = 0

            threads.forEachIndexed { index, thread ->
                importThread(thread)
                imported++
                onProgress?.invoke(index + 1, threads.size)
            }

            imported
        }
    }

    /**
     * Import a single SMS thread
     */
    private suspend fun importThread(thread: SmsThread) {
        val rawAddresses = thread.recipientAddresses
        if (rawAddresses.isEmpty()) return

        // Filter to valid phone numbers and normalize
        val addresses = rawAddresses
            .filter { isValidPhoneAddress(it) }
            .map { PhoneAndCodeParsingUtils.normalizePhoneNumber(it) }
            .filter { it.isNotBlank() }
            .distinct()

        if (addresses.isEmpty()) {
            Log.d(TAG, "Skipping thread ${thread.threadId} - no valid phone addresses")
            return
        }

        val isGroup = addresses.size > 1
        val chatGuid = if (isGroup) {
            "mms;-;${addresses.sorted().joinToString(",")}"
        } else {
            "sms;-;${addresses.first()}"
        }

        // Create or update chat
        val existingChat = chatDao.getChatByGuid(chatGuid)
        if (existingChat == null) {
            val chat = ChatEntity(
                guid = chatGuid,
                chatIdentifier = if (isGroup) null else addresses.first(),
                displayName = null, // Will be resolved from contacts
                isGroup = isGroup,
                lastMessageDate = thread.lastMessageDate,
                lastMessageText = thread.snippet,
                unreadCount = if (thread.isRead) 0 else 1
            )
            chatDao.insertChat(chat)
        }

        // Create handles for addresses and link to chat
        // Use upsertHandle to get existing handle ID instead of creating duplicates
        // (insertHandle with REPLACE would delete existing handles, breaking cross-refs)
        addresses.filter { it.isNotBlank() }.forEach { address ->
            // Look up contact info from device contacts
            val contactName = androidContactsService.getContactDisplayName(address)
            val contactPhotoUri = androidContactsService.getContactPhotoUri(address)
            val formattedAddress = PhoneNumberFormatter.format(address)

            val handle = HandleEntity(
                address = address,
                formattedAddress = formattedAddress,
                service = "SMS",
                cachedDisplayName = contactName,
                cachedAvatarPath = contactPhotoUri
            )
            val handleId = handleDao.upsertHandle(handle)
            // Link handle to chat (required for getParticipantsForChat to work)
            chatDao.insertChatHandleCrossRef(ChatHandleCrossRef(chatGuid, handleId))
        }

        // For single contacts (not groups), link to unified group
        val validAddresses = addresses.filter { it.isNotBlank() }
        if (!isGroup && validAddresses.isNotEmpty()) {
            val normalizedPhone = validAddresses.first()
            linkChatToUnifiedGroup(chatGuid, normalizedPhone)
        }

        // Import the most recent message so the badge displays correctly in conversation list
        importLatestMessageForThread(thread.threadId, chatGuid, isGroup)

        // Update unified group's latestMessageDate if applicable
        if (!isGroup && validAddresses.isNotEmpty()) {
            val normalizedPhone = validAddresses.first()
            val group = unifiedChatGroupDao.getGroupByIdentifier(normalizedPhone)
            if (group != null) {
                // Update latestMessageDate if this thread's message is newer
                val currentLatest = group.latestMessageDate ?: 0L
                if (thread.lastMessageDate > currentLatest) {
                    unifiedChatGroupDao.updateLatestMessage(group.id, thread.lastMessageDate, thread.snippet)
                }
            }
        }
    }

    /**
     * Import the most recent message from an SMS/MMS thread.
     * This ensures the conversation list can display the correct message source badge.
     */
    private suspend fun importLatestMessageForThread(threadId: Long, chatGuid: String, isGroup: Boolean) {
        // First try SMS (more common)
        val smsMessages = smsContentProvider.getSmsMessages(threadId, limit = 1)
        val mmsMessages = smsContentProvider.getMmsMessages(threadId, limit = 1)

        // Find the most recent message between SMS and MMS
        val latestSms = smsMessages.firstOrNull()
        val latestMms = mmsMessages.firstOrNull()

        val latestMessage: MessageEntity? = when {
            latestSms != null && latestMms != null -> {
                if (latestSms.date >= latestMms.date) {
                    with(smsContentProvider) { latestSms.toMessageEntity(chatGuid) }
                } else {
                    with(smsContentProvider) { latestMms.toMessageEntity(chatGuid) }
                }
            }
            latestSms != null -> with(smsContentProvider) { latestSms.toMessageEntity(chatGuid) }
            latestMms != null -> with(smsContentProvider) { latestMms.toMessageEntity(chatGuid) }
            else -> null
        }

        // Insert the message if we found one (upsert to avoid duplicates)
        latestMessage?.let { msg ->
            messageDao.insertOrUpdateMessage(msg)
        }
    }

    /**
     * Link a chat to a unified group, creating the group if necessary.
     * This enables merging SMS and iMessage conversations for the same contact.
     */
    private suspend fun linkChatToUnifiedGroup(chatGuid: String, normalizedPhone: String) {
        // Skip if identifier is empty or looks like an email/RCS address (not a phone)
        // This prevents all non-phone chats from being lumped into a single group
        if (normalizedPhone.isBlank() || normalizedPhone.contains("@")) {
            Log.d(TAG, "linkChatToUnifiedGroup: Skipping $chatGuid - invalid identifier '$normalizedPhone'")
            return
        }

        Log.d(TAG, "linkChatToUnifiedGroup: Linking $chatGuid to group for '$normalizedPhone'")

        try {
            // Check if unified group already exists for this phone number
            var group = unifiedChatGroupDao.getGroupByIdentifier(normalizedPhone)
            Log.d(TAG, "linkChatToUnifiedGroup: Existing group for '$normalizedPhone': ${group?.id}")

            if (group == null) {
                // Check if there's an existing iMessage chat for this phone
                val existingIMessageChat = findIMessageChatForPhone(normalizedPhone)
                Log.d(TAG, "linkChatToUnifiedGroup: Found existing iMessage chat: ${existingIMessageChat?.guid}")

                // Create new unified group and add this chat atomically
                val primaryGuid = existingIMessageChat?.guid ?: chatGuid
                val newGroup = UnifiedChatGroupEntity(
                    identifier = normalizedPhone,
                    primaryChatGuid = primaryGuid,
                    displayName = existingIMessageChat?.displayName
                )

                // Use atomic method to prevent FOREIGN KEY errors
                group = unifiedChatGroupDao.getOrCreateGroupAndAddMember(newGroup, chatGuid)
                Log.d(TAG, "linkChatToUnifiedGroup: Created/got group id=${group.id} and added member $chatGuid")

                // Add existing iMessage chat to group if found (separate from SMS chat)
                if (existingIMessageChat != null && existingIMessageChat.guid != chatGuid) {
                    Log.d(TAG, "linkChatToUnifiedGroup: Adding iMessage chat ${existingIMessageChat.guid} to group ${group.id}")
                    unifiedChatGroupDao.insertMember(
                        UnifiedChatMember(groupId = group.id, chatGuid = existingIMessageChat.guid)
                    )
                }
            } else {
                // Group exists, just add this SMS chat to it
                Log.d(TAG, "linkChatToUnifiedGroup: Adding $chatGuid to existing group ${group.id}")
                unifiedChatGroupDao.insertMember(
                    UnifiedChatMember(groupId = group.id, chatGuid = chatGuid)
                )
            }

            Log.d(TAG, "linkChatToUnifiedGroup: Successfully linked $chatGuid to group ${group.id}")
        } catch (e: Exception) {
            Log.e(TAG, "linkChatToUnifiedGroup: FAILED to link $chatGuid to group for '$normalizedPhone'", e)
            throw e
        }
    }

    /**
     * Find an existing iMessage chat for a given phone number.
     * Searches through non-group iMessage chats and matches by participant phone.
     */
    private suspend fun findIMessageChatForPhone(normalizedPhone: String): ChatEntity? {
        val nonGroupChats = chatDao.getAllNonGroupIMessageChats()

        for (chat in nonGroupChats) {
            val participants = chatDao.getParticipantsForChat(chat.guid)
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
     * Check if two phone numbers match, accounting for different formats.
     * Handles cases like +1 prefix, different country codes, etc.
     */
    private fun phonesMatch(phone1: String, phone2: String): Boolean {
        // Empty strings should never match (prevents RCS/email addresses from matching all phones)
        if (phone1.isEmpty() || phone2.isEmpty()) return false
        if (phone1 == phone2) return true
        // Only use endsWith matching if both strings have reasonable length
        if (phone1.length >= 7 && phone2.length >= 7) {
            if (phone1.endsWith(phone2) || phone2.endsWith(phone1)) return true
        }
        // Compare last 10 digits for US numbers
        if (phone1.length >= 10 && phone2.length >= 10 &&
            phone1.takeLast(10) == phone2.takeLast(10)) {
            return true
        }
        return false
    }

    /**
     * Import messages for a specific chat
     * @param chatGuid The chat GUID
     * @param limit Maximum number of messages to import
     */
    suspend fun importMessagesForChat(
        chatGuid: String,
        limit: Int = 100
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            // Parse thread ID from chat guid or look it up
            val threadId = getThreadIdForChat(chatGuid) ?: return@runCatching 0

            var imported = 0

            // Import SMS messages
            val smsMessages = smsContentProvider.getSmsMessages(threadId, limit = limit)
            smsMessages.forEach { sms ->
                val guid = "sms-${sms.id}"
                if (messageDao.getMessageByGuid(guid) == null) {
                    val entity = sms.toMessageEntity(chatGuid)
                    messageDao.insertMessage(entity)
                    imported++
                }
            }

            // Import MMS messages
            val mmsMessages = smsContentProvider.getMmsMessages(threadId, limit = limit)
            mmsMessages.forEach { mms ->
                val guid = "mms-${mms.id}"
                if (messageDao.getMessageByGuid(guid) == null) {
                    val entity = mms.toMessageEntity(chatGuid)

                    // Check for duplicate SMS message with matching content and timestamp
                    // This prevents the same message from appearing twice when recorded as both SMS and MMS
                    val textContent = entity.text
                    if (textContent != null) {
                        val matchingMessage = messageDao.findMatchingMessage(
                            chatGuid = chatGuid,
                            text = textContent,
                            isFromMe = entity.isFromMe,
                            dateCreated = entity.dateCreated,
                            toleranceMs = 10000 // 10 second window for MMS which can have delayed timestamps
                        )
                        if (matchingMessage != null) {
                            // Skip this MMS as it duplicates an existing SMS message
                            return@forEach
                        }
                    }

                    messageDao.insertMessage(entity)
                    imported++
                }
            }

            imported
        }
    }

    // ===== Send Operations =====

    /**
     * Send an SMS message
     */
    suspend fun sendSms(
        address: String,
        text: String,
        subscriptionId: Int = -1
    ): Result<MessageEntity> {
        // Normalize address to prevent duplicate conversations
        val normalizedAddress = PhoneAndCodeParsingUtils.normalizePhoneNumber(address)
        val chatGuid = "sms;-;$normalizedAddress"
        ensureChatExists(chatGuid, normalizedAddress, isGroup = false)
        return smsSendService.sendSms(address, text, chatGuid, subscriptionId)
    }

    /**
     * Send an MMS message (for group or with attachments)
     */
    suspend fun sendMms(
        recipients: List<String>,
        text: String?,
        attachments: List<Uri> = emptyList(),
        subject: String? = null,
        subscriptionId: Int = -1
    ): Result<MessageEntity> {
        // Normalize addresses to prevent duplicate conversations
        val normalizedRecipients = recipients.map { PhoneAndCodeParsingUtils.normalizePhoneNumber(it) }
        val chatGuid = if (normalizedRecipients.size == 1 && attachments.isEmpty()) {
            "sms;-;${normalizedRecipients.first()}"
        } else {
            "mms;-;${normalizedRecipients.sorted().joinToString(",")}"
        }

        ensureChatExists(chatGuid, normalizedRecipients.firstOrNull() ?: "", isGroup = normalizedRecipients.size > 1)
        return mmsSendService.sendMms(recipients, text, attachments, chatGuid, subject, subscriptionId)
    }

    // ===== Message Operations =====

    /**
     * Mark all messages in a thread as read (both SMS and MMS)
     */
    suspend fun markThreadAsRead(chatGuid: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val threadId = getThreadIdForChat(chatGuid)
            if (threadId != null) {
                smsContentProvider.markThreadAsRead(threadId)  // SMS
                smsContentProvider.markMmsAsRead(threadId)     // MMS
            }
            chatDao.updateUnreadCount(chatGuid, 0)
            chatDao.updateUnreadStatus(chatGuid, false)
        }
    }

    /**
     * Delete a message
     */
    suspend fun deleteMessage(messageGuid: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Delete from our database
            messageDao.deleteMessageByGuid(messageGuid)

            // Delete from system SMS/MMS database if it's a local message
            when {
                messageGuid.startsWith("sms-") -> {
                    val smsId = messageGuid.removePrefix("sms-").toLongOrNull()
                    if (smsId != null) {
                        context.contentResolver.delete(
                            Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, smsId.toString()),
                            null,
                            null
                        )
                    }
                }
                messageGuid.startsWith("mms-") -> {
                    val mmsId = messageGuid.removePrefix("mms-").toLongOrNull()
                    if (mmsId != null) {
                        context.contentResolver.delete(
                            Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, mmsId.toString()),
                            null,
                            null
                        )
                    }
                }
            }
        }
    }

    /**
     * Delete all messages in a thread
     */
    suspend fun deleteThread(chatGuid: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val threadId = getThreadIdForChat(chatGuid)
            if (threadId != null) {
                // Delete SMS messages
                context.contentResolver.delete(
                    Telephony.Sms.CONTENT_URI,
                    "${Telephony.Sms.THREAD_ID} = ?",
                    arrayOf(threadId.toString())
                )
                // Delete MMS messages
                context.contentResolver.delete(
                    Telephony.Mms.CONTENT_URI,
                    "${Telephony.Mms.THREAD_ID} = ?",
                    arrayOf(threadId.toString())
                )
            }

            // Delete from our database
            messageDao.deleteMessagesForChat(chatGuid)
            chatDao.deleteChatByGuid(chatGuid)
        }
    }

    // ===== SIM Info =====

    /**
     * Get available SIM cards
     */
    fun getAvailableSims(): List<SimInfo> {
        return smsSendService.getAvailableSubscriptions()
    }

    /**
     * Get default SMS subscription ID
     */
    fun getDefaultSimId(): Int {
        return smsSendService.getDefaultSubscriptionId()
    }

    // ===== Helper Methods =====

    private suspend fun ensureChatExists(chatGuid: String, address: String, isGroup: Boolean) {
        val existingChat = chatDao.getChatByGuid(chatGuid)
        if (existingChat == null) {
            val chat = ChatEntity(
                guid = chatGuid,
                chatIdentifier = if (isGroup) null else address,
                displayName = null,
                isGroup = isGroup,
                lastMessageDate = System.currentTimeMillis(),
                unreadCount = 0
            )
            chatDao.insertChat(chat)

            // Create handle and link to chat (use upsert to preserve existing handle IDs)
            if (address.isNotBlank()) {
                // Look up contact info from device contacts
                val contactName = androidContactsService.getContactDisplayName(address)
                val contactPhotoUri = androidContactsService.getContactPhotoUri(address)
                val formattedAddress = PhoneNumberFormatter.format(address)

                val handle = HandleEntity(
                    address = address,
                    formattedAddress = formattedAddress,
                    service = "SMS",
                    cachedDisplayName = contactName,
                    cachedAvatarPath = contactPhotoUri
                )
                val handleId = handleDao.upsertHandle(handle)
                chatDao.insertChatHandleCrossRef(ChatHandleCrossRef(chatGuid, handleId))
            }
        }
    }

    private suspend fun getThreadIdForChat(chatGuid: String): Long? {
        // Extract address(es) from chat GUID
        val parts = chatGuid.split(";-;")
        if (parts.size != 2) return null

        val addresses = parts[1].split(",")
        if (addresses.isEmpty()) return null

        // Look up thread ID from the first message with this address
        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms.ADDRESS} = ?",
            arrayOf(addresses.first()),
            "${Telephony.Sms.DATE} DESC LIMIT 1"
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    // Extension functions for entity conversion
    private fun SmsMessage.toMessageEntity(chatGuid: String): MessageEntity {
        return MessageEntity(
            guid = "sms-$id",
            chatGuid = chatGuid,
            text = body,
            dateCreated = date,
            isFromMe = isFromMe,
            error = if (isFailed) 1 else 0,
            messageSource = MessageSource.LOCAL_SMS.name,
            smsId = id,
            smsThreadId = threadId,
            smsStatus = when {
                isDraft -> "draft"
                isFailed -> "failed"
                isPending -> "pending"
                else -> "complete"
            },
            simSlot = if (subscriptionId >= 0) subscriptionId else null
        )
    }

    private fun MmsMessage.toMessageEntity(chatGuid: String): MessageEntity {
        return MessageEntity(
            guid = "mms-$id",
            chatGuid = chatGuid,
            text = textParts.joinToString("\n").takeIf { it.isNotBlank() },
            subject = subject,
            dateCreated = date,
            isFromMe = isFromMe,
            hasAttachments = imageParts.isNotEmpty(),
            messageSource = MessageSource.LOCAL_MMS.name,
            smsId = id,
            smsThreadId = threadId,
            smsStatus = if (isDraft) "draft" else "complete"
        )
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
}
