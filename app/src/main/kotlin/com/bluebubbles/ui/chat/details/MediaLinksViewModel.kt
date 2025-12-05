package com.bluebubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bluebubbles.data.local.db.dao.AttachmentDao
import com.bluebubbles.data.local.db.entity.AttachmentEntity
import com.bluebubbles.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class MediaLinksUiState(
    val images: List<AttachmentEntity> = emptyList(),
    val videos: List<AttachmentEntity> = emptyList(),
    val links: List<LinkPreview> = emptyList(),
    val isLoading: Boolean = true,
    val selectedFilter: MediaFilter = MediaFilter.ALL
)

data class LinkPreview(
    val url: String,
    val domain: String,
    val senderName: String,
    val timestamp: String,
    val thumbnailUrl: String? = null
)

enum class MediaFilter {
    ALL, IMAGES, VIDEOS, PLACES, LINKS
}

@HiltViewModel
class MediaLinksViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val attachmentDao: AttachmentDao
) : ViewModel() {

    private val route: Screen.MediaGallery = savedStateHandle.toRoute()
    val chatGuid: String = route.chatGuid

    private val _selectedFilter = MutableStateFlow(MediaFilter.ALL)

    val uiState: StateFlow<MediaLinksUiState> = combine(
        attachmentDao.getImagesForChat(chatGuid),
        attachmentDao.getVideosForChat(chatGuid),
        _selectedFilter
    ) { images, videos, filter ->
        MediaLinksUiState(
            images = images.take(8), // Preview limit
            videos = videos.take(4),
            links = emptyList(), // TODO: Extract links from messages
            isLoading = false,
            selectedFilter = filter
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MediaLinksUiState()
    )

    fun selectFilter(filter: MediaFilter) {
        _selectedFilter.value = filter
    }
}
