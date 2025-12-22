package com.bothbubbles.data.repository

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.TombstoneDao
import timber.log.Timber
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.ChatHandleCrossRef
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatMember
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
    private val unifiedChatGroupDao: UnifiedChatGroupDao,
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
        unifiedChatGroupDao = unifiedChatGroupDao,
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
     * Repair orphaned SMS chats by linking them to unified groups.
     * This fixes messages sent via Android Auto or other external apps that
     * were created before the unified group linking was added.
     *
     * Call this on app startup to retroactively fix missing messages in merged views.
     */
    suspend fun repairOrphanedSmsChats(): Int = withContext(Dispatchers.IO) {
        try {
            // Find all SMS/MMS chats that are not in any unified group
            val allSmsChats = chatDao.getAllSmsChats()
            var repairedCount = 0

            for (chat in allSmsChats) {
                // Skip if already in a unified group
                if (unifiedChatGroupDao.isChatInUnifiedGroup(chat.guid)) {
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
                    // Check if unified group already exists for this phone number
                    var group = unifiedChatGroupDao.getGroupByIdentifier(normalizedPhone)

                    if (group == null) {
                        // Check if there's an existing iMessage chat for this phone
                        val existingIMessageChat = findIMessageChatForPhone(normalizedPhone)

                        // Determine latestMessageDate from the most recent chat
                        val latestDate = maxOf(
                            chat.lastMessageDate ?: 0L,
                            existingIMessageChat?.lastMessageDate ?: 0L
                        )

                        // Create new unified group - prefer iMessage as primary if it exists
                        val primaryGuid = existingIMessageChat?.guid ?: chat.guid
                        val newGroup = UnifiedChatGroupEntity(
                            identifier = normalizedPhone,
                            primaryChatGuid = primaryGuid,
                            displayName = existingIMessageChat?.displayName ?: chat.displayName,
                            latestMessageDate = latestDate.takeIf { it > 0 }
                        )

                        // Use atomic method to prevent FOREIGN KEY errors
                        group = unifiedChatGroupDao.getOrCreateGroupAndAddMember(newGroup, chat.guid)
                        Timber.d("repairOrphanedSmsChats: Created group ${group.id} for ${chat.guid} with latestMessageDate=$latestDate")

                        // Add existing iMessage chat to group if found
                        if (existingIMessageChat != null && existingIMessageChat.guid != chat.guid) {
                            unifiedChatGroupDao.insertMember(
                                UnifiedChatMember(groupId = group.id, chatGuid = existingIMessageChat.guid)
                            )
                            Timber.d("repairOrphanedSmsChats: Also linked iMessage ${existingIMessageChat.guid} to group ${group.id}")
                        }
                    } else {
                        // Group exists, just add this SMS chat to it
                        unifiedChatGroupDao.insertMember(
                            UnifiedChatMember(groupId = group.id, chatGuid = chat.guid)
                        )

                        // Update latestMessageDate if this chat has a more recent message
                        val chatLatestDate = chat.lastMessageDate ?: 0L
                        val groupLatestDate = group.latestMessageDate ?: 0L
                        if (chatLatestDate > groupLatestDate) {
                            unifiedChatGroupDao.updateLatestMessage(group.id, chatLatestDate, chat.lastMessageText)
                        }

                        Timber.d("repairOrphanedSmsChats: Added ${chat.guid} to existing group ${group.id}")
                    }

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
     * Repair unified group timestamps by updating latestMessageDate from member chats.
     * This fixes groups that have an outdated timestamp (e.g., when RCS messages were
     * received but the unified group timestamp wasn't updated).
     *
     * Call this on app startup to ensure conversation list is sorted correctly.
     */
    suspend fun repairUnifiedGroupTimestamps(): Int = withContext(Dispatchers.IO) {
        try {
            // Get all unified groups
            val allGroups = unifiedChatGroupDao.getAllGroups()
            var repairedCount = 0

            for (group in allGroups) {
                // Get all member chat GUIDs for this group
                val memberGuids = unifiedChatGroupDao.getChatGuidsForGroup(group.id)
                if (memberGuids.isEmpty()) continue

                // Find the latest message date across all member chats
                var latestDate = 0L
                var latestText: String? = null

                for (guid in memberGuids) {
                    val chat = chatDao.getChatByGuid(guid)
                    val chatDate = chat?.lastMessageDate ?: 0L
                    if (chatDate > latestDate) {
                        latestDate = chatDate
                        latestText = chat?.lastMessageText
                    }
                }

                // Update if the computed date is newer than the stored date
                val groupLatestDate = group.latestMessageDate ?: 0L
                if (latestDate > groupLatestDate) {
                    unifiedChatGroupDao.updateLatestMessage(group.id, latestDate, latestText)
                    Timber.d("repairUnifiedGroupTimestamps: Updated group ${group.id} from $groupLatestDate to $latestDate")
                    repairedCount++
                }
            }

            if (repairedCount > 0) {
                Timber.i("repairUnifiedGroupTimestamps: Repaired $repairedCount unified group timestamps")
            }

            repairedCount
        } catch (e: Exception) {
            Timber.e(e, "repairUnifiedGroupTimestamps: Error repairing unified group timestamps")
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
        // Also update the unified group's unread count for badge sync
        if (result.isSuccess) {
            unifiedChatGroupDao.getGroupForChat(chatGuid)?.let { group ->
                unifiedChatGroupDao.updateUnreadCount(group.id, 0)
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
