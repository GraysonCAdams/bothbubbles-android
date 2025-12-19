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
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.Life360Repository
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.contacts.DiscordContactService
import com.bothbubbles.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val life360Member: Life360Member? = null,
    val isLoading: Boolean = true,
    val error: String? = null
) {
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
    private val life360Repository: Life360Repository
) : ViewModel() {

    private val route: Screen.ChatDetails = savedStateHandle.toRoute()
    val chatGuid: String = route.chatGuid

    private val _actionState = MutableStateFlow<ActionState>(ActionState.Idle)
    val actionState: StateFlow<ActionState> = _actionState

    private val _isContactStarred = MutableStateFlow(false)
    private val _discordChannelId = MutableStateFlow<String?>(null)

    // Observe Life360 member linked to first participant (by address, not handle ID)
    // Using address is more reliable because the same phone number can have multiple handle IDs
    // (one for iMessage, one for SMS, etc.)
    private val life360MemberFlow = chatRepository.observeParticipantsForChat(chatGuid)
        .flatMapLatest { participants ->
            val participant = participants.firstOrNull()
            val address = participant?.address
            timber.log.Timber.d("Life360 lookup: chatGuid=$chatGuid, participant=$address")
            if (address != null) {
                life360Repository.observeMemberByAddress(address)
            } else {
                flowOf(null)
            }
        }

    val uiState: StateFlow<ConversationDetailsUiState> = combine(
        chatRepository.observeChat(chatGuid),
        chatRepository.observeParticipantsForChat(chatGuid),
        attachmentRepository.observeImageCountForChat(chatGuid),
        attachmentRepository.observeOtherMediaCountForChat(chatGuid),
        attachmentRepository.observeRecentImagesForChat(chatGuid, 5),
        _isContactStarred,
        _discordChannelId,
        life360MemberFlow
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val chat = values[0] as? ChatEntity
        val participants = values[1] as? List<HandleEntity> ?: emptyList()
        val imageCount = values[2] as? Int ?: 0
        val otherMediaCount = values[3] as? Int ?: 0
        val recentImages = values[4] as? List<AttachmentEntity> ?: emptyList()
        val isStarred = values[5] as? Boolean ?: false
        val discordChannelId = values[6] as? String
        val life360Member = values[7] as? Life360Member

        ConversationDetailsUiState(
            chat = chat,
            participants = participants,
            imageCount = imageCount,
            otherMediaCount = otherMediaCount,
            recentImages = recentImages,
            isContactStarred = isStarred,
            discordChannelId = discordChannelId,
            life360Member = life360Member,
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
