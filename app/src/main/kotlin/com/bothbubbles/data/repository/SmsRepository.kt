package com.bothbubbles.data.repository

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.TombstoneDao
import timber.log.Timber
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.ChatHandleCrossRef
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.sms.*
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    private val tombstoneDao: TombstoneDao,
    private val unifiedChatDao: UnifiedChatDao,
    private val unifiedChatRepository: UnifiedChatRepository,
    private val smsContentProvider: SmsContentProvider,
    private val smsSendService: SmsSendService,
    private val mmsSendService: MmsSendService,
    private val smsContentObserver: SmsContentObserver,
    private val androidContactsService: AndroidContactsService
) {

    // Delegate instances for modular operations
    private val importer = SmsImporter(
        chatDao = chatDao,
        handleDao = handleDao,
        messageDao = messageDao,
        unifiedChatDao = unifiedChatDao,
        unifiedChatRepository = unifiedChatRepository,
        smsContentProvider = smsContentProvider,
        androidContactsService = androidContactsService
    )

    private val messageOperations = SmsMessageOperations(
        context = context,
        chatDao = chatDao,
        messageDao = messageDao,
        tombstoneDao = tombstoneDao,
        smsContentProvider = smsContentProvider
    )

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
            Timber.d("Checking ${mmsWithoutStatus.size} MMS messages for draft status")

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
                            Timber.d("Marked MMS ${message.guid} as draft")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error marking MMS drafts")
        }
    }

    /**
     * Check if device has SMS capability
     */
    fun hasSmsCapability(): Boolean {
        return context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)
    }

    /**
     * Repair orphaned SMS chats by linking them to unified chats.
     * This fixes messages sent via Android Auto or other external apps that
     * were created before the unified chat linking was added.
     *
     * Call this on app startup to retroactively fix missing messages in merged views.
     */
    suspend fun repairOrphanedSmsChats(): Int = withContext(Dispatchers.IO) {
        try {
            // Find all SMS/MMS chats that are not linked to a unified chat
            val allSmsChats = chatDao.getAllSmsChats()
            var repairedCount = 0

            for (chat in allSmsChats) {
                // Skip if already linked to a unified chat
                if (chat.unifiedChatId != null) {
                    continue
                }

                // Skip group chats - only merge 1:1 conversations
                if (chat.isGroup) {
                    continue
                }

                // Extract and normalize the phone number from the chat identifier
                val identifier = chat.chatIdentifier ?: continue
                val normalizedPhone = PhoneAndCodeParsingUtils.normalizePhoneNumber(identifier)

                // Skip if identifier is empty or looks like an email/RCS address
                if (normalizedPhone.isBlank() || normalizedPhone.contains("@")) {
                    continue
                }

                Timber.d("repairOrphanedSmsChats: Linking orphaned chat ${chat.guid} for '$normalizedPhone'")

                try {
                    // Check if there's an existing iMessage chat for this phone to determine display name
                    val existingIMessageChat = findIMessageChatForPhone(normalizedPhone)
                    val displayName = existingIMessageChat?.displayName ?: chat.displayName

                    // Get or create unified chat for this phone number
                    val unifiedChat = unifiedChatRepository.getOrCreate(
                        address = normalizedPhone,
                        sourceId = existingIMessageChat?.guid ?: chat.guid,
                        displayName = displayName,
                        isGroup = false
                    )

                    // Link this SMS chat to the unified chat
                    chatDao.setUnifiedChatId(chat.guid, unifiedChat.id)

                    // Also link the iMessage chat if found and not already linked
                    if (existingIMessageChat != null && existingIMessageChat.unifiedChatId == null) {
                        chatDao.setUnifiedChatId(existingIMessageChat.guid, unifiedChat.id)
                        messageDao.setUnifiedChatIdForChat(existingIMessageChat.guid, unifiedChat.id)
                        Timber.d("repairOrphanedSmsChats: Also linked iMessage ${existingIMessageChat.guid} to unified chat ${unifiedChat.id}")
                    }

                    // Update messages' unified_chat_id
                    messageDao.setUnifiedChatIdForChat(chat.guid, unifiedChat.id)

                    // Update unified chat's latest message if this chat has a more recent one
                    val chatLatestDate = chat.latestMessageDate ?: 0L
                    if (chatLatestDate > 0) {
                        unifiedChatRepository.updateLatestMessageIfNewer(
                            id = unifiedChat.id,
                            date = chatLatestDate,
                            text = null,
                            guid = null,
                            isFromMe = false,
                            hasAttachments = false,
                            source = "LOCAL_SMS"
                        )
                    }

                    Timber.d("repairOrphanedSmsChats: Linked ${chat.guid} to unified chat ${unifiedChat.id}")
                    repairedCount++
                } catch (e: Exception) {
                    Timber.e(e, "repairOrphanedSmsChats: Failed to link ${chat.guid}")
                }
            }

            if (repairedCount > 0) {
                Timber.i("repairOrphanedSmsChats: Repaired $repairedCount orphaned SMS chats")
            }

            repairedCount
        } catch (e: Exception) {
            Timber.e(e, "repairOrphanedSmsChats: Error repairing orphaned SMS chats")
            0
        }
    }

    /**
     * Repair unified chat timestamps by updating latestMessageDate from linked chats.
     * This fixes unified chats that have an outdated timestamp (e.g., when RCS messages were
     * received but the unified chat timestamp wasn't updated).
     *
     * Call this on app startup to ensure conversation list is sorted correctly.
     */
    suspend fun repairUnifiedChatTimestamps(): Int = withContext(Dispatchers.IO) {
        try {
            // Get all unified chats
            val allUnifiedChats = unifiedChatDao.getAllChats()
            var repairedCount = 0

            for (unifiedChat in allUnifiedChats) {
                // Get all chat GUIDs linked to this unified chat
                val linkedChats = chatDao.getChatsForUnifiedChat(unifiedChat.id)
                if (linkedChats.isEmpty()) continue

                // Find the latest message date across all linked chats
                var latestDate = 0L
                var latestText: String? = null

                for (chat in linkedChats) {
                    val chatDate = chat.latestMessageDate ?: 0L
                    if (chatDate > latestDate) {
                        latestDate = chatDate
                        // Note: ChatEntity no longer has lastMessageText, would need to query message
                    }
                }

                // Update if the computed date is newer than the stored date
                val unifiedLatestDate = unifiedChat.latestMessageDate ?: 0L
                if (latestDate > unifiedLatestDate) {
                    unifiedChatRepository.updateLatestMessageIfNewer(
                        id = unifiedChat.id,
                        date = latestDate,
                        text = latestText,
                        guid = null,
                        isFromMe = false,
                        hasAttachments = false,
                        source = null
                    )
                    Timber.d("repairUnifiedChatTimestamps: Updated unified chat ${unifiedChat.id} from $unifiedLatestDate to $latestDate")
                    repairedCount++
                }
            }

            if (repairedCount > 0) {
                Timber.i("repairUnifiedChatTimestamps: Repaired $repairedCount unified chat timestamps")
            }

            repairedCount
        } catch (e: Exception) {
            Timber.e(e, "repairUnifiedChatTimestamps: Error repairing unified chat timestamps")
            0
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
    ): Result<Int> {
        return importer.importAllThreads(limit, onProgress)
    }

    /**
     * Import messages for a specific chat
     * @param chatGuid The chat GUID
     * @param limit Maximum number of messages to import
     */
    suspend fun importMessagesForChat(
        chatGuid: String,
        limit: Int = 100
    ): Result<Int> {
        return importer.importMessagesForChat(chatGuid, limit) { guid ->
            getThreadIdForChat(guid)
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
    suspend fun markThreadAsRead(chatGuid: String): Result<Unit> {
        val result = messageOperations.markThreadAsRead(chatGuid) { guid ->
            getThreadIdForChat(guid)
        }
        // Also update the unified chat's unread count for badge sync
        if (result.isSuccess) {
            val chat = chatDao.getChatByGuid(chatGuid)
            chat?.unifiedChatId?.let { unifiedChatId ->
                unifiedChatDao.markAsRead(unifiedChatId)
            }
        }
        return result
    }

    /**
     * Delete a message
     */
    suspend fun deleteMessage(messageGuid: String): Result<Unit> {
        return messageOperations.deleteMessage(messageGuid)
    }

    /**
     * Delete all messages in a thread
     */
    suspend fun deleteThread(chatGuid: String): Result<Unit> {
        return messageOperations.deleteThread(chatGuid) { guid ->
            getThreadIdForChat(guid)
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
        return SmsContentProviderHelpers.getThreadIdForChat(context, chatGuid)
    }
}
