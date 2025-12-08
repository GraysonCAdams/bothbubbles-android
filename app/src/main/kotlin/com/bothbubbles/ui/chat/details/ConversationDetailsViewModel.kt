package com.bothbubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

@HiltViewModel
class ConversationDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val chatDao: ChatDao,
    private val attachmentDao: AttachmentDao,
    private val androidContactsService: AndroidContactsService
) : ViewModel() {

    private val route: Screen.ChatDetails = savedStateHandle.toRoute()
    val chatGuid: String = route.chatGuid

    private val _actionState = MutableStateFlow<ActionState>(ActionState.Idle)
    val actionState: StateFlow<ActionState> = _actionState

    private val _isContactStarred = MutableStateFlow(false)

    val uiState: StateFlow<ConversationDetailsUiState> = combine(
        chatRepository.observeChat(chatGuid),
        chatDao.observeParticipantsForChat(chatGuid),
        attachmentDao.observeImageCountForChat(chatGuid),
        attachmentDao.observeOtherMediaCountForChat(chatGuid),
        attachmentDao.observeRecentImagesForChat(chatGuid, 5),
        _isContactStarred
    ) { values ->
        val chat = values[0] as ChatEntity?
        val participants = values[1] as List<HandleEntity>
        val imageCount = values[2] as Int
        val otherMediaCount = values[3] as Int
        val recentImages = values[4] as List<AttachmentEntity>
        val isStarred = values[5] as Boolean

        ConversationDetailsUiState(
            chat = chat,
            participants = participants,
            imageCount = imageCount,
            otherMediaCount = otherMediaCount,
            recentImages = recentImages,
            isContactStarred = isStarred,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConversationDetailsUiState()
    )

    init {
        // Check starred status when participants are loaded
        viewModelScope.launch {
            chatDao.observeParticipantsForChat(chatGuid).collect { participants ->
                val address = participants.firstOrNull()?.address
                if (address != null) {
                    _isContactStarred.value = androidContactsService.isContactStarred(address)
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

    sealed interface ActionState {
        data object Idle : ActionState
        data object Archived : ActionState
        data object Deleted : ActionState
        data object Blocked : ActionState
    }
}
