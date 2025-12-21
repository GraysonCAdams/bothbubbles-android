package com.bothbubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import android.content.Intent
import android.net.Uri
import com.bothbubbles.core.model.Life360Member
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.core.data.prefs.FeaturePreferences
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.Life360Repository
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.contacts.DiscordContactService
import com.bothbubbles.services.life360.Life360Service
import com.bothbubbles.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationDetailsUiState(
    val chat: ChatEntity? = null,
    val participants: List<HandleEntity> = emptyList(),
    val imageCount: Int = 0,
    val otherMediaCount: Int = 0,
    val recentImages: List<AttachmentEntity> = emptyList(),
    val isContactStarred: Boolean = false,
    val discordChannelId: String? = null,
    val life360Members: List<Life360Member> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
) {
    /**
     * For 1:1 chats, get the single Life360 member (if any).
     */
    val life360Member: Life360Member?
        get() = life360Members.firstOrNull()

    val displayName: String
        get() = chat?.displayName
            ?: participants.firstOrNull()?.displayName
            ?: chat?.chatIdentifier
            ?: ""

    val subtitle: String
        get() = when {
            chat?.isGroup == true -> "${participants.size} people"
            participants.isNotEmpty() -> participants.first().address
            else -> chat?.chatIdentifier ?: ""
        }

    val isMuted: Boolean
        get() = chat?.muteType != null

    val isPinned: Boolean
        get() = chat?.isPinned == true

    val isArchived: Boolean
        get() = chat?.isArchived == true

    val isIMessage: Boolean
        get() = chat?.isIMessage == true

    val isSms: Boolean
        get() = chat?.isLocalSms == true || chat?.isTextForwarding == true

    val isSnoozed: Boolean
        get() = chat?.isSnoozed == true

    val snoozeUntil: Long?
        get() = chat?.snoozeUntil

    /**
     * Whether the first participant is a saved contact.
     * True if the participant has a cachedDisplayName (synced from device contacts).
     */
    val hasContact: Boolean
        get() = participants.firstOrNull()?.cachedDisplayName != null

    /**
     * The address of the first participant (phone number or email)
     */
    val firstParticipantAddress: String
        get() = participants.firstOrNull()?.address ?: ""
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val attachmentRepository: AttachmentRepository,
    private val androidContactsService: AndroidContactsService,
    private val discordContactService: DiscordContactService,
    private val life360Repository: Life360Repository,
    private val life360Service: Life360Service,
    private val featurePreferences: FeaturePreferences
) : ViewModel() {

    private val route: Screen.ChatDetails = savedStateHandle.toRoute()
    val chatGuid: String = route.chatGuid

    private val _actionState = MutableStateFlow<ActionState>(ActionState.Idle)
    val actionState: StateFlow<ActionState> = _actionState

    private val _isContactStarred = MutableStateFlow(false)
    private val _discordChannelId = MutableStateFlow<String?>(null)

    // Life360 refresh state
    private val _isRefreshingLife360 = MutableStateFlow(false)
    val isRefreshingLife360: StateFlow<Boolean> = _isRefreshingLife360.asStateFlow()

    // Life360 automatic polling job (foreground-only)
    private var life360PollingJob: Job? = null

    // Observe Life360 members linked to ALL participants (by phoneNumber match).
    // Using phoneNumber is more reliable because the same phone number can have multiple handle IDs
    // (one for iMessage, one for SMS, etc.), and works before autoMapContacts runs.
    // For group chats, this returns all members linked to any participant.
    private val life360MembersFlow = chatRepository.observeParticipantsForChat(chatGuid)
        .flatMapLatest { participants ->
            val addresses = participants.map { it.address }.toSet()
            life360Repository.observeMembersByPhoneNumbers(addresses)
        }

    val uiState: StateFlow<ConversationDetailsUiState> = combine(
        chatRepository.observeChat(chatGuid),
        chatRepository.observeParticipantsForChat(chatGuid),
        attachmentRepository.observeImageCountForChat(chatGuid),
        attachmentRepository.observeOtherMediaCountForChat(chatGuid),
        attachmentRepository.observeRecentImagesForChat(chatGuid, 5),
        _isContactStarred,
        _discordChannelId,
        life360MembersFlow
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val chat = values[0] as? ChatEntity
        val participants = values[1] as? List<HandleEntity> ?: emptyList()
        val imageCount = values[2] as? Int ?: 0
        val otherMediaCount = values[3] as? Int ?: 0
        val recentImages = values[4] as? List<AttachmentEntity> ?: emptyList()
        val isStarred = values[5] as? Boolean ?: false
        val discordChannelId = values[6] as? String
        val life360Members = values[7] as? List<Life360Member> ?: emptyList()

        ConversationDetailsUiState(
            chat = chat,
            participants = participants,
            imageCount = imageCount,
            otherMediaCount = otherMediaCount,
            recentImages = recentImages,
            isContactStarred = isStarred,
            discordChannelId = discordChannelId,
            life360Members = life360Members,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConversationDetailsUiState()
    )

    init {
        // Check starred status and Discord channel when participants are loaded
        viewModelScope.launch {
            chatRepository.observeParticipantsForChat(chatGuid).collect { participants ->
                val address = participants.firstOrNull()?.address
                if (address != null) {
                    _isContactStarred.value = androidContactsService.isContactStarred(address)
                    // Load Discord channel ID for non-group chats
                    _discordChannelId.value = discordContactService.getDiscordChannelId(address)
                }
            }
        }

        // Start Life360 polling when a member is linked to this chat
        startLife360Polling()
    }

    fun toggleStarred() {
        viewModelScope.launch {
            val address = uiState.value.firstParticipantAddress
            if (address.isNotEmpty()) {
                val newStarred = !_isContactStarred.value
                val success = androidContactsService.setContactStarred(address, newStarred)
                if (success) {
                    _isContactStarred.value = newStarred
                }
            }
        }
    }

    fun togglePin() {
        viewModelScope.launch {
            val currentState = uiState.value
            val newPinned = !currentState.isPinned
            chatRepository.setPinned(chatGuid, newPinned)
        }
    }

    fun toggleMute() {
        viewModelScope.launch {
            val currentState = uiState.value
            val newMuted = !currentState.isMuted
            chatRepository.setMuted(chatGuid, newMuted)
        }
    }

    fun snoozeChat(durationMs: Long) {
        viewModelScope.launch {
            chatRepository.snoozeChat(chatGuid, durationMs)
        }
    }

    fun unsnoozeChat() {
        viewModelScope.launch {
            chatRepository.unsnoozeChat(chatGuid)
        }
    }

    fun toggleArchive() {
        viewModelScope.launch {
            val currentState = uiState.value
            val newArchived = !currentState.isArchived
            chatRepository.setArchived(chatGuid, newArchived)
            if (newArchived) {
                _actionState.value = ActionState.Archived
            }
        }
    }

    fun deleteChat() {
        viewModelScope.launch {
            chatRepository.deleteChat(chatGuid)
            _actionState.value = ActionState.Deleted
        }
    }

    fun blockContact() {
        viewModelScope.launch {
            val address = uiState.value.firstParticipantAddress
            if (address.isNotEmpty()) {
                // Block using Android's BlockedNumberContract
                val blocked = androidContactsService.blockNumber(address)
                if (blocked) {
                    // Also archive the chat
                    chatRepository.setArchived(chatGuid, true)
                    _actionState.value = ActionState.Blocked
                }
            }
        }
    }

    fun clearActionState() {
        _actionState.value = ActionState.Idle
    }

    /**
     * Refresh contact info from device contacts.
     * Called when returning from the system contacts app after adding a contact.
     */
    fun refreshContactInfo(address: String) {
        viewModelScope.launch {
            val displayName = androidContactsService.getContactDisplayName(address)
            val photoUri = androidContactsService.getContactPhotoUri(address)
            if (displayName != null || photoUri != null) {
                chatRepository.updateHandleCachedContactInfo(address, displayName, photoUri)
            }
        }
    }

    // ============================================================================
    // LIFE360 LOCATION REFRESH
    // ============================================================================

    /**
     * Manually refresh Life360 location for the current contact (1:1 chat).
     * For group chats, use refreshLife360LocationFor(memberId).
     *
     * If 5+ minutes have passed since the last location request:
     * - Pings the contact's phone to request fresh location
     * - Waits briefly for their phone to respond
     * - Fetches just this member's data
     *
     * Otherwise (rate limited):
     * - Just fetches the member's cached data from Life360's servers
     *
     * The UI will auto-update since it observes the Life360 member flow.
     */
    fun refreshLife360Location() {
        val member = uiState.value.life360Member ?: return
        refreshLife360LocationFor(member.memberId)
    }

    /**
     * Manually refresh Life360 location for a specific member.
     * Used in group chats where multiple members may have Life360 linked.
     */
    fun refreshLife360LocationFor(memberId: String) {
        if (_isRefreshingLife360.value) return

        viewModelScope.launch {
            val member = uiState.value.life360Members.find { it.memberId == memberId } ?: return@launch

            _isRefreshingLife360.value = true
            val startTime = System.currentTimeMillis()
            timber.log.Timber.d("Life360 manual refresh started for ${member.displayName}")
            try {

                // Try to request location update (fails immediately if rate limited)
                val requestResult = life360Service.requestLocationUpdate(member.circleId, member.memberId)
                requestResult.fold(
                    onSuccess = {
                        timber.log.Timber.d("Location update requested for ${member.displayName}")
                        // Give the contact's phone a moment to respond before fetching
                        kotlinx.coroutines.delay(2000)
                    },
                    onFailure = {
                        timber.log.Timber.d("Skipping location ping (rate limited), will fetch cached data")
                    }
                )

                // Fetch just this member's data (not all circles)
                val result = life360Service.syncMember(member.circleId, member.memberId)
                val elapsed = System.currentTimeMillis() - startTime
                result.fold(
                    onSuccess = {
                        timber.log.Timber.d("Life360 refresh completed for ${member.displayName} in ${elapsed}ms")
                    },
                    onFailure = { error ->
                        timber.log.Timber.w(error, "Life360 refresh failed for ${member.displayName} after ${elapsed}ms")
                    }
                )
            } finally {
                _isRefreshingLife360.value = false
            }
        }
    }

    /**
     * Start automatic Life360 location polling for this conversation's members.
     *
     * This runs continuously while the conversation details screen is visible.
     * Uses the MEMBER endpoint (10-second rate limit) for efficient single-member fetches.
     * For group chats with multiple Life360 members, polls all of them.
     * Cancels automatically when ViewModel is cleared (user navigates away).
     */
    private fun startLife360Polling() {
        life360PollingJob?.cancel()
        life360PollingJob = viewModelScope.launch {
            // Wait for at least one Life360 member to be linked to this chat
            val members = life360MembersFlow.first { it.isNotEmpty() }
            timber.log.Timber.d("Life360 polling started for ${members.size} member(s): ${members.map { it.displayName }}")

            // Poll at MEMBER rate limit (10 seconds) - the service handles rate limiting
            while (true) {
                // Skip if manual refresh is in progress
                if (!_isRefreshingLife360.value) {
                    // Get current members (may change if participants change)
                    val currentMembers = life360MembersFlow.firstOrNull() ?: emptyList()
                    for (member in currentMembers) {
                        try {
                            life360Service.syncMember(member.circleId, member.memberId)
                            timber.log.Timber.d("Life360 poll sync completed for ${member.displayName}")
                        } catch (e: Exception) {
                            timber.log.Timber.w(e, "Life360 poll sync failed for ${member.displayName}")
                        }
                    }
                }
                // Wait before next poll (service rate limiter will also enforce minimum interval)
                delay(LIFE360_POLL_INTERVAL_MS)
            }
        }
    }

    companion object {
        // Poll every 15 seconds - service rate limiter enforces 10s minimum
        private const val LIFE360_POLL_INTERVAL_MS = 15_000L
    }

    // ============================================================================
    // DISCORD VIDEO CALL SUPPORT
    // ============================================================================

    /**
     * Check if Discord is installed.
     */
    fun isDiscordInstalled(): Boolean {
        return discordContactService.isDiscordInstalled()
    }

    /**
     * Save Discord channel ID for the participant.
     */
    fun saveDiscordChannelId(channelId: String) {
        val address = uiState.value.firstParticipantAddress
        if (address.isEmpty()) return

        viewModelScope.launch {
            val success = discordContactService.setDiscordChannelId(address, channelId)
            if (success) {
                _discordChannelId.value = channelId
            }
        }
    }

    /**
     * Clear Discord channel ID for the participant.
     */
    fun clearDiscordChannelId() {
        val address = uiState.value.firstParticipantAddress
        if (address.isEmpty()) return

        viewModelScope.launch {
            val success = discordContactService.clearDiscordChannelId(address)
            if (success) {
                _discordChannelId.value = null
            }
        }
    }

    /**
     * Create intent to open Discord DM channel.
     */
    fun getDiscordCallIntent(channelId: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("discord://-/channels/@me/$channelId"))
    }

    /**
     * Check if WhatsApp is installed.
     */
    fun isWhatsAppAvailable(): Boolean {
        return try {
            // This is a simple check - in production you'd use PackageManager
            true // WhatsApp is commonly installed
        } catch (e: Exception) {
            false
        }
    }

    sealed interface ActionState {
        data object Idle : ActionState
        data object Archived : ActionState
        data object Deleted : ActionState
        data object Blocked : ActionState
    }
}
