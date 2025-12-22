package com.bothbubbles.ui.compose.delegates

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.core.model.entity.UnifiedChatEntity
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * - Deduplication (one row per contact, most recently used handle)
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
    private var handleServiceMap: Map<String, String> = emptyMap()
    private var lastMessageDates: Map<String, Long> = emptyMap()

    private var scope: CoroutineScope? = null

    private data class ContactEntry(
        val contactId: Long?,
        val displayName: String,
        val address: String,
        val normalizedAddress: String,
        val formattedAddress: String,
        val service: String,
        val avatarPath: String?,
        val lastMessageDate: Long?
    )

    private data class GroupEntry(
        val chatGuid: String,
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
            // Load activity-based service map (most recent chat with messages wins)
            val activityServiceMap = chatRepository.getServiceMapFromActiveChats()

            // Build handle service map: activity-based, falling back to handle existence
            val allHandles = handleRepository.getAllHandlesOnce()
            handleServiceMap = mutableMapOf<String, String>().apply {
                allHandles.forEach { handle ->
                    val normalized = normalizeAddress(handle.address)
                    // Priority 1: Use service from most recent ACTIVE chat (has messages)
                    val activityService = activityServiceMap[normalized]
                    if (activityService != null) {
                        put(normalized, activityService)
                    }
                    // Priority 2: For handles with no active chat, default to SMS (phone) or iMessage (email)
                    else if (!containsKey(normalized)) {
                        put(normalized, if (handle.address.contains("@")) "iMessage" else "SMS")
                    }
                }
            }

            // Load last message dates for deduplication
            lastMessageDates = chatRepository.getLastMessageDatePerAddress()

            // Load contacts
            val phoneContacts = androidContactsService.getAllContacts()
            val contactEntries = mutableListOf<ContactEntry>()

            for (contact in phoneContacts) {
                // Add phone numbers
                for (phone in contact.phoneNumbers) {
                    val normalized = normalizeAddress(phone)
                    val service = handleServiceMap[normalized] ?: "SMS"
                    contactEntries.add(
                        ContactEntry(
                            contactId = contact.contactId,
                            displayName = contact.displayName,
                            address = phone,
                            normalizedAddress = normalized,
                            formattedAddress = PhoneNumberFormatter.format(phone),
                            service = service,
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
                            service = "iMessage", // Emails are always iMessage
                            avatarPath = contact.photoUri,
                            lastMessageDate = lastMessageDates[normalized]
                        )
                    )
                }
            }

            // Deduplicate by normalized address, prefer iMessage
            allContacts = contactEntries
                .groupBy { it.normalizedAddress }
                .map { (_, group) ->
                    group.find { it.service == "iMessage" } ?: group.first()
                }

            // Load group chats
            val groupChats = chatRepository.getRecentGroupChats().first()
            allGroups = groupChats.map { chat ->
                GroupEntry(
                    chatGuid = chat.guid,
                    displayName = chat.displayName ?: chat.chatIdentifier ?: "Group Chat",
                    memberPreview = buildMemberPreview(chat),
                    avatarPath = null // Group photo now stored in UnifiedChatEntity
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
                matchedIdentifiers.add(chat.normalizedAddress)

                // Look up handle for avatar (prefer any handle with avatar)
                val handles = handleRepository.getHandlesByAddress(chat.normalizedAddress)
                val handle = handles.firstOrNull { it.cachedAvatarPath != null } ?: handles.firstOrNull()

                // Determine service using activity-based logic (same as ContactLoadDelegate)
                // Priority: handleServiceMap (activity-based) > sourceId prefix > SMS default
                val normalizedIdentifier = normalizeAddress(chat.normalizedAddress)
                val activityService = handleServiceMap[normalizedIdentifier]
                val service = when {
                    activityService != null -> activityService
                    chat.sourceId.startsWith("iMessage", ignoreCase = true) -> "iMessage"
                    chat.sourceId.startsWith("RCS", ignoreCase = true) -> "RCS"
                    else -> "SMS"
                }
                Timber.d("Unified chat service determination: identifier=${chat.normalizedAddress}, activityService=$activityService, service=$service")

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
                        service = service,
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
            // Deduplicate by contactId, prefer most recently used handle
            .groupBy { it.contactId ?: it.normalizedAddress.hashCode().toLong() }
            .map { (_, group) ->
                val withMessages = group.filter { it.lastMessageDate != null }
                if (withMessages.isNotEmpty()) {
                    withMessages.maxByOrNull { it.lastMessageDate!! }!!
                } else {
                    group.find { it.service == "iMessage" } ?: group.first()
                }
            }
            .take(MAX_CONTACT_SUGGESTIONS - results.size.coerceAtMost(MAX_CONTACT_SUGGESTIONS))

        results.addAll(matchingContacts.map { contact ->
            RecipientSuggestion.Contact(
                contactId = contact.contactId,
                displayName = contact.displayName,
                address = contact.address,
                formattedAddress = contact.formattedAddress,
                service = contact.service,
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

    private fun buildMemberPreview(chat: ChatEntity): String {
        // For now, just return a placeholder. Could enhance with actual participant lookup.
        return chat.chatIdentifier ?: "Group members"
    }

    companion object {
        private const val MAX_UNIFIED_SUGGESTIONS = 5 // Unified groups with nicknames (priority)
        private const val MAX_CONTACT_SUGGESTIONS = 5 // Android contacts fallback
        private const val MAX_GROUP_SUGGESTIONS = 3   // Multi-participant group chats
    }
}
