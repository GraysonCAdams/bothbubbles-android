package com.bothbubbles.ui.settings.storage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bothbubbles.services.storage.StorageBreakdown
import com.bothbubbles.services.storage.StorageCategory
import com.bothbubbles.services.storage.StorageManagementService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI State for Storage Management screen.
 */
data class StorageUiState(
    val isLoading: Boolean = true,
    val breakdown: StorageBreakdown = StorageBreakdown.EMPTY,
    val isClearing: Boolean = false,
    val clearingCategory: StorageCategory? = null,
    val lastClearedBytes: Long = 0L,
    val showClearSuccess: Boolean = false
)

@HiltViewModel
class StorageManagementViewModel @Inject constructor(
    private val storageService: StorageManagementService
) : ViewModel() {

    private val _uiState = MutableStateFlow(StorageUiState())
    val uiState: StateFlow<StorageUiState> = _uiState.asStateFlow()

    init {
        refreshStorageInfo()
    }

    fun refreshStorageInfo() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val breakdown = storageService.calculateStorageBreakdown()
            _uiState.update { it.copy(isLoading = false, breakdown = breakdown) }
        }
    }

    fun clearCategory(category: StorageCategory) {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearing = true, clearingCategory = category) }

            val freedBytes = storageService.clearCategory(category)

            // Refresh storage info after clearing
            val newBreakdown = storageService.calculateStorageBreakdown()

            _uiState.update {
                it.copy(
                    isClearing = false,
                    clearingCategory = null,
                    breakdown = newBreakdown,
                    lastClearedBytes = freedBytes,
                    showClearSuccess = true
                )
            }
        }
    }

    fun dismissClearSuccess() {
        _uiState.update { it.copy(showClearSuccess = false) }
    }

    fun formatBytes(bytes: Long): String = storageService.formatBytes(bytes)
}

/**
 * Storage content for embedding in SettingsPanel.
 * Provides the same functionality as StorageManagementScreen without the scaffold.
 */
@Composable
fun StorageContent(
    modifier: Modifier = Modifier,
    viewModel: StorageManagementViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show success snackbar
    LaunchedEffect(uiState.showClearSuccess) {
        if (uiState.showClearSuccess) {
            val message = "Cleared ${viewModel.formatBytes(uiState.lastClearedBytes)}"
            snackbarHostState.showSnackbar(message)
            viewModel.dismissClearSuccess()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Calculating storage usage...")
                }
            }
        } else {
            StorageManagementContent(
                modifier = Modifier.fillMaxSize(),
                breakdown = uiState.breakdown,
                isClearing = uiState.isClearing,
                clearingCategory = uiState.clearingCategory,
                onClearCategory = viewModel::clearCategory,
                formatBytes = viewModel::formatBytes
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageManagementScreen(
    onBackClick: () -> Unit,
    viewModel: StorageManagementViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Show success snackbar
    LaunchedEffect(uiState.showClearSuccess) {
        if (uiState.showClearSuccess) {
            val message = "Cleared ${viewModel.formatBytes(uiState.lastClearedBytes)}"
            snackbarHostState.showSnackbar(message)
            viewModel.dismissClearSuccess()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Storage") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Calculating storage usage...")
                }
            }
        } else {
            StorageManagementContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                breakdown = uiState.breakdown,
                isClearing = uiState.isClearing,
                clearingCategory = uiState.clearingCategory,
                onClearCategory = viewModel::clearCategory,
                formatBytes = viewModel::formatBytes
            )
        }
    }
}

