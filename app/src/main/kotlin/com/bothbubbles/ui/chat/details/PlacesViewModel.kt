package com.bothbubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.data.repository.HandleRepository
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

data class PlacesUiState(
    val places: List<PlaceItem> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class PlacesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val handleRepository: HandleRepository
) : ViewModel() {

    private val route: Screen.PlacesGallery = savedStateHandle.toRoute()
    val chatGuid: String = route.chatGuid

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    // Domains and patterns that indicate a location/places link
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

    val uiState: StateFlow<PlacesUiState> = messageRepository.getMessagesWithUrlsForChat(chatGuid)
        .map { messages ->
            val places = messages.flatMap { message ->
                extractPlacesFromMessage(message)
            }
            PlacesUiState(
                places = places,
                isLoading = false
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PlacesUiState()
        )

    private suspend fun extractPlacesFromMessage(message: MessageEntity): List<PlaceItem> {
        val text = message.text ?: return emptyList()

        // Detect URLs and coordinates
        val detectedUrls = UrlParsingUtils.detectUrls(text)
        val detectedCoordinates = UrlParsingUtils.detectCoordinates(text)

        // Filter for places URLs only
        val placesUrls = detectedUrls.filter { isPlacesUrl(it.url) }

        // Combine URLs and coordinates (coordinates are already converted to Google Maps URLs)
        val allPlaces = (placesUrls + detectedCoordinates)
            .sortedBy { it.startIndex }
            .distinctBy { it.url } // Avoid duplicates

        if (allPlaces.isEmpty()) return emptyList()

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

        return allPlaces.map { detected ->
            val title = when {
                detected.isCoordinates -> "Location: ${detected.matchedText}"
                detected.url.contains("findmy") -> "See my real-time location"
                else -> detected.url.take(40).let { if (detected.url.length > 40) "$it..." else it }
            }

            PlaceItem(
                id = "${message.guid}_${detected.startIndex}",
                title = title,
                url = detected.url,
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
}
