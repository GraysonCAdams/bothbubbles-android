package com.bluebubbles.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Master toggle section
            SettingsSection(title = null) {
                SettingsToggleItem(
                    title = "Enable message effects",
                    subtitle = "Show animations for special messages",
                    checked = uiState.effectsEnabled,
                    onCheckedChange = viewModel::setEffectsEnabled
                )
            }

            // Playback section
            SettingsSection(title = "Playback") {
                SettingsToggleItem(
                    title = "Auto-play effects",
                    subtitle = "Play effects automatically when messages arrive",
                    checked = uiState.autoPlayEffects,
                    enabled = uiState.effectsEnabled,
                    onCheckedChange = viewModel::setAutoPlayEffects
                )

                SettingsToggleItem(
                    title = "Replay effects on scroll",
                    subtitle = "Show effects again when scrolling to old messages",
                    checked = uiState.replayEffectsOnScroll,
                    enabled = uiState.effectsEnabled,
                    onCheckedChange = viewModel::setReplayEffectsOnScroll
                )
            }

            // Performance section
            SettingsSection(title = "Performance") {
                SettingsToggleItem(
                    title = "Disable on low battery",
                    subtitle = "Turn off effects below ${uiState.lowBatteryThreshold}%",
                    checked = uiState.disableOnLowBattery,
                    enabled = uiState.effectsEnabled,
                    onCheckedChange = viewModel::setDisableOnLowBattery
                )

                if (uiState.disableOnLowBattery && uiState.effectsEnabled) {
                    SettingsSliderItem(
                        title = "Battery threshold",
                        value = uiState.lowBatteryThreshold,
                        valueRange = 5..50,
                        onValueChange = viewModel::setLowBatteryThreshold
                    )
                }
            }

            // Accessibility section
            SettingsSection(title = "Accessibility") {
                SettingsToggleItem(
                    title = "Reduce motion",
                    subtitle = "Show simplified effect indicators instead of animations",
                    checked = uiState.reduceMotion,
                    enabled = uiState.effectsEnabled,
                    onCheckedChange = viewModel::setReduceMotion
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
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
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
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
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SettingsSliderItem(
    title: String,
    value: Int,
    valueRange: IntRange,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "$title: $value%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = (valueRange.last - valueRange.first) / 5 - 1
        )
    }
}
