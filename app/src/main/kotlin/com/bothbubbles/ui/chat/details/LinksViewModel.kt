package com.bothbubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.data.repository.LinkPreviewRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.util.parsing.UrlParsingUtils
import com.bothbubbles.ui.navigation.Screen
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class LinksUiState(
    val links: List<LinkItem> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true
)

@HiltViewModel
class LinksViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val handleRepository: HandleRepository,
    private val linkPreviewRepository: LinkPreviewRepository,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    companion object {
        private const val PAGE_SIZE = 20  // Messages per page (each may have multiple URLs)
    }

    private val route: Screen.LinksGallery = savedStateHandle.toRoute()
    val chatGuid: String = route.chatGuid

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    // Domains that indicate a location/places link
    private val placesDomains = setOf(
        "maps.google.com",
        "maps.apple.com",
        "maps.app.goo.gl",
        "goo.gl/maps",
        "findmy.apple.com",
        "find-my.apple.com"
    )

    private val _uiState = MutableStateFlow(LinksUiState())
    val uiState: StateFlow<LinksUiState> = _uiState.asStateFlow()

    private var currentOffset = 0
    private val loadedLinks = mutableListOf<LinkItem>()

    init {
        loadInitialLinks()
    }

    private fun loadInitialLinks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            loadNextPage()
        }
    }

    fun loadMore() {
        val currentState = _uiState.value
        if (currentState.isLoading || currentState.isLoadingMore || !currentState.hasMore) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            loadNextPage()
        }
    }

    private suspend fun loadNextPage() {
        val messages = messageRepository.getMessagesWithUrlsForChatPaged(
            chatGuid = chatGuid,
            limit = PAGE_SIZE,
            offset = currentOffset
        )

        // Extract links from messages
        val newLinks = messages.flatMap { message ->
            extractLinksFromMessage(message)
        }.filter { link ->
            !isPlacesUrl(link.url)
        }

        // Fetch preview data only if link previews are enabled
        val linksWithPreviews = if (settingsDataStore.linkPreviewsEnabled.first()) {
            val urls = newLinks.map { it.url }.distinct()
            val previews = linkPreviewRepository.getLinkPreviews(urls)

            newLinks.map { link ->
                val preview = previews[link.url]
                if (preview != null && preview.isSuccess) {
                    link.copy(
                        title = preview.title,
                        thumbnailUrl = preview.imageUrl
                    )
                } else {
                    link
                }
            }
        } else {
            newLinks
        }

        loadedLinks.addAll(linksWithPreviews)
        currentOffset += PAGE_SIZE

        _uiState.update {
            it.copy(
                links = loadedLinks.toList(),
                isLoading = false,
                isLoadingMore = false,
                hasMore = messages.size == PAGE_SIZE  // More pages available if we got a full page
            )
        }
    }

    private suspend fun extractLinksFromMessage(message: MessageEntity): List<LinkItem> {
        val text = message.text ?: return emptyList()
        val detectedUrls = UrlParsingUtils.detectUrls(text)

        if (detectedUrls.isEmpty()) return emptyList()

        // Get sender name and avatar
        val handle = if (!message.isFromMe) {
            message.handleId?.let { handleId -> handleRepository.getHandleById(handleId) }
        } else null

        val senderName = if (message.isFromMe) {
            "You"
        } else {
            handle?.displayName ?: handle?.address?.let { PhoneNumberFormatter.format(it) } ?: ""
        }

        val senderAvatarPath = if (message.isFromMe) null else handle?.cachedAvatarPath

        val timestamp = dateFormat.format(Date(message.dateCreated))

        return detectedUrls.map { detected ->
            LinkItem(
                id = "${message.guid}_${detected.startIndex}",
                url = detected.url,
                title = null, // Could be populated from LinkPreviewRepository if needed
                domain = detected.domain,
                senderName = senderName,
                senderAvatarPath = senderAvatarPath,
                timestamp = timestamp,
                thumbnailUrl = null
            )
        }
    }

    private fun isPlacesUrl(url: String): Boolean {
        val domain = UrlParsingUtils.extractDomain(url).lowercase()
        return placesDomains.any { placesDomain ->
            domain.contains(placesDomain) || url.lowercase().contains(placesDomain)
        }
    }
}
