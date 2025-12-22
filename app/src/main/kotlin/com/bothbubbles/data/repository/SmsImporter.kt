package com.bothbubbles.data.repository

import com.bothbubbles.data.local.db.dao.ChatDao
import timber.log.Timber
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.ChatHandleCrossRef
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
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
    private val unifiedChatDao: UnifiedChatDao,
    private val unifiedChatRepository: UnifiedChatRepository,
    private val smsContentProvider: SmsContentProvider,
    private val androidContactsService: AndroidContactsService
) {

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
            Timber.d("Getting SMS threads (limit=$limit)...")
            val threads = smsContentProvider.getThreads(limit = limit)
            Timber.d("Found ${threads.size} SMS threads to import")
            var imported = 0

            // If there are no threads, report completion immediately
            if (threads.isEmpty()) {
                onProgress?.invoke(0, 0)
            }

            threads.forEachIndexed { index, thread ->
                importThread(thread)
                imported++
                onProgress?.invoke(index + 1, threads.size)
            }

            Timber.d("SMS import finished: $imported threads imported")
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
            Timber.d("Skipping thread ${thread.threadId} - no valid phone addresses")
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

        // For single contacts (not groups), link to unified chat
        val validAddresses = addresses.filter { it.isNotBlank() }
        if (!isGroup && validAddresses.isNotEmpty()) {
            val normalizedPhone = validAddresses.first()
            linkChatToUnifiedChat(chatGuid, normalizedPhone)
        }

        // Import the most recent message so the badge displays correctly in conversation list
        importLatestMessageForThread(thread.threadId, chatGuid, isGroup)

        // Update unified chat's latestMessageDate if applicable
        if (!isGroup && validAddresses.isNotEmpty()) {
            val normalizedPhone = validAddresses.first()
            val unifiedChat = unifiedChatDao.getByNormalizedAddress(normalizedPhone)
            if (unifiedChat != null) {
                // Update latestMessageDate if this thread's message is newer
                unifiedChatRepository.updateLatestMessageIfNewer(
                    id = unifiedChat.id,
                    date = thread.lastMessageDate,
                    text = thread.snippet,
                    guid = null,
                    isFromMe = false,
                    hasAttachments = false,
                    source = "LOCAL_SMS"
                )
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
     * Link a chat to a unified chat, creating the unified chat if necessary.
     * This enables merging SMS and iMessage conversations for the same contact.
     */
    private suspend fun linkChatToUnifiedChat(chatGuid: String, normalizedPhone: String) {
        // Skip if identifier is empty or looks like an email/RCS address (not a phone)
        // This prevents all non-phone chats from being lumped into a single unified chat
        if (normalizedPhone.isBlank() || normalizedPhone.contains("@")) {
            Timber.d("linkChatToUnifiedChat: Skipping $chatGuid - invalid identifier '$normalizedPhone'")
            return
        }

        Timber.d("linkChatToUnifiedChat: Linking $chatGuid to unified chat for '$normalizedPhone'")

        try {
            // Check if there's an existing iMessage chat for this phone to determine source ID
            val existingIMessageChat = findIMessageChatForPhone(normalizedPhone)
            Timber.d("linkChatToUnifiedChat: Found existing iMessage chat: ${existingIMessageChat?.guid}")

            // Prefer iMessage as source if available
            val sourceId = existingIMessageChat?.guid ?: chatGuid
            val displayName = existingIMessageChat?.displayName

            // Get or create unified chat for this phone number
            val unifiedChat = unifiedChatRepository.getOrCreate(
                address = normalizedPhone,
                sourceId = sourceId,
                displayName = displayName,
                isGroup = false
            )
            Timber.d("linkChatToUnifiedChat: Got unified chat id=${unifiedChat.id}")

            // Link this SMS chat to the unified chat
            chatDao.setUnifiedChatId(chatGuid, unifiedChat.id)

            // Also link the iMessage chat if found and not already linked
            if (existingIMessageChat != null && existingIMessageChat.guid != chatGuid) {
                if (existingIMessageChat.unifiedChatId == null) {
                    chatDao.setUnifiedChatId(existingIMessageChat.guid, unifiedChat.id)
                    messageDao.setUnifiedChatIdForChat(existingIMessageChat.guid, unifiedChat.id)
                    Timber.d("linkChatToUnifiedChat: Also linked iMessage ${existingIMessageChat.guid} to unified chat ${unifiedChat.id}")
                }
            }

            // Update messages' unified_chat_id for this chat
            messageDao.setUnifiedChatIdForChat(chatGuid, unifiedChat.id)

            Timber.d("linkChatToUnifiedChat: Successfully linked $chatGuid to unified chat ${unifiedChat.id}")
        } catch (e: Exception) {
            Timber.e(e, "linkChatToUnifiedChat: FAILED to link $chatGuid to unified chat for '$normalizedPhone'")
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
