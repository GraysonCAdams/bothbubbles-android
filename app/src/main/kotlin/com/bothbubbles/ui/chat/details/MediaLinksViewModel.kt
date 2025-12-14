package com.bothbubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.util.parsing.UrlParsingUtils
import com.bothbubbles.ui.navigation.Screen
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val attachmentRepository: AttachmentRepository,
    private val messageRepository: MessageRepository,
    private val handleRepository: HandleRepository
) : ViewModel() {

    private val route: Screen.MediaGallery = savedStateHandle.toRoute()
    val chatGuid: String = route.chatGuid

    private val _selectedFilter = MutableStateFlow(MediaFilter.ALL)
    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    // Domains and patterns that indicate a location/places link (to be excluded from links)
    private val placesPatterns = listOf(
        "maps.google.com",
        "maps.apple.com",
        "maps.app.goo.gl",
        "goo.gl/maps",
        "findmy.apple.com",
        "find-my.apple.com",
        "maps.google",
        "www.google.com/maps"
    )

    private val _uiState = MutableStateFlow(MediaLinksUiState())
    val uiState: StateFlow<MediaLinksUiState> = _uiState.asStateFlow()

    init {
        loadMediaAndLinks()
    }

    private fun loadMediaAndLinks() {
        viewModelScope.launch {
            // Combine images and videos flows with filter
            combine(
                attachmentRepository.getImagesForChat(chatGuid),
                attachmentRepository.getVideosForChat(chatGuid),
                _selectedFilter
            ) { images, videos, filter ->
                Triple(images.take(8), videos.take(4), filter)
            }.collect { (images, videos, filter) ->
                // Load links separately with limit (more efficient than loading all)
                val messages = messageRepository.getMessagesWithUrlsForChatPaged(
                    chatGuid = chatGuid,
                    limit = 20,  // Fetch enough to get ~10 non-places links
                    offset = 0
                )
                val links = messages
                    .flatMap { message -> extractLinksFromMessage(message) }
                    .take(10)  // Preview limit

                _uiState.update {
                    it.copy(
                        images = images,
                        videos = videos,
                        links = links,
                        isLoading = false,
                        selectedFilter = filter
                    )
                }
            }
        }
    }

    private suspend fun extractLinksFromMessage(message: MessageEntity): List<LinkPreview> {
        val text = message.text ?: return emptyList()

        // Detect all URLs in the message
        val detectedUrls = UrlParsingUtils.detectUrls(text)

        // Filter out places URLs (those go in PlacesScreen)
        val nonPlacesUrls = detectedUrls.filter { detected ->
            !isPlacesUrl(detected.url)
        }

        if (nonPlacesUrls.isEmpty()) return emptyList()

        // Get sender name
        val senderName = if (message.isFromMe) {
            "You"
        } else {
            message.handleId?.let { handleId ->
                val handle = handleRepository.getHandleById(handleId)
                handle?.displayName ?: handle?.address?.let { PhoneNumberFormatter.format(it) }
            } ?: ""
        }

        val timestamp = dateFormat.format(Date(message.dateCreated))

        return nonPlacesUrls.map { detected ->
            LinkPreview(
                url = detected.url,
                domain = detected.domain,
                senderName = senderName,
                timestamp = timestamp,
                thumbnailUrl = null
            )
        }
    }

    private fun isPlacesUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return placesPatterns.any { pattern ->
            lowerUrl.contains(pattern)
        }
    }

    fun selectFilter(filter: MediaFilter) {
        _selectedFilter.value = filter
    }
}
