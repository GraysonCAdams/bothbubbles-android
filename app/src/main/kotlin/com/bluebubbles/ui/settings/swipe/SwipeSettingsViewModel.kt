package com.bluebubbles.ui.settings.swipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.local.prefs.SettingsDataStore
import com.bluebubbles.ui.components.SwipeActionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SwipeSettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SwipeSettingsUiState())
    val uiState: StateFlow<SwipeSettingsUiState> = _uiState.asStateFlow()

    init {
        observeSettings()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            combine(
                settingsDataStore.swipeGesturesEnabled,
                settingsDataStore.swipeLeftAction,
                settingsDataStore.swipeRightAction,
                settingsDataStore.swipeSensitivity
            ) { enabled, leftAction, rightAction, sensitivity ->
                SwipeSettingsUiState(
                    swipeEnabled = enabled,
                    leftAction = SwipeActionType.fromKey(leftAction),
                    rightAction = SwipeActionType.fromKey(rightAction),
                    sensitivity = sensitivity,
                    isLoading = false
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun setSwipeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setSwipeGesturesEnabled(enabled)
        }
    }

    fun setLeftAction(action: SwipeActionType) {
        viewModelScope.launch {
            settingsDataStore.setSwipeLeftAction(action.key)
        }
    }

    fun setRightAction(action: SwipeActionType) {
        viewModelScope.launch {
            settingsDataStore.setSwipeRightAction(action.key)
        }
    }

    fun setSensitivity(sensitivity: Float) {
        viewModelScope.launch {
            settingsDataStore.setSwipeSensitivity(sensitivity)
        }
    }

    companion object {
        // Available actions for selection (excluding contextual variants)
        val availableActions = listOf(
            SwipeActionType.NONE,
            SwipeActionType.PIN,
            SwipeActionType.ARCHIVE,
            SwipeActionType.DELETE,
            SwipeActionType.MUTE,
            SwipeActionType.MARK_READ
        )
    }
}

data class SwipeSettingsUiState(
    val swipeEnabled: Boolean = true,
    val leftAction: SwipeActionType = SwipeActionType.ARCHIVE,
    val rightAction: SwipeActionType = SwipeActionType.PIN,
    val sensitivity: Float = 0.25f,
    val isLoading: Boolean = true
)
