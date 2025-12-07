package com.bothbubbles.ui.media

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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MediaViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val attachmentDao: AttachmentDao,
    private val attachmentRepository: AttachmentRepository
) : ViewModel() {

    private val route: Screen.MediaViewer = savedStateHandle.toRoute()
    val attachmentGuid: String = route.attachmentGuid
    val chatGuid: String = route.chatGuid

    private val _uiState = MutableStateFlow(MediaViewerUiState())
    val uiState: StateFlow<MediaViewerUiState> = _uiState.asStateFlow()

    init {
        loadAttachment()
    }

    private fun loadAttachment() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val attachment = attachmentDao.getAttachmentByGuid(attachmentGuid)
            if (attachment == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Attachment not found"
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    attachment = attachment,
                    title = attachment.transferName ?: "Media"
                )
            }

            // If attachment isn't downloaded yet, start download
            if (attachment.localPath == null && attachment.webUrl != null) {
                downloadAttachment()
            }
        }
    }

    fun downloadAttachment() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, downloadProgress = 0f) }

            attachmentRepository.downloadAttachment(attachmentGuid) { progress ->
                _uiState.update { it.copy(downloadProgress = progress) }
            }.fold(
                onSuccess = { file ->
                    // Refresh attachment from DB to get updated localPath
                    val updatedAttachment = attachmentDao.getAttachmentByGuid(attachmentGuid)
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            attachment = updatedAttachment,
                            localFile = file
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            error = "Failed to download: ${e.message}"
                        )
                    }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class MediaViewerUiState(
    val isLoading: Boolean = true,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val attachment: AttachmentEntity? = null,
    val localFile: File? = null,
    val title: String = "",
    val error: String? = null
) {
    val mediaUrl: String?
        get() = attachment?.localPath ?: attachment?.webUrl

    val isImage: Boolean
        get() = attachment?.isImage == true

    val isVideo: Boolean
        get() = attachment?.isVideo == true

    val isAudio: Boolean
        get() = attachment?.isAudio == true

    val hasValidMedia: Boolean
        get() = mediaUrl != null
}
