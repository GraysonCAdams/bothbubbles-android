package com.bothbubbles.data.repository

import com.bothbubbles.data.local.db.dao.ChatDao
import timber.log.Timber
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.core.model.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.ChatHandleCrossRef
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.repository.SmsContentProviderHelpers.determineChatGuidForMms
import com.bothbubbles.data.repository.SmsContentProviderHelpers.determineChatGuidForSms
import com.bothbubbles.data.repository.SmsContentProviderHelpers.getValidMmsRecipients
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
     * Import a single SMS thread.
     * Only imports threads that have at least one message - empty threads are skipped.
     *
     * IMPORTANT: Uses per-message chat_guid determination instead of thread-level addresses.
     * This fixes the bug where 1:1 SMS messages were incorrectly assigned to group chats
     * when the thread contained both 1:1 and group messages.
     */
    suspend fun importThread(thread: SmsThread) {
        // IMPORTANT: Check for messages FIRST before creating any records
        // Skip empty threads entirely - don't create chats without messages
        val smsMessages = smsContentProvider.getSmsMessages(thread.threadId, limit = 1)
        val mmsMessages = smsContentProvider.getMmsMessages(thread.threadId, limit = 1)
        val latestSms = smsMessages.firstOrNull()
        val latestMms = mmsMessages.firstOrNull()

        if (latestSms == null && latestMms == null) {
            Timber.d("Skipping thread ${thread.threadId} - no messages found")
            return
        }

        // Process each message with its per-message chat_guid
        // This is the key fix: we determine chat_guid from the MESSAGE's address/addresses,
        // not from thread-level addresses which can be incorrect for mixed threads

        // Process latest SMS if present
        latestSms?.let { sms ->
            val smsChatGuid = determineChatGuidForSms(sms.address)
            if (smsChatGuid != null) {
                ensureChatExists(smsChatGuid, sms.address, sms.date)
                val messageEntity = sms.toMessageEntity(smsChatGuid)
                messageDao.insertOrUpdateMessage(messageEntity)

                // Update unified chat for 1:1 SMS
                updateUnifiedChatForMessage(smsChatGuid, sms.address, sms.date, messageEntity, thread.snippet)
            } else {
                Timber.d("Skipping SMS ${sms.id} - invalid address '${sms.address}'")
            }
        }

        // Process latest MMS if present (only if it's newer than SMS or SMS doesn't exist)
        latestMms?.let { mms ->
            // Only process MMS if it's the latest message or no SMS exists
            val shouldProcessMms = latestSms == null || mms.date >= latestSms.date

            if (shouldProcessMms) {
                val mmsChatGuid = determineChatGuidForMms(mms.addresses)
                if (mmsChatGuid != null) {
                    // Get valid recipient addresses as strings for chat creation
                    val validRecipients = getValidMmsRecipients(mms.addresses)
                    ensureChatExistsForMms(mmsChatGuid, validRecipients, mms.date)
                    val messageEntity = mms.toMessageEntity(mmsChatGuid)
                    messageDao.insertOrUpdateMessage(messageEntity)

                    // Update unified chat for 1:1 MMS (groups don't use unified chats for merging)
                    if (!mmsChatGuid.startsWith("mms;-;")) {
                        val primaryAddress = validRecipients.firstOrNull()
                        if (primaryAddress != null) {
                            updateUnifiedChatForMessage(mmsChatGuid, primaryAddress, mms.date, messageEntity, thread.snippet)
                        }
                    }
                } else {
                    Timber.d("Skipping MMS ${mms.id} - no valid addresses")
                }
            }
        }
    }

    /**
     * Ensure a chat record exists for a 1:1 SMS conversation.
     */
    private suspend fun ensureChatExists(chatGuid: String, address: String, messageDate: Long) {
        val normalizedAddress = PhoneAndCodeParsingUtils.normalizePhoneNumber(address)
        if (normalizedAddress.isBlank()) return

        val existingChat = chatDao.getChatByGuid(chatGuid)
        if (existingChat == null) {
            val chat = ChatEntity(
                guid = chatGuid,
                chatIdentifier = normalizedAddress,
                displayName = null,
                isGroup = false,
                latestMessageDate = messageDate
            )
            chatDao.insertChat(chat)
        }

        // Create handle and link to chat
        createAndLinkHandle(chatGuid, normalizedAddress)
    }

    /**
     * Ensure a chat record exists for an MMS conversation (1:1 or group).
     */
    private suspend fun ensureChatExistsForMms(chatGuid: String, addresses: List<String>, messageDate: Long) {
        val validAddresses = addresses
            .filter { isValidPhoneAddress(it) }
            .map { PhoneAndCodeParsingUtils.normalizePhoneNumber(it) }
            .filter { it.isNotBlank() }
            .distinct()

        if (validAddresses.isEmpty()) return

        val isGroup = validAddresses.size > 1

        val existingChat = chatDao.getChatByGuid(chatGuid)
        if (existingChat == null) {
            val chat = ChatEntity(
                guid = chatGuid,
                chatIdentifier = if (isGroup) null else validAddresses.first(),
                displayName = null,
                isGroup = isGroup,
                latestMessageDate = messageDate
            )
            chatDao.insertChat(chat)
        }

        // Create handles and link to chat
        validAddresses.forEach { address ->
            createAndLinkHandle(chatGuid, address)
        }
    }

    /**
     * Create a handle entity and link it to a chat.
     */
    private suspend fun createAndLinkHandle(chatGuid: String, normalizedAddress: String) {
        val contactName = androidContactsService.getContactDisplayName(normalizedAddress)
        val contactPhotoUri = androidContactsService.getContactPhotoUri(normalizedAddress)
        val formattedAddress = PhoneNumberFormatter.format(normalizedAddress)

        val handle = HandleEntity(
            address = normalizedAddress,
            formattedAddress = formattedAddress,
            service = "SMS",
            cachedDisplayName = contactName,
            cachedAvatarPath = contactPhotoUri
        )
        val handleId = handleDao.upsertHandle(handle)
        chatDao.insertChatHandleCrossRef(ChatHandleCrossRef(chatGuid, handleId))
    }

    /**
     * Update unified chat for a 1:1 message.
     */
    private suspend fun updateUnifiedChatForMessage(
        chatGuid: String,
        normalizedAddress: String,
        messageDate: Long,
        messageEntity: MessageEntity,
        threadSnippet: String?
    ) {
        // Link chat to unified chat
        linkChatToUnifiedChat(chatGuid, normalizedAddress)

        // Update unified chat's latest message info
        val unifiedChat = unifiedChatDao.getByNormalizedAddress(normalizedAddress)
        if (unifiedChat != null) {
            val snippet = messageEntity.text ?: threadSnippet
            unifiedChatRepository.updateLatestMessageIfNewer(
                id = unifiedChat.id,
                date = messageDate,
                text = snippet,
                guid = messageEntity.guid,
                isFromMe = messageEntity.isFromMe,
                hasAttachments = messageEntity.hasAttachments,
                source = "LOCAL_SMS"
            )
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
     * Import messages for a specific chat/thread.
     *
     * IMPORTANT: Uses per-message chat_guid determination instead of the passed-in chatGuid.
     * This fixes the bug where messages in mixed threads were assigned incorrect chat_guids.
     *
     * @param chatGuid The original chat GUID (used to find thread, but NOT for message assignment)
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

            // Import SMS messages with per-message chat_guid
            val smsMessages = smsContentProvider.getSmsMessages(threadId, limit = limit)
            smsMessages.forEach { sms ->
                val guid = "sms-${sms.id}"
                if (messageDao.getMessageByGuid(guid) == null) {
                    // Determine the correct chat_guid from THIS message's address
                    val perMessageChatGuid = determineChatGuidForSms(sms.address) ?: return@forEach

                    // Ensure the chat exists for this message
                    ensureChatExists(perMessageChatGuid, sms.address, sms.date)

                    val entity = sms.toMessageEntity(perMessageChatGuid)
                    messageDao.insertMessage(entity)
                    imported++
                }
            }

            // Import MMS messages with per-message chat_guid
            val mmsMessages = smsContentProvider.getMmsMessages(threadId, limit = limit)
            mmsMessages.forEach { mms ->
                val guid = "mms-${mms.id}"
                if (messageDao.getMessageByGuid(guid) == null) {
                    // Determine the correct chat_guid from THIS message's addresses
                    val perMessageChatGuid = determineChatGuidForMms(mms.addresses) ?: return@forEach

                    // Ensure the chat exists for this message
                    val validRecipients = getValidMmsRecipients(mms.addresses)
                    ensureChatExistsForMms(perMessageChatGuid, validRecipients, mms.date)

                    val entity = mms.toMessageEntity(perMessageChatGuid)

                    // Check for duplicate SMS message with matching content and timestamp
                    // This prevents the same message from appearing twice when recorded as both SMS and MMS
                    val textContent = entity.text
                    if (textContent != null) {
                        val matchingMessage = messageDao.findMatchingMessage(
                            chatGuid = perMessageChatGuid,
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
