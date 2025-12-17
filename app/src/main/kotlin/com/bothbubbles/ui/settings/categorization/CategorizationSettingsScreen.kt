package com.bothbubbles.ui.settings.categorization

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.services.categorization.EntityExtractionService
import com.bothbubbles.services.categorization.MessageCategory
import com.bothbubbles.ui.settings.components.SettingsCard
import com.bothbubbles.ui.settings.components.SettingsMenuItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorizationSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: CategorizationSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show error snackbar
    LaunchedEffect(uiState.downloadError) {
        uiState.downloadError?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Message Categorization") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        CategorizationSettingsContent(
            modifier = Modifier.padding(padding),
            uiState = uiState,
            viewModel = viewModel
        )
    }
}

@Composable
fun CategorizationSettingsContent(
    modifier: Modifier = Modifier,
    viewModel: CategorizationSettingsViewModel = hiltViewModel(),
    uiState: CategorizationSettingsUiState = viewModel.uiState.collectAsStateWithLifecycle().value
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.downloadError) {
        uiState.downloadError?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
            // Description
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Outlined.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Automatically sort your messages into categories like Transactions, Deliveries, Promotions, and Reminders using on-device ML.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Categorization toggle
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsMenuItem(
                    icon = Icons.Default.Category,
                    title = "Enable categorization",
                    subtitle = if (uiState.categorizationEnabled) "Messages will be automatically categorized" else "Categorization is disabled",
                    onClick = { viewModel.setCategorizationEnabled(!uiState.categorizationEnabled) },
                    trailingContent = {
                        Switch(
                            checked = uiState.categorizationEnabled,
                            onCheckedChange = { viewModel.setCategorizationEnabled(it) }
                        )
                    }
                )
            }

            // ML Model section
            Text(
                text = "ML Model",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // ML model status
                SettingsMenuItem(
                    icon = if (uiState.mlModelDownloaded) Icons.Default.CloudDone else Icons.Default.CloudDownload,
                    title = "ML Model",
                    subtitle = when {
                        uiState.isDownloading -> "Downloading..."
                        uiState.mlModelDownloaded -> "Downloaded and ready"
                        else -> "Not downloaded (~${EntityExtractionService.MODEL_SIZE_MB} MB)"
                    },
                    onClick = {
                        if (!uiState.mlModelDownloaded && !uiState.isDownloading) {
                            viewModel.downloadMlModel()
                        }
                    },
                    enabled = !uiState.mlModelDownloaded && !uiState.isDownloading,
                    trailingContent = {
                        if (uiState.isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else if (!uiState.mlModelDownloaded) {
                            FilledTonalButton(
                                onClick = { viewModel.downloadMlModel() }
                            ) {
                                Text("Download")
                            }
                        } else {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Downloaded",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Cellular auto-update toggle
                SettingsMenuItem(
                    icon = Icons.Default.SignalCellularAlt,
                    title = "Auto-update on cellular",
                    subtitle = "Download ML model updates over cellular data",
                    onClick = { viewModel.setMlAutoUpdateOnCellular(!uiState.mlAutoUpdateOnCellular) },
                    trailingContent = {
                        Switch(
                            checked = uiState.mlAutoUpdateOnCellular,
                            onCheckedChange = { viewModel.setMlAutoUpdateOnCellular(it) }
                        )
                    }
                )
            }

            // Categories explanation
            Text(
                text = "Categories",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                MessageCategory.entries.forEachIndexed { index, category ->
                    val isEnabled = when (category) {
                        MessageCategory.TRANSACTIONS -> uiState.transactionsEnabled
                        MessageCategory.DELIVERIES -> uiState.deliveriesEnabled
                        MessageCategory.PROMOTIONS -> uiState.promotionsEnabled
                        MessageCategory.REMINDERS -> uiState.remindersEnabled
                    }
                    val onToggle: (Boolean) -> Unit = when (category) {
                        MessageCategory.TRANSACTIONS -> viewModel::setTransactionsEnabled
                        MessageCategory.DELIVERIES -> viewModel::setDeliveriesEnabled
                        MessageCategory.PROMOTIONS -> viewModel::setPromotionsEnabled
                        MessageCategory.REMINDERS -> viewModel::setRemindersEnabled
                    }

                    SettingsMenuItem(
                        icon = category.icon,
                        title = category.displayName,
                        subtitle = category.description,
                        onClick = { onToggle(!isEnabled) },
                        trailingContent = {
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = onToggle
                            )
                        }
                    )

                    if (index < MessageCategory.entries.size - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            // Info about how categorization works
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Categorization works best for business messages (short codes, alerts). Personal conversations are not categorized. All processing happens on-device for privacy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
