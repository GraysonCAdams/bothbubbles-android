package com.bothbubbles.ui.media

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.dao.MediaWithSender
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
    private val attachmentRepository: AttachmentRepository
) : ViewModel() {

    private val route: Screen.MediaViewer = savedStateHandle.toRoute()
    val attachmentGuid: String = route.attachmentGuid
    val chatGuid: String = route.chatGuid

    private val _uiState = MutableStateFlow(MediaViewerUiState())
    val uiState: StateFlow<MediaViewerUiState> = _uiState.asStateFlow()

    init {
        loadMediaList()
    }

    private fun loadMediaList() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Load all media for this chat with sender info (not just cached)
            val mediaList = attachmentRepository.getMediaWithSenderForChat(chatGuid)

            // Find the index of the initially selected attachment
            val initialIndex = mediaList.indexOfFirst { it.attachment.guid == attachmentGuid }.coerceAtLeast(0)
            val currentMedia = mediaList.getOrNull(initialIndex)

            if (currentMedia == null) {
                // Fall back to loading just the single attachment if not in list
                val attachment = attachmentRepository.getAttachmentByGuid(attachmentGuid)
                if (attachment == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Attachment not found"
                        )
                    }
                    return@launch
                }
                // Create a MediaWithSender wrapper for single attachment (no sender info available)
                val singleMedia = MediaWithSender(
                    attachment = attachment,
                    isFromMe = false,
                    senderAddress = null,
                    dateCreated = System.currentTimeMillis(),
                    displayName = null,
                    avatarPath = null,
                    formattedAddress = null
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        mediaList = listOf(singleMedia),
                        currentIndex = 0,
                        attachment = attachment,
                        title = attachment.transferName ?: "Media",
                        senderName = null,
                        senderAvatarPath = null,
                        senderAddress = null,
                        messageDateMillis = null
                    )
                }
                // If attachment isn't downloaded yet, start download
                if (attachment.localPath == null && attachment.webUrl != null) {
                    downloadCurrentAttachment()
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        mediaList = mediaList,
                        currentIndex = initialIndex,
                        attachment = currentMedia.attachment,
                        title = currentMedia.attachment.transferName ?: "Media",
                        senderName = if (currentMedia.isFromMe) "You" else currentMedia.displayName ?: currentMedia.formattedAddress ?: currentMedia.senderAddress,
                        senderAvatarPath = if (currentMedia.isFromMe) null else currentMedia.avatarPath,
                        senderAddress = if (currentMedia.isFromMe) null else (currentMedia.formattedAddress ?: currentMedia.senderAddress),
                        messageDateMillis = currentMedia.dateCreated,
                        isFromMe = currentMedia.isFromMe
                    )
                }
                // If current attachment isn't downloaded yet, start download
                if (currentMedia.attachment.localPath == null && currentMedia.attachment.webUrl != null) {
                    downloadCurrentAttachment()
                }
            }
        }
    }

    fun onPageChanged(index: Int) {
        val mediaList = _uiState.value.mediaList
        val media = mediaList.getOrNull(index) ?: return

        _uiState.update {
            it.copy(
                currentIndex = index,
                attachment = media.attachment,
                title = media.attachment.transferName ?: "Media",
                senderName = if (media.isFromMe) "You" else media.displayName ?: media.formattedAddress ?: media.senderAddress,
                senderAvatarPath = if (media.isFromMe) null else media.avatarPath,
                senderAddress = if (media.isFromMe) null else (media.formattedAddress ?: media.senderAddress),
                messageDateMillis = media.dateCreated,
                isFromMe = media.isFromMe
            )
        }

        // Auto-download if not cached
        if (media.attachment.localPath == null && media.attachment.webUrl != null) {
            downloadCurrentAttachment()
        }
    }

    fun downloadCurrentAttachment() {
        val currentGuid = _uiState.value.attachment?.guid ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, downloadProgress = 0f) }

            attachmentRepository.downloadAttachment(currentGuid) { progress ->
                _uiState.update { it.copy(downloadProgress = progress) }
            }.fold(
                onSuccess = { file ->
                    // Refresh attachment from DB to get updated localPath
                    val updatedAttachment = attachmentRepository.getAttachmentByGuid(currentGuid)
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

    fun saveToGallery() {
        val attachment = _uiState.value.attachment ?: return
        val localPath = attachment.localPath ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }

            attachmentRepository.saveToGallery(
                localPath = localPath,
                mimeType = attachment.mimeType,
                fileName = attachment.transferName
            ).fold(
                onSuccess = { uri ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            saveResult = SaveResult.Success(uri)
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            saveResult = SaveResult.Error(e.message ?: "Failed to save")
                        )
                    }
                }
            )
        }
    }

    fun showInfoDialog() {
        _uiState.update { it.copy(showInfoDialog = true) }
    }

    fun hideInfoDialog() {
        _uiState.update { it.copy(showInfoDialog = false) }
    }

    fun clearSaveResult() {
        _uiState.update { it.copy(saveResult = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

sealed interface SaveResult {
    data class Success(val uri: Uri) : SaveResult
    data class Error(val message: String) : SaveResult
}

data class MediaViewerUiState(
    val isLoading: Boolean = true,
    val isDownloading: Boolean = false,
    val isSaving: Boolean = false,
    val downloadProgress: Float = 0f,
    val mediaList: List<MediaWithSender> = emptyList(),
    val currentIndex: Int = 0,
    val attachment: AttachmentEntity? = null,
    val localFile: File? = null,
    val title: String = "",
    val error: String? = null,
    // Sender info
    val senderName: String? = null,
    val senderAvatarPath: String? = null,
    val senderAddress: String? = null,
    val messageDateMillis: Long? = null,
    val isFromMe: Boolean = false,
    // Dialogs
    val showInfoDialog: Boolean = false,
    val saveResult: SaveResult? = null
) {
    val mediaCount: Int get() = mediaList.size
    val hasMultipleMedia: Boolean get() = mediaList.size > 1
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

    val needsDownload: Boolean
        get() = attachment?.localPath == null && attachment?.webUrl != null

    val canSave: Boolean
        get() = attachment?.localPath != null && !isSaving

    val canShare: Boolean
        get() = attachment?.localPath != null
}
