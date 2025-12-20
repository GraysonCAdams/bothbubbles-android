package com.bothbubbles.ui.chatcreator.delegates

import timber.log.Timber
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.contacts.PhoneContact
import com.bothbubbles.ui.chatcreator.ContactUiModel
import com.bothbubbles.ui.chatcreator.GroupChatUiModel
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
 * - Marking recent and favorite contacts
 * - Loading and filtering group chats
 */
class ContactLoadDelegate @Inject constructor(
    private val handleRepository: HandleRepository,
    private val chatRepository: ChatRepository,
    private val androidContactsService: AndroidContactsService
) {
    /**
     * Data class containing loaded contacts data
     */
    data class ContactsData(
        val recent: List<ContactUiModel>,
        val grouped: Map<String, List<ContactUiModel>>,
        val favorites: List<ContactUiModel>,
        val groupChats: List<GroupChatUiModel>,
        val query: String,
        val hasContactsPermission: Boolean
    )

    /**
     * Check if the app has READ_CONTACTS permission.
     */
    fun hasContactsPermission(): Boolean = androidContactsService.hasReadPermission()

    /**
     * Load all contacts and observe search query changes.
     * Returns a Flow that emits ContactsData whenever the search query changes.
     */
    fun observeContacts(
        searchQueryFlow: Flow<String>,
        scope: CoroutineScope
    ): Flow<ContactsData> = flow {
        // Check permission first
        val hasPermission = androidContactsService.hasReadPermission()

        // Load contacts from phone contacts (not from handles)
        val phoneContacts = androidContactsService.getAllContacts()

        // Build a lookup map for iMessage availability from cached handles
        val handleServiceMap = mutableMapOf<String, String>()
        handleRepository.getAllHandlesOnce().forEach { handle ->
            val normalized = normalizeAddress(handle.address)
            // Prefer iMessage when we know it's available
            if (handle.isIMessage || !handleServiceMap.containsKey(normalized)) {
                handleServiceMap[normalized] = handle.service
            }
        }

        // Get recent addresses from handle cross-references
        val recentHandles = handleRepository.getRecentContacts().first()
        val recentAddresses = recentHandles.map { normalizeAddress(it.address) }.toSet()

        // Convert phone contacts to ContactUiModel entries
        // Each contact can have multiple phone numbers and emails
        val allContacts = mutableListOf<ContactUiModel>()
        for (contact in phoneContacts) {
            // Add phone numbers
            for (phone in contact.phoneNumbers) {
                val normalized = normalizeAddress(phone)
                val service = handleServiceMap[normalized] ?: "SMS"
                val isRecent = recentAddresses.contains(normalized)
                allContacts.add(
                    ContactUiModel(
                        address = phone,
                        normalizedAddress = normalized,
                        formattedAddress = formatPhoneNumber(phone),
                        displayName = contact.displayName,
                        service = service,
                        avatarPath = contact.photoUri,
                        isFavorite = contact.isStarred,
                        isRecent = isRecent
                    )
                )
            }
            // Add emails
            for (email in contact.emails) {
                val normalized = normalizeAddress(email)
                val isRecent = recentAddresses.contains(normalized)
                allContacts.add(
                    ContactUiModel(
                        address = email,
                        normalizedAddress = normalized,
                        formattedAddress = email,
                        displayName = contact.displayName,
                        service = "iMessage", // Emails are always iMessage
                        avatarPath = contact.photoUri,
                        isFavorite = contact.isStarred,
                        isRecent = isRecent
                    )
                )
            }
        }

        // De-duplicate by normalized address
        val deduped = allContacts
            .groupBy { it.normalizedAddress }
            .map { (_, group) ->
                // Prefer iMessage handle when both exist
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
            // Filter by search query
            val filtered = if (query.isNotBlank()) {
                deduped.filter { contact ->
                    contact.displayName.contains(query, ignoreCase = true) ||
                        contact.address.contains(query, ignoreCase = true) ||
                        contact.formattedAddress.contains(query, ignoreCase = true)
                }
            } else {
                deduped
            }

            // Split into recent (up to 4) and rest
            val recent = filtered
                .filter { it.isRecent }
                .take(4)
            val recentNormalizedAddresses = recent.map { it.normalizedAddress }.toSet()

            // Rest excludes the recent ones we're showing at the top
            val rest = filtered.filter { !recentNormalizedAddresses.contains(it.normalizedAddress) }

            // Group rest by first letter of display name (excluding favorites)
            val grouped = rest
                .filter { !it.isFavorite }
                .sortedBy { it.displayName.uppercase() }
                .groupBy { contact ->
                    val firstChar = contact.displayName.firstOrNull()?.uppercaseChar() ?: '#'
                    if (firstChar.isLetter()) firstChar.toString() else "#"
                }
                .toSortedMap()

            val favorites = rest.filter { it.isFavorite }.sortedBy { it.displayName.uppercase() }

            // Convert group chats to UI model
            val groupChatModels = groupChats.map { it.toGroupChatUiModel() }

            // Return all data
            ContactsData(recent, grouped, favorites, groupChatModels, query, hasPermission)
        }.collect { data ->
            emit(data)
        }
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

        val formattedTime = lastMessageDate?.let { timestamp ->
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
            lastMessage = lastMessageText,
            lastMessageTime = formattedTime,
            avatarPath = customAvatarPath,
            participantCount = 0 // Could be populated from cross-ref count
        )
    }
}
