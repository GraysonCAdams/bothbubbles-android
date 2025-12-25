package com.bothbubbles.ui.chatcreator.delegates

import timber.log.Timber
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.data.repository.PopularChatsRepository
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.contacts.PhoneContact
import com.bothbubbles.ui.chatcreator.ContactUiModel
import com.bothbubbles.ui.chatcreator.GroupChatUiModel
import com.bothbubbles.ui.chatcreator.PopularChatUiModel
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Delegate responsible for loading and organizing contacts from various sources.
 *
 * This delegate handles:
 * - Loading contacts from Android contacts service
 * - Enriching contacts with iMessage availability from cached handles
 * - Marking recent, popular, and favorite contacts
 * - Loading and filtering group chats
 *
 * Popular contacts are determined by message frequency in the last 30 days,
 * using the [PopularChatsRepository] for engagement-based suggestions.
 */
class ContactLoadDelegate @Inject constructor(
    private val handleRepository: HandleRepository,
    private val chatRepository: ChatRepository,
    private val popularChatsRepository: PopularChatsRepository,
    private val androidContactsService: AndroidContactsService
) {
    /**
     * Data class containing loaded contacts data
     */
    data class ContactsData(
        /** Top 5 popular chats (1:1 + groups mixed) based on message engagement */
        val popularChats: List<PopularChatUiModel>,
        /** Alphabetically grouped contacts */
        val grouped: Map<String, List<ContactUiModel>>,
        /** Starred/favorite contacts */
        val favorites: List<ContactUiModel>,
        /** Group chats available to join */
        val groupChats: List<GroupChatUiModel>,
        /** Current search query */
        val query: String,
        /** Whether app has contacts permission */
        val hasContactsPermission: Boolean
    )

    /**
     * Check if the app has READ_CONTACTS permission.
     */
    fun hasContactsPermission(): Boolean = androidContactsService.hasReadPermission()

    /**
     * Load all contacts and observe search query changes.
     * Returns a Flow that emits ContactsData whenever the search query changes.
     *
     * The "popular" section shows the top 5 chats (1:1 and groups) based on
     * message engagement in the last 30 days.
     *
     * Deduplication behavior:
     * - When NOT searching: Show one row per contact (Android contactId), using the
     *   most recently used handle based on last message date
     * - When searching: Show all handles for matching contacts (phone, email, etc.)
     */
    fun observeContacts(
        searchQueryFlow: Flow<String>,
        scope: CoroutineScope
    ): Flow<ContactsData> = flow {
        // Check permission first
        val hasPermission = androidContactsService.hasReadPermission()

        // Load contacts from phone contacts (not from handles)
        val phoneContacts = androidContactsService.getAllContacts()

        // Build a lookup map for service based on actual conversation activity
        // Priority: Use service from most recent chat that HAS messages
        // Fallback: SMS for phones, iMessage for emails (conservative defaults)
        val activityServiceMap = chatRepository.getServiceMapFromActiveChats()

        val handleServiceMap = mutableMapOf<String, String>()
        handleRepository.getAllHandlesOnce().forEach { handle ->
            val normalized = normalizeAddress(handle.address)

            // Priority 1: Use service from most recent ACTIVE chat (has messages)
            val activityService = activityServiceMap[normalized]
            if (activityService != null) {
                handleServiceMap[normalized] = activityService
            }
            // Priority 2: For handles with no active chat, use the service from the handle
            // The handle's service field comes from the server and indicates iMessage capability
            else if (!handleServiceMap.containsKey(normalized)) {
                handleServiceMap[normalized] = handle.service
            }
        }

        // Get last message dates per address for deduplication
        val lastMessageDates = chatRepository.getLastMessageDatePerAddress()

        // Get popular chats (1:1 + groups, top 5)
        val popularChats = popularChatsRepository.getPopularChats(limit = MAX_POPULAR_CHATS)

        // Convert phone contacts to ContactUiModel entries
        // Each contact can have multiple phone numbers and emails
        val allContacts = mutableListOf<ContactUiModel>()
        for (contact in phoneContacts) {
            // Add phone numbers
            for (phone in contact.phoneNumbers) {
                val normalized = normalizeAddress(phone)
                val service = handleServiceMap[normalized] ?: "SMS"
                allContacts.add(
                    ContactUiModel(
                        address = phone,
                        normalizedAddress = normalized,
                        formattedAddress = formatPhoneNumber(phone),
                        displayName = contact.displayName,
                        service = service,
                        avatarPath = contact.photoUri,
                        isFavorite = contact.isStarred,
                        isRecent = false,
                        isPopular = false,
                        contactId = contact.contactId,
                        lastMessageDate = lastMessageDates[normalized]
                    )
                )
            }
            // Add emails
            for (email in contact.emails) {
                val normalized = normalizeAddress(email)
                allContacts.add(
                    ContactUiModel(
                        address = email,
                        normalizedAddress = normalized,
                        formattedAddress = email,
                        displayName = contact.displayName,
                        service = "iMessage", // Emails are always iMessage
                        avatarPath = contact.photoUri,
                        isFavorite = contact.isStarred,
                        isRecent = false,
                        isPopular = false,
                        contactId = contact.contactId,
                        lastMessageDate = lastMessageDates[normalized]
                    )
                )
            }
        }

        // De-duplicate by normalized address (for same-address duplicates)
        val addressDeduped = allContacts
            .groupBy { it.normalizedAddress }
            .map { (_, group) ->
                // Prefer iMessage handle when both exist for same address
                group.find { it.service == "iMessage" } ?: group.first()
            }

        // Observe search query and group chats
        combine(
            searchQueryFlow.flatMapLatest { query ->
                if (query.isNotBlank()) {
                    chatRepository.searchGroupChats(query)
                } else {
                    chatRepository.getRecentGroupChats()
                }
            },
            searchQueryFlow
        ) { groupChats, query ->
            // Deduplication strategy depends on whether user is searching
            val filtered = if (query.isNotBlank()) {
                // SEARCHING: Show all handles for matching contacts (not deduplicated by contact)
                // This allows users to choose specific phone numbers/emails
                addressDeduped.filter { contact ->
                    contact.displayName.contains(query, ignoreCase = true) ||
                        contact.address.contains(query, ignoreCase = true) ||
                        contact.formattedAddress.contains(query, ignoreCase = true)
                }
            } else {
                // NOT SEARCHING: Deduplicate by contactId, pick most recently used handle
                // This shows one row per person using the handle they last messaged with
                deduplicateByContact(addressDeduped)
            }

            // Convert popular chats to UI models (only show when not searching)
            val popularChatModels = if (query.isBlank()) {
                popularChats.map { chat ->
                    val service = if (!chat.isGroup && chat.identifier != null) {
                        handleServiceMap[normalizeAddress(chat.identifier)] ?: "SMS"
                    } else {
                        ""  // Groups don't have a service indicator
                    }
                    PopularChatUiModel(
                        chatGuid = chat.chatGuid,
                        displayName = chat.displayName,
                        isGroup = chat.isGroup,
                        avatarPath = chat.avatarPath,
                        service = service,
                        identifier = chat.identifier
                    )
                }
            } else {
                emptyList()
            }

            // Group all contacts by first letter of display name (excluding favorites)
            val grouped = filtered
                .filter { !it.isFavorite }
                .sortedBy { it.displayName.uppercase() }
                .groupBy { contact ->
                    val firstChar = contact.displayName.firstOrNull()?.uppercaseChar() ?: '#'
                    if (firstChar.isLetter()) firstChar.toString() else "#"
                }
                .toSortedMap()

            val favorites = filtered.filter { it.isFavorite }.sortedBy { it.displayName.uppercase() }

            // Convert group chats to UI model
            val groupChatModels = groupChats.map { it.toGroupChatUiModel() }

            // Return all data
            ContactsData(popularChatModels, grouped, favorites, groupChatModels, query, hasPermission)
        }.collect { data ->
            emit(data)
        }
    }

    /**
     * Deduplicate contacts by Android contactId.
     * For each contact (person), picks the handle (phone/email) that was most recently used.
     * Falls back to iMessage preference, then first in list if no message history.
     */
    private fun deduplicateByContact(contacts: List<ContactUiModel>): List<ContactUiModel> {
        return contacts
            .groupBy { it.contactId ?: it.normalizedAddress.hashCode().toLong() }
            .map { (_, group) ->
                // Pick the handle with the most recent message date
                val withMessages = group.filter { it.lastMessageDate != null }
                if (withMessages.isNotEmpty()) {
                    withMessages.maxByOrNull { it.lastMessageDate!! }!!
                } else {
                    // No message history - prefer iMessage, then first
                    group.find { it.service == "iMessage" } ?: group.first()
                }
            }
    }

    companion object {
        /** Maximum number of popular chats (1:1 + groups) to show */
        private const val MAX_POPULAR_CHATS = 5
    }

    /**
     * Format a phone number for display.
     */
    private fun formatPhoneNumber(phone: String): String {
        return PhoneNumberFormatter.format(phone)
    }

    /**
     * Normalize an address for de-duplication purposes.
     * Strips non-essential characters from phone numbers, lowercases emails.
     */
    private fun normalizeAddress(address: String): String {
        return if (address.contains("@")) {
            // Email - just lowercase
            address.lowercase()
        } else {
            // Phone number - strip non-digits except leading +
            address.replace(Regex("[^0-9+]"), "")
        }
    }

    /**
     * Convert ChatEntity to GroupChatUiModel
     */
    private fun ChatEntity.toGroupChatUiModel(): GroupChatUiModel {
        val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        val dateFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

        val formattedTime = latestMessageDate?.let { timestamp ->
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            val oneDayMs = 24 * 60 * 60 * 1000L
            val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())

            when {
                diff < oneDayMs -> dateTime.format(timeFormatter)
                diff < 7 * oneDayMs -> dateTime.format(dateFormatter)
                else -> dateTime.format(dateFormatter)
            }
        }

        return GroupChatUiModel(
            guid = guid,
            displayName = displayName ?: chatIdentifier ?: "Group Chat",
            lastMessage = null,
            lastMessageTime = formattedTime,
            avatarPath = null, // Group photo now stored in UnifiedChatEntity
            participantCount = 0 // Could be populated from cross-ref count
        )
    }
}
