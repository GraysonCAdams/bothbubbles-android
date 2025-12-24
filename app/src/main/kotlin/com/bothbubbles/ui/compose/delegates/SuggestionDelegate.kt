package com.bothbubbles.ui.compose.delegates

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.ui.compose.RecipientSuggestion
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate managing recipient suggestions in the compose screen.
 *
 * Handles:
 * - Searching unified chats (shows nicknames like "💛 Liz")
 * - Loading contacts from Android contacts as fallback
 * - Loading group chats from database
 * - Filtering based on search query
 * - Deduplication (one row per contact per type - phone vs email)
 */
class SuggestionDelegate @Inject constructor(
    private val chatRepository: ChatRepository,
    private val handleRepository: HandleRepository,
    private val androidContactsService: AndroidContactsService,
    private val unifiedChatDao: UnifiedChatDao
) {
    private val _suggestions = MutableStateFlow<ImmutableList<RecipientSuggestion>>(persistentListOf())
    val suggestions: StateFlow<ImmutableList<RecipientSuggestion>> = _suggestions.asStateFlow()

    private val _showSuggestions = MutableStateFlow(false)
    val showSuggestions: StateFlow<Boolean> = _showSuggestions.asStateFlow()

    private val _selectedIndex = MutableStateFlow(0)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    private var allContacts: List<ContactEntry> = emptyList()
    private var allGroups: List<GroupEntry> = emptyList()
    private var lastMessageDates: Map<String, Long> = emptyMap()

    private var scope: CoroutineScope? = null

    private data class ContactEntry(
        val contactId: Long?,
        val displayName: String,
        val address: String,
        val normalizedAddress: String,
        val formattedAddress: String,
        val isEmail: Boolean,
        val avatarPath: String?,
        val lastMessageDate: Long?
    )

    private data class GroupEntry(
        val chatGuid: String,
        val unifiedChatId: String?,
        val displayName: String,
        val memberPreview: String,
        val avatarPath: String?
    )

    /**
     * Initialize the delegate with a coroutine scope.
     * Loads contacts and groups in the background.
     */
    @OptIn(FlowPreview::class)
    fun initialize(scope: CoroutineScope) {
        this.scope = scope

        // Load data in background
        scope.launch {
            loadContactsAndGroups()
        }
    }

    /**
     * Update suggestions based on search query.
     * Call this when the recipient input text changes.
     */
    fun updateQuery(query: String, allowGroups: Boolean) {
        val trimmedQuery = query.trim()

        if (trimmedQuery.isEmpty()) {
            _showSuggestions.value = false
            _suggestions.value = persistentListOf()
            _selectedIndex.value = 0
            return
        }

        _showSuggestions.value = true
        // Search unified groups async (they have proper nicknames)
        scope?.launch {
            filterSuggestionsAsync(trimmedQuery, allowGroups)
        }
    }

    /**
     * Hide the suggestions popup.
     */
    fun hideSuggestions() {
        _showSuggestions.value = false
    }

    /**
     * Move selection up in the suggestions list.
     */
    fun moveSelectionUp() {
        if (_suggestions.value.isEmpty()) return
        _selectedIndex.value = (_selectedIndex.value - 1).coerceAtLeast(0)
    }

    /**
     * Move selection down in the suggestions list.
     */
    fun moveSelectionDown() {
        if (_suggestions.value.isEmpty()) return
        _selectedIndex.value = (_selectedIndex.value + 1).coerceAtMost(_suggestions.value.size - 1)
    }

    /**
     * Get the currently selected suggestion, or null if none.
     */
    fun getSelectedSuggestion(): RecipientSuggestion? {
        val suggestions = _suggestions.value
        val index = _selectedIndex.value
        return if (index in suggestions.indices) suggestions[index] else null
    }

    private suspend fun loadContactsAndGroups() {
        try {
            // Load last message dates for deduplication
            lastMessageDates = chatRepository.getLastMessageDatePerAddress()

            // Load contacts
            val phoneContacts = androidContactsService.getAllContacts()
            val contactEntries = mutableListOf<ContactEntry>()

            for (contact in phoneContacts) {
                // Add phone numbers
                for (phone in contact.phoneNumbers) {
                    val normalized = normalizeAddress(phone)
                    contactEntries.add(
                        ContactEntry(
                            contactId = contact.contactId,
                            displayName = contact.displayName,
                            address = phone,
                            normalizedAddress = normalized,
                            formattedAddress = PhoneNumberFormatter.format(phone),
                            isEmail = false,
                            avatarPath = contact.photoUri,
                            lastMessageDate = lastMessageDates[normalized]
                        )
                    )
                }
                // Add emails
                for (email in contact.emails) {
                    val normalized = normalizeAddress(email)
                    contactEntries.add(
                        ContactEntry(
                            contactId = contact.contactId,
                            displayName = contact.displayName,
                            address = email,
                            normalizedAddress = normalized,
                            formattedAddress = email,
                            isEmail = true,
                            avatarPath = contact.photoUri,
                            lastMessageDate = lastMessageDates[normalized]
                        )
                    )
                }
            }

            // Deduplicate by contactId + type (phone vs email)
            // Each contact can appear once with phone and once with email
            allContacts = contactEntries
                .groupBy { Pair(it.contactId ?: it.normalizedAddress.hashCode().toLong(), it.isEmail) }
                .map { (_, group) ->
                    // Within each type, prefer most recently messaged
                    val withMessages = group.filter { it.lastMessageDate != null }
                    if (withMessages.isNotEmpty()) {
                        withMessages.maxByOrNull { it.lastMessageDate!! } ?: group.first()
                    } else {
                        group.first()
                    }
                }

            // Load group chats with participants
            val groupChats = chatRepository.getRecentGroupChats().first()
            val chatGuids = groupChats.map { it.guid }

            // Batch fetch participants for all groups
            val participantsByChat = if (chatGuids.isNotEmpty()) {
                chatRepository.getParticipantsGroupedByChat(chatGuids)
            } else {
                emptyMap()
            }

            // Build group entries with proper display names and avatars
            // Deduplicate by unifiedChatId
            val seenUnifiedIds = mutableSetOf<String>()
            allGroups = groupChats.mapNotNull { chat ->
                // Skip if we've already seen this unified chat
                val unifiedId = chat.unifiedChatId
                if (unifiedId != null && !seenUnifiedIds.add(unifiedId)) {
                    return@mapNotNull null
                }

                val participants = participantsByChat[chat.guid] ?: emptyList()

                // Get avatar from chat's server group photo or unified chat
                val avatarPath = chat.serverGroupPhotoPath
                    ?: unifiedId?.let { unifiedChatDao.getById(it)?.effectiveAvatarPath }

                // Build display name from participants if no explicit name
                val displayName = chat.displayName?.takeIf { it.isNotBlank() }
                    ?: buildParticipantDisplayName(participants)

                GroupEntry(
                    chatGuid = chat.guid,
                    unifiedChatId = unifiedId,
                    displayName = displayName,
                    memberPreview = buildMemberPreview(participants),
                    avatarPath = avatarPath
                )
            }

            Timber.d("Loaded ${allContacts.size} contacts and ${allGroups.size} groups")
        } catch (e: Exception) {
            Timber.e(e, "Failed to load contacts and groups")
        }
    }

    /**
     * Filter suggestions asynchronously, prioritizing unified chats with nicknames.
     *
     * Priority order:
     * 1. Unified chats (has nicknames like "💛 Liz", most recent chats)
     * 2. Android contacts (fallback for contacts without existing chats)
     * 3. Group chats (if allowed)
     */
    private suspend fun filterSuggestionsAsync(query: String, allowGroups: Boolean) {
        val lowerQuery = query.lowercase()
        val results = mutableListOf<RecipientSuggestion>()
        val matchedIdentifiers = mutableSetOf<String>()

        // 1. Search unified chats first - they have proper nicknames
        try {
            Timber.d("Searching unified chats for query: '$query'")
            val unifiedChats = unifiedChatDao.search(query, MAX_UNIFIED_SUGGESTIONS)
            Timber.d("Found ${unifiedChats.size} unified chats")
            for (chat in unifiedChats) {
                // Skip group chats from unified search - they're handled separately
                if (chat.isGroup) continue

                matchedIdentifiers.add(chat.normalizedAddress)

                // Look up handle for avatar (prefer any handle with avatar)
                val handles = handleRepository.getHandlesByAddress(chat.normalizedAddress)
                val handle = handles.firstOrNull { it.cachedAvatarPath != null } ?: handles.firstOrNull()

                // Get display name from:
                // 1. Unified chat displayName
                // 2. Handle's cachedDisplayName (contact nickname like "💛 Liz")
                // 3. Formatted phone number as fallback
                val displayName = chat.displayName
                    ?: handle?.cachedDisplayName
                    ?: PhoneNumberFormatter.format(chat.normalizedAddress)

                Timber.d("Unified chat: identifier=${chat.normalizedAddress}, displayName=$displayName")

                results.add(
                    RecipientSuggestion.Contact(
                        contactId = null,
                        displayName = displayName,
                        address = chat.normalizedAddress,
                        formattedAddress = PhoneNumberFormatter.format(chat.normalizedAddress),
                        service = "", // No longer used
                        avatarPath = chat.effectiveAvatarPath ?: handle?.cachedAvatarPath,
                        chatGuid = chat.sourceId
                    )
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to search unified chats")
        }

        // 2. Filter contacts that don't already have unified chats
        val matchingContacts = allContacts
            .filter { contact ->
                // Skip if already matched from unified chats
                !matchedIdentifiers.contains(contact.normalizedAddress) &&
                (contact.displayName.lowercase().contains(lowerQuery) ||
                    contact.address.contains(query, ignoreCase = true) ||
                    contact.formattedAddress.contains(query, ignoreCase = true))
            }
            // Deduplicate by contactId + type
            .groupBy { Pair(it.contactId ?: it.normalizedAddress.hashCode().toLong(), it.isEmail) }
            .map { (_, group) ->
                val withMessages = group.filter { it.lastMessageDate != null }
                if (withMessages.isNotEmpty()) {
                    withMessages.maxByOrNull { it.lastMessageDate!! } ?: group.first()
                } else {
                    group.first()
                }
            }
            .take(MAX_CONTACT_SUGGESTIONS - results.size.coerceAtMost(MAX_CONTACT_SUGGESTIONS))

        results.addAll(matchingContacts.map { contact ->
            RecipientSuggestion.Contact(
                contactId = contact.contactId,
                displayName = contact.displayName,
                address = contact.address,
                formattedAddress = contact.formattedAddress,
                service = "", // No longer used
                avatarPath = contact.avatarPath
            )
        })

        // 3. Filter groups (only if allowed)
        if (allowGroups) {
            val matchingGroups = allGroups
                .filter { group ->
                    group.displayName.lowercase().contains(lowerQuery) ||
                        group.memberPreview.lowercase().contains(lowerQuery)
                }
                .take(MAX_GROUP_SUGGESTIONS)

            results.addAll(matchingGroups.map { group ->
                RecipientSuggestion.Group(
                    chatGuid = group.chatGuid,
                    displayName = group.displayName,
                    memberPreview = group.memberPreview,
                    avatarPath = group.avatarPath
                )
            })
        }

        Timber.d("Final suggestions: ${results.size} items")
        results.forEach { suggestion ->
            when (suggestion) {
                is RecipientSuggestion.Contact -> Timber.d("  Contact: ${suggestion.displayName} (${suggestion.address})")
                is RecipientSuggestion.Group -> Timber.d("  Group: ${suggestion.displayName} (${suggestion.chatGuid})")
            }
        }
        _suggestions.value = results.toImmutableList()
        _selectedIndex.value = 0
    }

    private fun normalizeAddress(address: String): String {
        return if (address.contains("@")) {
            address.lowercase()
        } else {
            // Use proper phone normalization that adds country code for consistency
            // This ensures "7709057960" matches "+17709057960" in the handle map
            PhoneAndCodeParsingUtils.normalizePhoneNumber(address)
        }
    }

    /**
     * Build display name for a group chat from participant names.
     * Format: "Name1, Name2, +N others" to fit in a single line.
     */
    private fun buildParticipantDisplayName(participants: List<HandleEntity>): String {
        if (participants.isEmpty()) return "Group Chat"

        val names = participants.map { it.displayName }
        return when {
            names.size <= 2 -> names.joinToString(", ")
            else -> {
                // Show first 2 names + others count
                val firstTwo = names.take(2).joinToString(", ")
                val othersCount = names.size - 2
                "$firstTwo, +$othersCount others"
            }
        }
    }

    /**
     * Build member preview for a group chat (shown as subtitle).
     * Shows as many names as possible with "+N others" format.
     */
    private fun buildMemberPreview(participants: List<HandleEntity>): String {
        if (participants.isEmpty()) return "No members"

        val names = participants.map { it.displayName }
        return when {
            names.size <= 3 -> names.joinToString(", ")
            else -> {
                val firstThree = names.take(3).joinToString(", ")
                val othersCount = names.size - 3
                "$firstThree, +$othersCount others"
            }
        }
    }

    companion object {
        private const val MAX_UNIFIED_SUGGESTIONS = 5 // Unified groups with nicknames (priority)
        private const val MAX_CONTACT_SUGGESTIONS = 5 // Android contacts fallback
        private const val MAX_GROUP_SUGGESTIONS = 3   // Multi-participant group chats
    }
}
