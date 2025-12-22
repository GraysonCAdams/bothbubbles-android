package com.bothbubbles.ui.settings.attachments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.model.AttachmentQuality
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ImageQualitySettingsUiState(
    val selectedQuality: AttachmentQuality = AttachmentQuality.DEFAULT,
    val rememberLastQuality: Boolean = false,
    val saveCapturedMediaToGallery: Boolean = true
)

@HiltViewModel
class ImageQualitySettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    val uiState: StateFlow<ImageQualitySettingsUiState> = combine(
        settingsDataStore.defaultImageQuality,
        settingsDataStore.rememberLastQuality,
        settingsDataStore.saveCapturedMediaToGallery
    ) { qualityStr, rememberLast, saveToGallery ->
        ImageQualitySettingsUiState(
            selectedQuality = AttachmentQuality.fromString(qualityStr),
            rememberLastQuality = rememberLast,
            saveCapturedMediaToGallery = saveToGallery
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ImageQualitySettingsUiState()
    )

    fun setImageQuality(quality: AttachmentQuality) {
        viewModelScope.launch {
            settingsDataStore.setDefaultImageQuality(quality.name)
        }
    }

    fun setRememberLastQuality(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setRememberLastQuality(enabled)
        }
    }

    fun setSaveCapturedMediaToGallery(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setSaveCapturedMediaToGallery(enabled)
        }
    }
}
