package com.bothbubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MediaGalleryUiState(
    val attachments: List<AttachmentEntity> = emptyList(),
    val isLoading: Boolean = true,
    val title: String = "Images"
) {
    val itemCount: String
        get() = when {
            attachments.size >= 100 -> "99+"
            else -> attachments.size.toString()
        }
}

@HiltViewModel
class MediaGalleryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val attachmentDao: AttachmentDao,
    private val attachmentRepository: AttachmentRepository
) : ViewModel() {

    private val route: Screen.MediaGallery = savedStateHandle.toRoute()
    val chatGuid: String = route.chatGuid
    val mediaType: String = route.mediaType

    val uiState: StateFlow<MediaGalleryUiState> = when (mediaType) {
        "images" -> attachmentDao.getImagesForChat(chatGuid)
        "videos" -> attachmentDao.getVideosForChat(chatGuid)
        else -> attachmentDao.getAttachmentsForChat(chatGuid)
    }.map { attachments ->
        MediaGalleryUiState(
            attachments = attachments,
            isLoading = false,
            title = when (mediaType) {
                "images" -> "Images"
                "videos" -> "Videos"
                else -> "Media"
            }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MediaGalleryUiState()
    )

    fun downloadAttachment(attachmentGuid: String) {
        viewModelScope.launch {
            attachmentRepository.downloadAttachment(attachmentGuid)
        }
    }
}
