package com.bluebubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bluebubbles.data.local.db.dao.AttachmentDao
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.entity.AttachmentEntity
import com.bluebubbles.data.local.db.entity.ChatEntity
import com.bluebubbles.data.local.db.entity.HandleEntity
import com.bluebubbles.data.repository.ChatRepository
import com.bluebubbles.ui.navigation.Screen
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
    private val attachmentDao: AttachmentDao
) : ViewModel() {

    private val route: Screen.ChatDetails = savedStateHandle.toRoute()
    val chatGuid: String = route.chatGuid

    private val _actionState = MutableStateFlow<ActionState>(ActionState.Idle)
    val actionState: StateFlow<ActionState> = _actionState

    val uiState: StateFlow<ConversationDetailsUiState> = combine(
        chatRepository.observeChat(chatGuid),
        chatDao.observeParticipantsForChat(chatGuid),
        attachmentDao.observeImageCountForChat(chatGuid),
        attachmentDao.observeOtherMediaCountForChat(chatGuid),
        attachmentDao.observeRecentImagesForChat(chatGuid, 5)
    ) { chat, participants, imageCount, otherMediaCount, recentImages ->
        ConversationDetailsUiState(
            chat = chat,
            participants = participants,
            imageCount = imageCount,
            otherMediaCount = otherMediaCount,
            recentImages = recentImages,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConversationDetailsUiState()
    )

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

    fun clearActionState() {
        _actionState.value = ActionState.Idle
    }

    sealed interface ActionState {
        data object Idle : ActionState
        data object Archived : ActionState
        data object Deleted : ActionState
    }
}
