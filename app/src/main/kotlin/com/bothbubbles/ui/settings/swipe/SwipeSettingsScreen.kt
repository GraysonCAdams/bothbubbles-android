package com.bothbubbles.ui.settings.swipe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SwipeSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Swipe Actions") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        SwipeSettingsContent(
            modifier = Modifier.padding(paddingValues),
            uiState = uiState,
            viewModel = viewModel
        )
    }
}

@Composable
fun SwipeSettingsContent(
    modifier: Modifier = Modifier,
    viewModel: SwipeSettingsViewModel = hiltViewModel(),
    uiState: SwipeSettingsUiState = viewModel.uiState.collectAsState().value
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Enable/Disable swipe gestures
        item {
            SwitchSettingCard(
                title = "Enable Swipe Gestures",
                subtitle = "Swipe conversations left or right for quick actions",
                icon = Icons.Default.SwipeRight,
                checked = uiState.swipeEnabled,
                onCheckedChange = viewModel::setSwipeEnabled
            )
        }

        // Swipe preview visualization
        item {
            AnimatedVisibility(visible = uiState.swipeEnabled) {
                SwipePreviewCard(
                    leftAction = uiState.leftAction,
                    rightAction = uiState.rightAction
                )
            }
        }

        // Left swipe action
        item {
            AnimatedVisibility(visible = uiState.swipeEnabled) {
                ActionSelectionCard(
                    title = "Swipe Left",
                    subtitle = "Action when swiping from right to left",
                    selectedAction = uiState.leftAction,
                    onActionSelected = viewModel::setLeftAction,
                    availableActions = SwipeSettingsViewModel.availableActions
                )
            }
        }

        // Right swipe action
        item {
            AnimatedVisibility(visible = uiState.swipeEnabled) {
                ActionSelectionCard(
                    title = "Swipe Right",
                    subtitle = "Action when swiping from left to right",
                    selectedAction = uiState.rightAction,
                    onActionSelected = viewModel::setRightAction,
                    availableActions = SwipeSettingsViewModel.availableActions
                )
            }
        }

        // Sensitivity slider
        item {
            AnimatedVisibility(visible = uiState.swipeEnabled) {
                SensitivityCard(
                    sensitivity = uiState.sensitivity,
                    onSensitivityChange = viewModel::setSensitivity
                )
            }
        }

        // Info card
        item {
            AnimatedVisibility(visible = uiState.swipeEnabled) {
                InfoCard()
            }
        }
    }
}
