package com.bothbubbles.ui.settings

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bothbubbles.util.HapticUtils
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectsSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: EffectsSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Message Effects") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        EffectsSettingsContent(
            modifier = Modifier.padding(paddingValues),
            uiState = uiState,
            viewModel = viewModel
        )
    }
}

@Composable
fun EffectsSettingsContent(
    modifier: Modifier = Modifier,
    viewModel: EffectsSettingsViewModel = hiltViewModel(),
    uiState: EffectsSettingsUiState = viewModel.uiState.collectAsStateWithLifecycle().value
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
            // Power saver mode banner
            if (uiState.isPowerSaveMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.BatterySaver,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .weight(1f)
                    ) {
                        Text(
                            text = "Power saver is on",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "Message effects are temporarily disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Playback section
            SettingsSection(title = "Playback") {
                SettingsToggleItem(
                    title = "Auto-play effects",
                    subtitle = "Play effects automatically when messages arrive",
                    checked = uiState.autoPlayEffects,
                    enabled = !uiState.isPowerSaveMode,
                    onCheckedChange = viewModel::setAutoPlayEffects
                )

                SettingsToggleItem(
                    title = "Replay effects on scroll",
                    subtitle = "Show effects again when scrolling to old messages",
                    checked = uiState.replayEffectsOnScroll,
                    enabled = !uiState.isPowerSaveMode,
                    onCheckedChange = viewModel::setReplayEffectsOnScroll
                )
            }

            // Accessibility section
            SettingsSection(title = "Accessibility") {
                SettingsToggleItem(
                    title = "Reduce motion",
                    subtitle = "Show simplified effect indicators instead of animations",
                    checked = uiState.reduceMotion,
                    enabled = !uiState.isPowerSaveMode,
                    onCheckedChange = viewModel::setReduceMotion
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

@Composable
private fun SettingsSection(
    title: String?,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        content()
    }
}

@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    // Subtle scale animation on toggle (Android 16 style)
    val scale by animateFloatAsState(
        targetValue = if (checked) 1f else 0.98f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "toggleScale"
    )

    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                }
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                HapticUtils.onConfirm(haptic)
                onCheckedChange(it)
            },
            enabled = enabled
        )
    }
}