@Composable
private fun StorageManagementContent(
    modifier: Modifier = Modifier,
    breakdown: StorageBreakdown,
    isClearing: Boolean,
    clearingCategory: StorageCategory?,
    onClearCategory: (StorageCategory) -> Unit,
    formatBytes: (Long) -> String
) {
    var showClearAllDialog by remember { mutableStateOf(false) }
    var categoryToClear by remember { mutableStateOf<StorageCategory?>(null) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Total storage card
        TotalStorageCard(
            total = breakdown.total,
            formatBytes = formatBytes
        )

        // Storage breakdown
        Text(
            text = "Storage Breakdown",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp)
        )

        StorageBreakdownCard(
            breakdown = breakdown,
            formatBytes = formatBytes
        )

        // Category clear options
        Text(
            text = "Clear Cache",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp)
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column {
                StorageCategoryRow(
                    icon = Icons.Default.Image,
                    title = "Images",
                    subtitle = "Cached images and thumbnails",
                    size = breakdown.images,
                    formatBytes = formatBytes,
                    isClearing = clearingCategory == StorageCategory.IMAGES,
                    onClear = { categoryToClear = StorageCategory.IMAGES }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                StorageCategoryRow(
                    icon = Icons.Default.Videocam,
                    title = "Videos",
                    subtitle = "Cached videos and social media",
                    size = breakdown.videos,
                    formatBytes = formatBytes,
                    isClearing = clearingCategory == StorageCategory.VIDEOS,
                    onClear = { categoryToClear = StorageCategory.VIDEOS }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                StorageCategoryRow(
                    icon = Icons.Default.Article,
                    title = "Documents",
                    subtitle = "PDFs, audio, and other files",
                    size = breakdown.documents,
                    formatBytes = formatBytes,
                    isClearing = clearingCategory == StorageCategory.DOCUMENTS,
                    onClear = { categoryToClear = StorageCategory.DOCUMENTS }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                StorageCategoryRow(
                    icon = Icons.Default.Link,
                    title = "Downloaded Links",
                    subtitle = "Link previews and social media videos",
                    size = breakdown.linkPreviews,
                    formatBytes = formatBytes,
                    isClearing = clearingCategory == StorageCategory.LINK_PREVIEWS,
                    onClear = { categoryToClear = StorageCategory.LINK_PREVIEWS }
                )

                if (breakdown.other > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    StorageCategoryRow(
                        icon = Icons.Default.MoreHoriz,
                        title = "Other",
                        subtitle = "Temporary and system cache",
                        size = breakdown.other,
                        formatBytes = formatBytes,
                        isClearing = false,
                        onClear = null // Can't clear individually
                    )
                }
            }
        }

        // Clear all button
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { showClearAllDialog = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isClearing && breakdown.total > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.DeleteForever, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear All Cache")
        }

        Text(
            text = "This will not delete your messages or conversations.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))
    }

    // Clear category confirmation dialog
    categoryToClear?.let { category ->
        ClearConfirmationDialog(
            category = category,
            size = when (category) {
                StorageCategory.IMAGES -> breakdown.images
                StorageCategory.VIDEOS -> breakdown.videos
                StorageCategory.DOCUMENTS -> breakdown.documents
                StorageCategory.LINK_PREVIEWS -> breakdown.linkPreviews
                StorageCategory.ALL -> breakdown.total
            },
            formatBytes = formatBytes,
            onConfirm = {
                onClearCategory(category)
                categoryToClear = null
            },
            onDismiss = { categoryToClear = null }
        )
    }

    // Clear all confirmation dialog
    if (showClearAllDialog) {
        ClearConfirmationDialog(
            category = StorageCategory.ALL,
            size = breakdown.total,
            formatBytes = formatBytes,
            onConfirm = {
                onClearCategory(StorageCategory.ALL)
                showClearAllDialog = false
            },
            onDismiss = { showClearAllDialog = false }
        )
    }
}

@Composable
private fun TotalStorageCard(
    total: Long,
    formatBytes: (Long) -> String
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = "App Cache",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = formatBytes(total),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun StorageBreakdownCard(
    breakdown: StorageBreakdown,
    formatBytes: (Long) -> String
) {
    val total = breakdown.total.coerceAtLeast(1) // Prevent division by zero

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Visual breakdown bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                val categories = listOf(
                    breakdown.images to MaterialTheme.colorScheme.primary,
                    breakdown.videos to MaterialTheme.colorScheme.secondary,
                    breakdown.documents to MaterialTheme.colorScheme.tertiary,
                    breakdown.linkPreviews to MaterialTheme.colorScheme.error,
                    breakdown.other to MaterialTheme.colorScheme.outline
                ).filter { it.first > 0 }

                categories.forEach { (size, color) ->
                    val fraction by animateFloatAsState(
                        targetValue = (size.toFloat() / total).coerceIn(0.02f, 1f),
                        label = "bar_fraction"
                    )
                    Box(
                        modifier = Modifier
                            .weight(fraction)
                            .fillMaxSize()
                            .background(color)
                    )
                }

                if (categories.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StorageLegendItem("Images", MaterialTheme.colorScheme.primary)
                StorageLegendItem("Videos", MaterialTheme.colorScheme.secondary)
                StorageLegendItem("Docs", MaterialTheme.colorScheme.tertiary)
                StorageLegendItem("Links", MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun StorageLegendItem(
    label: String,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StorageCategoryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    size: Long,
    formatBytes: (Long) -> String,
    isClearing: Boolean,
    onClear: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = formatBytes(size),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        if (onClear != null && size > 0) {
            Spacer(modifier = Modifier.width(8.dp))

            if (isClearing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                FilledTonalButton(
                    onClick = onClear,
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Text("Clear")
                }
            }
        }
    }
}

@Composable
private fun ClearConfirmationDialog(
    category: StorageCategory,
    size: Long,
    formatBytes: (Long) -> String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val title = when (category) {
        StorageCategory.IMAGES -> "Clear Images?"
        StorageCategory.VIDEOS -> "Clear Videos?"
        StorageCategory.DOCUMENTS -> "Clear Documents?"
        StorageCategory.LINK_PREVIEWS -> "Clear Downloaded Links?"
        StorageCategory.ALL -> "Clear All Cache?"
    }

    val description = when (category) {
        StorageCategory.ALL -> "This will free up ${formatBytes(size)} by removing all cached files. Your messages and conversations will not be affected."
        else -> "This will free up ${formatBytes(size)}. These files can be re-downloaded when needed."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(description) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (category == StorageCategory.ALL) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            ) {
                Text("Clear")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
