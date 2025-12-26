package com.bothbubbles.ui.settings.seam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.core.data.prefs.SendModeBehavior
import com.bothbubbles.data.repository.StitchSettingsRepository
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
import kotlinx.coroutines.flow.combine
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
    private val featureRegistry: FeatureRegistry,
    private val stitchSettingsRepository: StitchSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SeamSettingsUiState())
    val uiState: StateFlow<SeamSettingsUiState> = _uiState.asStateFlow()

    init {
        loadStitchesAndFeatures()
        observeSettings()
    }

    private fun observeSettings() {
        // Observe priority order and send mode behavior
        viewModelScope.launch {
            combine(
                stitchSettingsRepository.observePriorityOrder(),
                stitchSettingsRepository.observeSendModeBehavior()
            ) { priorityOrder, sendModeBehavior ->
                priorityOrder to sendModeBehavior
            }.collect { (priorityOrder, sendModeBehavior) ->
                _uiState.update { state ->
                    val effectivePriority = priorityOrder.ifEmpty {
                        // Default priority: BlueBubbles first, then SMS, then others
                        state.stitches.map { it.id }
                            .sortedWith(compareByDescending { id ->
                                when (id) {
                                    "bluebubbles" -> 2
                                    "sms" -> 1
                                    else -> 0
                                }
                            })
                    }

                    // Sort stitches by priority
                    val sortedStitches = state.stitches
                        .sortedBy { stitch ->
                            val index = effectivePriority.indexOf(stitch.id)
                            if (index >= 0) index else Int.MAX_VALUE
                        }
                        .toImmutableList()

                    state.copy(
                        stitches = sortedStitches,
                        priorityOrder = effectivePriority.toImmutableList(),
                        sendModeBehavior = sendModeBehavior
                    )
                }
            }
        }
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

    /**
     * Reorder stitches by moving one stitch from one position to another.
     */
    fun reorderStitch(fromIndex: Int, toIndex: Int) {
        val currentOrder = _uiState.value.priorityOrder.toMutableList()
        if (fromIndex !in currentOrder.indices || toIndex !in currentOrder.indices) return

        val item = currentOrder.removeAt(fromIndex)
        currentOrder.add(toIndex, item)

        viewModelScope.launch {
            stitchSettingsRepository.setPriorityOrder(currentOrder)
        }
    }

    /**
     * Set the complete priority order.
     */
    fun setPriorityOrder(orderedIds: List<String>) {
        viewModelScope.launch {
            stitchSettingsRepository.setPriorityOrder(orderedIds)
        }
    }

    /**
     * Toggle send mode behavior between AUTO_PRIORITY and PROMPT_FIRST_TIME.
     */
    fun toggleSendModeBehavior() {
        val currentBehavior = _uiState.value.sendModeBehavior
        val newBehavior = when (currentBehavior) {
            SendModeBehavior.AUTO_PRIORITY -> SendModeBehavior.PROMPT_FIRST_TIME
            SendModeBehavior.PROMPT_FIRST_TIME -> SendModeBehavior.AUTO_PRIORITY
        }
        viewModelScope.launch {
            stitchSettingsRepository.setSendModeBehavior(newBehavior)
        }
    }

    /**
     * Set the send mode behavior directly.
     */
    fun setSendModeBehavior(behavior: SendModeBehavior) {
        viewModelScope.launch {
            stitchSettingsRepository.setSendModeBehavior(behavior)
        }
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
    val priorityOrder: ImmutableList<String> = persistentListOf(),
    val sendModeBehavior: SendModeBehavior = SendModeBehavior.AUTO_PRIORITY,
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
    val hasSettings: Boolean,
    val priorityIndex: Int = -1  // -1 means not in priority order yet
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
