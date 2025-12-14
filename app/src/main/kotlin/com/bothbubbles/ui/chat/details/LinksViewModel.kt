package com.bothbubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.data.repository.LinkPreviewRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.util.parsing.UrlParsingUtils
import com.bothbubbles.ui.navigation.Screen
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class LinksUiState(
    val links: List<LinkItem> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class LinksViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val handleRepository: HandleRepository,
    private val linkPreviewRepository: LinkPreviewRepository
) : ViewModel() {

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

    val uiState: StateFlow<LinksUiState> = messageRepository.getMessagesWithUrlsForChat(chatGuid)
        .map { messages ->
            // Step 1: Extract all links without preview data
            val linksWithoutPreviews = messages.flatMap { message ->
                extractLinksFromMessage(message)
            }.filter { link ->
                // Exclude places/location links
                !isPlacesUrl(link.url)
            }

            // Step 2: Batch fetch link previews
            val urls = linksWithoutPreviews.map { it.url }.distinct()
            val previews = linkPreviewRepository.getLinkPreviews(urls)

            // Step 3: Merge preview data into links
            val linksWithPreviews = linksWithoutPreviews.map { link ->
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

            LinksUiState(
                links = linksWithPreviews,
                isLoading = false
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LinksUiState()
        )

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
