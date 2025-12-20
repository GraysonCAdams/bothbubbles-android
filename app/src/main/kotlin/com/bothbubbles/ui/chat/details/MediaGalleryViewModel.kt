package com.bothbubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.dao.AttachmentWithDate
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

/**
 * A group of media items for a specific month.
 */
data class MediaGroup(
    val label: String,
    val items: List<AttachmentWithDate>
)

data class MediaGalleryUiState(
    val groups: List<MediaGroup> = emptyList(),
    val isLoading: Boolean = true,
    val title: String = "Images",
    val totalCount: Int = 0
) {
    val itemCount: String
        get() = when {
            totalCount >= 100 -> "99+"
            else -> totalCount.toString()
        }
}

@HiltViewModel
class MediaGalleryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val attachmentRepository: AttachmentRepository
) : ViewModel() {

    private val route: Screen.MediaGallery = savedStateHandle.toRoute()
    val chatGuid: String = route.chatGuid
    val mediaType: String = route.mediaType

    companion object {
        // Thread-safe DateTimeFormatter (replaces SimpleDateFormat)
        private val MONTH_YEAR_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    }

    val uiState: StateFlow<MediaGalleryUiState> = attachmentRepository
        .getMediaWithDatesForChat(chatGuid)
        .map { allMedia ->
            // Filter by media type
            val filtered = when (mediaType) {
                "images" -> allMedia.filter { it.attachment.isImage }
                "videos" -> allMedia.filter { it.attachment.isVideo }
                else -> allMedia
            }

            // Group by month
            val groups = groupByMonth(filtered)

            MediaGalleryUiState(
                groups = groups,
                isLoading = false,
                title = when (mediaType) {
                    "images" -> "Images"
                    "videos" -> "Videos"
                    else -> "Media"
                },
                totalCount = filtered.size
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

    /**
     * Group attachments by month with human-readable labels.
     */
    private fun groupByMonth(items: List<AttachmentWithDate>): List<MediaGroup> {
        if (items.isEmpty()) return emptyList()

        val calendar = Calendar.getInstance()
        val now = Calendar.getInstance()

        return items.groupBy { item ->
            calendar.timeInMillis = item.dateCreated
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)

            // Use relative names for recent months
            when {
                year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH) -> "This Month"
                year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH) - 1 -> "Last Month"
                year == now.get(Calendar.YEAR) - 1 && now.get(Calendar.MONTH) == 0 && month == 11 -> "Last Month"
                else -> {
                    val instant = Instant.ofEpochMilli(item.dateCreated)
                    MONTH_YEAR_FORMAT.format(instant.atZone(ZoneId.systemDefault()))
                }
            }
        }.map { (label, groupItems) ->
            MediaGroup(label = label, items = groupItems)
        }
    }
}
