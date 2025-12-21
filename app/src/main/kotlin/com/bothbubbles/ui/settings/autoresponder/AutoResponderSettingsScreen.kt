package com.bothbubbles.ui.settings.autoresponder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bothbubbles.util.HapticUtils
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoResponderSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AutoResponderSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auto-Responder") },
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
        AutoResponderSettingsContent(
            modifier = Modifier.padding(paddingValues),
            uiState = uiState,
            viewModel = viewModel
        )
    }
}

@Composable
fun AutoResponderSettingsContent(
    modifier: Modifier = Modifier,
    viewModel: AutoResponderSettingsViewModel = hiltViewModel(),
    uiState: AutoResponderSettingsUiState = viewModel.uiState.collectAsStateWithLifecycle().value
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
            // Info card
            InfoCard()

            // Enable toggle
            SettingsSection(title = null) {
                SettingsToggleItem(
                    title = "Enable Auto-Responder",
                    subtitle = "Send iMessage greeting to SMS senders who are iMessage-capable",
                    checked = uiState.enabled,
                    onCheckedChange = viewModel::setEnabled
                )
            }

            // Message preview
            if (uiState.enabled) {
                SettingsSection(title = "Message Preview") {
                    MessagePreviewCard(message = uiState.messagePreview)
                }

                // Alias selection
                SettingsSection(title = "Recommend adding") {
                    AliasSelector(
                        availableAliases = uiState.availableAliases,
                        selectedAlias = uiState.selectedAlias,
                        isLoading = uiState.isLoadingAliases,
                        onAliasSelected = viewModel::setRecommendedAlias
                    )
                }

                // Filter options
                SettingsSection(title = "Auto-respond to") {
                    FilterOption(
                        title = "Everyone",
                        subtitle = "Any SMS sender who is iMessage-capable",
                        selected = uiState.filterMode == "everyone",
                        onClick = { viewModel.setFilterMode("everyone") }
                    )
                    FilterOption(
                        title = "Known contacts",
                        subtitle = "Only people saved in your contacts",
                        selected = uiState.filterMode == "known_senders",
                        onClick = { viewModel.setFilterMode("known_senders") }
                    )
                    FilterOption(
                        title = "Favorites only",
                        subtitle = "Only starred contacts",
                        selected = uiState.filterMode == "favorites",
                        onClick = { viewModel.setFilterMode("favorites") }
                    )
                }

                // Rate limit
                SettingsSection(title = "Rate Limit") {
                    RateLimitSlider(
                        currentLimit = uiState.rateLimit,
                        onLimitChange = viewModel::setRateLimit
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

@Composable
private fun InfoCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Help SMS contacts switch to iMessage",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "When someone sends you an SMS but is also registered on iMessage, " +
                        "this sends them an iMessage explaining how to add your iMessage address " +
                        "to their contacts so future messages go through iMessage instead of SMS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun MessagePreviewCard(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "This is what your contacts will receive",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            // Chat bubble style
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(12.dp)
                )
            }
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
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                HapticUtils.onConfirm(haptic)
                onCheckedChange(!checked)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                HapticUtils.onConfirm(haptic)
                onCheckedChange(it)
            }
        )
    }
}

@Composable
private fun FilterOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RateLimitSlider(
    currentLimit: Int,
    onLimitChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Maximum $currentLimit auto-responses per hour",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = currentLimit.toFloat(),
            onValueChange = { onLimitChange(it.roundToInt()) },
            valueRange = 1f..50f,
            steps = 48
        )
        Text(
            text = "Prevents spam if many people message you at once",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AliasSelector(
    availableAliases: List<String>,
    selectedAlias: String,
    isLoading: Boolean,
    onAliasSelected: (String) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (isLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Loading your iMessage addresses...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (availableAliases.isEmpty()) {
            Text(
                text = "Could not load iMessage addresses. Make sure Private API is enabled on your server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = selectedAlias.ifBlank { "Select an address to recommend" },
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    // Option for generic message (no specific alias)
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Don't specify (generic message)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        onClick = {
                            onAliasSelected("")
                            expanded = false
                        }
                    )
                    // Available aliases
                    availableAliases.forEach { alias ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = alias,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            onClick = {
                                onAliasSelected(alias)
                                expanded = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Choose which phone number or email to recommend contacts add",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
