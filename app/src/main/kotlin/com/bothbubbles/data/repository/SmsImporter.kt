package com.bothbubbles.data.repository

import android.util.Log
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.ChatHandleCrossRef
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatMember
import com.bothbubbles.data.repository.SmsContentProviderHelpers.isValidPhoneAddress
import com.bothbubbles.data.repository.SmsContentProviderHelpers.phonesMatch
import com.bothbubbles.data.repository.SmsContentProviderHelpers.toMessageEntity
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.sms.SmsContentProvider
import com.bothbubbles.services.sms.SmsThread
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles importing SMS/MMS threads and messages from the system content provider
 * into the app's database.
 */
class SmsImporter(
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val messageDao: MessageDao,
    private val unifiedChatGroupDao: UnifiedChatGroupDao,
    private val smsContentProvider: SmsContentProvider,
    private val androidContactsService: AndroidContactsService
) {
    companion object {
        private const val TAG = "SmsImporter"
    }

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
    suspend fun importThread(thread: SmsThread) {
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
                    latestSms.toMessageEntity(chatGuid)
                } else {
                    latestMms.toMessageEntity(chatGuid)
                }
            }
            latestSms != null -> latestSms.toMessageEntity(chatGuid)
            latestMms != null -> latestMms.toMessageEntity(chatGuid)
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
     * PERF: Uses batch query to fetch all participants in a single database call.
     */
    private suspend fun findIMessageChatForPhone(normalizedPhone: String): ChatEntity? {
        val nonGroupChats = chatDao.getAllNonGroupIMessageChats()
        if (nonGroupChats.isEmpty()) return null

        // PERF: Batch fetch all participants for all chats in a single query
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
     * Import messages for a specific chat
     * @param chatGuid The chat GUID
     * @param limit Maximum number of messages to import
     */
    suspend fun importMessagesForChat(
        chatGuid: String,
        limit: Int = 100,
        getThreadIdForChat: suspend (String) -> Long?
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
}
