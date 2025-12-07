package com.bothbubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.ui.components.UrlParsingUtils
import com.bothbubbles.ui.navigation.Screen
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val attachmentDao: AttachmentDao,
    private val messageDao: MessageDao,
    private val handleDao: HandleDao
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

    // Flow that extracts links from messages
    private val linksFlow = messageDao.getMessagesWithUrlsForChat(chatGuid)
        .map { messages ->
            messages.flatMap { message -> extractLinksFromMessage(message) }
        }

    val uiState: StateFlow<MediaLinksUiState> = combine(
        attachmentDao.getImagesForChat(chatGuid),
        attachmentDao.getVideosForChat(chatGuid),
        linksFlow,
        _selectedFilter
    ) { images, videos, links, filter ->
        MediaLinksUiState(
            images = images.take(8), // Preview limit
            videos = videos.take(4),
            links = links.take(10), // Preview limit
            isLoading = false,
            selectedFilter = filter
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MediaLinksUiState()
    )

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
                val handle = handleDao.getHandleById(handleId)
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
