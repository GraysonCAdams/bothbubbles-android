package com.bothbubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.ui.components.UrlParsingUtils
import com.bothbubbles.ui.navigation.Screen
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
    private val messageDao: MessageDao,
    private val handleDao: HandleDao
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

    val uiState: StateFlow<LinksUiState> = messageDao.getMessagesWithUrlsForChat(chatGuid)
        .map { messages ->
            val links = messages.flatMap { message ->
                extractLinksFromMessage(message)
            }.filter { link ->
                // Exclude places/location links
                !isPlacesUrl(link.url)
            }
            LinksUiState(
                links = links,
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

        // Get sender name
        val senderName = if (message.isFromMe) {
            "You"
        } else {
            message.handleId?.let { handleId ->
                handleDao.getHandleById(handleId)?.displayName
            } ?: "Unknown"
        }

        val timestamp = dateFormat.format(Date(message.dateCreated))

        return detectedUrls.map { detected ->
            LinkItem(
                id = "${message.guid}_${detected.startIndex}",
                url = detected.url,
                title = null, // Could be populated from LinkPreviewRepository if needed
                domain = detected.domain,
                senderName = senderName,
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
