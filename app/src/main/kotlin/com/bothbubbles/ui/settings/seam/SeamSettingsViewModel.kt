package com.bothbubbles.ui.settings.seam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.seam.hems.Feature
import com.bothbubbles.seam.hems.FeatureRegistry
import com.bothbubbles.seam.stitches.Stitch
import com.bothbubbles.seam.stitches.StitchConnectionState
import com.bothbubbles.seam.stitches.StitchRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Seam settings screen.
 * Manages Stitches (platform integrations) and Features (cross-platform enhancements).
 */
@HiltViewModel
class SeamSettingsViewModel @Inject constructor(
    private val stitchRegistry: StitchRegistry,
    private val featureRegistry: FeatureRegistry
) : ViewModel() {

    private val _uiState = MutableStateFlow(SeamSettingsUiState())
    val uiState: StateFlow<SeamSettingsUiState> = _uiState.asStateFlow()

    init {
        loadStitchesAndFeatures()
    }

    private fun loadStitchesAndFeatures() {
        viewModelScope.launch {
            val stitches = stitchRegistry.getAllStitches()
                .map { stitch -> stitch.toUiModel() }
                .sortedBy { it.displayName }
                .toImmutableList()

            val features = featureRegistry.getAllFeatures()
                .map { feature -> feature.toUiModel() }
                .sortedBy { it.displayName }
                .toImmutableList()

            _uiState.update {
                it.copy(
                    stitches = stitches,
                    features = features,
                    isLoading = false
                )
            }

            // Observe state changes for each stitch
            stitchRegistry.getAllStitches().forEach { stitch ->
                observeStitchState(stitch)
            }

            // Observe state changes for each feature
            featureRegistry.getAllFeatures().forEach { feature ->
                observeFeatureState(feature)
            }
        }
    }

    private fun observeStitchState(stitch: Stitch) {
        viewModelScope.launch {
            stitch.isEnabled.collect { isEnabled ->
                updateStitchEnabled(stitch.id, isEnabled)
            }
        }
        viewModelScope.launch {
            stitch.connectionState.collect { state ->
                updateStitchConnectionState(stitch.id, state)
            }
        }
    }

    private fun observeFeatureState(feature: Feature) {
        viewModelScope.launch {
            feature.isEnabled.collect { isEnabled ->
                updateFeatureEnabled(feature.id, isEnabled)
            }
        }
    }

    private fun updateStitchEnabled(stitchId: String, isEnabled: Boolean) {
        _uiState.update { state ->
            val updatedStitches = state.stitches.map { stitch ->
                if (stitch.id == stitchId) stitch.copy(isEnabled = isEnabled) else stitch
            }.toImmutableList()
            state.copy(stitches = updatedStitches)
        }
    }

    private fun updateStitchConnectionState(stitchId: String, connectionState: StitchConnectionState) {
        _uiState.update { state ->
            val updatedStitches = state.stitches.map { stitch ->
                if (stitch.id == stitchId) stitch.copy(connectionState = connectionState) else stitch
            }.toImmutableList()
            state.copy(stitches = updatedStitches)
        }
    }

    private fun updateFeatureEnabled(featureId: String, isEnabled: Boolean) {
        _uiState.update { state ->
            val updatedFeatures = state.features.map { feature ->
                if (feature.id == featureId) feature.copy(isEnabled = isEnabled) else feature
            }.toImmutableList()
            state.copy(features = updatedFeatures)
        }
    }

    /**
     * Check if a stitch can be disabled (must have at least one stitch enabled).
     */
    fun canDisableStitch(stitchId: String): Boolean {
        val stitch = stitchRegistry.getStitchById(stitchId) ?: return false
        return stitchRegistry.canDisableStitch(stitch)
    }

    /**
     * Get the settings route for a stitch, if available.
     */
    fun getStitchSettingsRoute(stitchId: String): String? {
        return stitchRegistry.getStitchById(stitchId)?.settingsRoute
    }

    /**
     * Get the settings route for a feature, if available.
     */
    fun getFeatureSettingsRoute(featureId: String): String? {
        return featureRegistry.getFeatureById(featureId)?.settingsRoute
    }

    private fun Stitch.toUiModel(): StitchUiModel = StitchUiModel(
        id = id,
        displayName = displayName,
        iconResId = iconResId,
        isEnabled = isEnabled.value,
        connectionState = connectionState.value,
        hasSettings = settingsRoute != null
    )

    private fun Feature.toUiModel(): FeatureUiModel = FeatureUiModel(
        id = id,
        displayName = displayName,
        description = description,
        isEnabled = isEnabled.value,
        hasSettings = settingsRoute != null
    )
}

/**
 * UI state for the Seam settings screen.
 */
data class SeamSettingsUiState(
    val stitches: ImmutableList<StitchUiModel> = persistentListOf(),
    val features: ImmutableList<FeatureUiModel> = persistentListOf(),
    val isLoading: Boolean = true
)

/**
 * UI model for a Stitch (platform integration).
 */
data class StitchUiModel(
    val id: String,
    val displayName: String,
    val iconResId: Int,
    val isEnabled: Boolean,
    val connectionState: StitchConnectionState,
    val hasSettings: Boolean
)

/**
 * UI model for a Feature (cross-platform enhancement).
 */
data class FeatureUiModel(
    val id: String,
    val displayName: String,
    val description: String,
    val isEnabled: Boolean,
    val hasSettings: Boolean
)
