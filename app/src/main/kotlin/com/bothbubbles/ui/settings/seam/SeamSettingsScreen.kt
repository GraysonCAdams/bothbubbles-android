package com.bothbubbles.ui.settings.seam

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.core.data.prefs.SendModeBehavior
import com.bothbubbles.seam.stitches.StitchConnectionState
import com.bothbubbles.seam.stitches.bluebubbles.BlueBubblesStitch
import com.bothbubbles.seam.stitches.sms.SmsStitch
import com.bothbubbles.ui.settings.components.SettingsCard
import com.bothbubbles.ui.settings.components.SettingsIconColors
import com.bothbubbles.ui.settings.components.SettingsMenuItem
import com.bothbubbles.ui.settings.components.SettingsSectionTitle
import com.bothbubbles.util.HapticUtils
import kotlin.math.roundToInt

/**
 * Settings screen for managing Stitches (messaging platforms) and Features (enhancements).
 *
 * User-facing terminology:
 * - "Stitches" = Platform integrations (SMS, iMessage)
 * - "Hems" = Cross-platform features (Reels) - shown as "Features" in UI for clarity
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeamSettingsScreen(
    onNavigateBack: () -> Unit,
    onStitchSettingsClick: (route: String) -> Unit,
    onFeatureSettingsClick: (route: String) -> Unit,
    viewModel: SeamSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Messaging Platforms") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        SeamSettingsContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            uiState = uiState,
            listState = listState,
            canDisableStitch = viewModel::canDisableStitch,
            onStitchSettingsClick = { stitchId ->
                viewModel.getStitchSettingsRoute(stitchId)?.let { route ->
                    onStitchSettingsClick(route)
                }
            },
            onFeatureSettingsClick = { featureId ->
                viewModel.getFeatureSettingsRoute(featureId)?.let { route ->
                    onFeatureSettingsClick(route)
                }
            },
            onReorderStitches = viewModel::setPriorityOrder,
            onToggleSendModeBehavior = viewModel::toggleSendModeBehavior
        )
    }
}

@Composable
private fun SeamSettingsContent(
    modifier: Modifier = Modifier,
    uiState: SeamSettingsUiState,
    listState: LazyListState,
    canDisableStitch: (String) -> Boolean,
    onStitchSettingsClick: (stitchId: String) -> Unit,
    onFeatureSettingsClick: (featureId: String) -> Unit,
    onReorderStitches: (List<String>) -> Unit,
    onToggleSendModeBehavior: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // Drag state for priority reordering
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var workingOrder by remember(uiState.priorityOrder) { mutableStateOf(uiState.priorityOrder.toList()) }

    // Item height for calculating swap positions
    val itemHeightPx = with(density) { 72.dp.toPx() }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
    ) {
        // ═══════════════════════════════════════════════════════════════
        // SECTION: Platform Priority
        // ═══════════════════════════════════════════════════════════════
        item {
            SettingsSectionTitle(title = "Platform Priority")
        }

        item {
            Text(
                text = "Drag to set priority order for automatic platform selection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column {
                    workingOrder.forEachIndexed { index, stitchId ->
                        val stitch = uiState.stitches.find { it.id == stitchId }
                        if (stitch != null) {
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }

                            val isBeingDragged = index == draggedIndex && isDragging
                            val scale by animateFloatAsState(
                                targetValue = if (isBeingDragged) 1.02f else 1f,
                                animationSpec = spring(),
                                label = "priorityScale"
                            )
                            val elevation by animateDpAsState(
                                targetValue = if (isBeingDragged) 8.dp else 0.dp,
                                animationSpec = spring(),
                                label = "priorityElevation"
                            )
                            val offsetY = if (isBeingDragged) dragOffset else 0f

                            Box(
                                modifier = Modifier
                                    .zIndex(if (isBeingDragged) 1f else 0f)
                                    .offset { IntOffset(0, offsetY.roundToInt()) }
                                    .scale(scale)
                                    .shadow(elevation, RoundedCornerShape(12.dp))
                                    .background(
                                        if (isBeingDragged) {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        }
                                    )
                            ) {
                                PriorityStitchRow(
                                    priorityNumber = index + 1,
                                    stitch = stitch,
                                    onSettingsClick = { onStitchSettingsClick(stitch.id) },
                                    onDragStart = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        draggedIndex = index
                                        isDragging = true
                                        dragOffset = 0f
                                    },
                                    onDrag = { dragAmount ->
                                        dragOffset += dragAmount

                                        // Calculate target index based on drag offset
                                        val offsetItems = (dragOffset / itemHeightPx).roundToInt()
                                        val targetIndex = (draggedIndex + offsetItems)
                                            .coerceIn(0, workingOrder.lastIndex)

                                        // Swap items if target changed
                                        if (targetIndex != draggedIndex && targetIndex in workingOrder.indices) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            workingOrder = workingOrder.toMutableList().apply {
                                                val item = removeAt(draggedIndex)
                                                add(targetIndex, item)
                                            }
                                            // Reset offset and update dragged index
                                            dragOffset -= (targetIndex - draggedIndex) * itemHeightPx
                                            draggedIndex = targetIndex
                                        }
                                    },
                                    onDragEnd = {
                                        isDragging = false
                                        dragOffset = 0f
                                        // Notify parent of reorder
                                        if (workingOrder != uiState.priorityOrder.toList()) {
                                            onReorderStitches(workingOrder)
                                        }
                                        draggedIndex = -1
                                    },
                                    onDragCancel = {
                                        isDragging = false
                                        dragOffset = 0f
                                        workingOrder = uiState.priorityOrder.toList()
                                        draggedIndex = -1
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION: Send Mode Behavior
        // ═══════════════════════════════════════════════════════════════
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SettingsSectionTitle(title = "New Conversations")
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SendModeBehaviorRow(
                    behavior = uiState.sendModeBehavior,
                    onToggle = onToggleSendModeBehavior
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION: Stitches (Platform Integrations)
        // ═══════════════════════════════════════════════════════════════
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SettingsSectionTitle(title = "Platform Settings")
        }

        item {
            Text(
                text = "Configure individual platform settings and colors.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                uiState.stitches.forEachIndexed { index, stitch ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    StitchRow(
                        stitch = stitch,
                        canDisable = canDisableStitch(stitch.id),
                        onSettingsClick = { onStitchSettingsClick(stitch.id) }
                    )
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION: Features (Cross-Platform Enhancements)
        // ═══════════════════════════════════════════════════════════════
        if (uiState.features.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionTitle(title = "Features")
            }

            item {
                Text(
                    text = "Enhancements that work across all platforms.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            item {
                SettingsCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    uiState.features.forEachIndexed { index, feature ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        FeatureRow(
                            feature = feature,
                            onSettingsClick = { onFeatureSettingsClick(feature.id) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Row for priority ordering with drag handle.
 */
@Composable
private fun PriorityStitchRow(
    priorityNumber: Int,
    stitch: StitchUiModel,
    onSettingsClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    // Get icon based on stitch ID
    val icon = when (stitch.id) {
        BlueBubblesStitch.ID -> Icons.Default.Cloud
        SmsStitch.ID -> Icons.Default.CellTower
        else -> Icons.Default.Extension
    }

    // Connection state to color
    val statusColor = when (stitch.connectionState) {
        StitchConnectionState.Connected -> MaterialTheme.colorScheme.tertiary
        StitchConnectionState.Connecting -> MaterialTheme.colorScheme.secondary
        StitchConnectionState.Disconnected -> MaterialTheme.colorScheme.error
        StitchConnectionState.NotConfigured -> MaterialTheme.colorScheme.outline
        is StitchConnectionState.Error -> MaterialTheme.colorScheme.error
    }

    val animatedStatusColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "priorityStatusColor"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Drag handle
        Box(
            modifier = Modifier
                .size(40.dp)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.y)
                        },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Priority number badge
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = priorityNumber.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Platform icon
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SettingsIconColors.Connectivity,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Platform name
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stitch.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Status indicator dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(animatedStatusColor)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Settings arrow if has settings
        if (stitch.hasSettings) {
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Configure",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Row for send mode behavior toggle.
 */
@Composable
private fun SendModeBehaviorRow(
    behavior: SendModeBehavior,
    onToggle: () -> Unit
) {
    val isPromptMode = behavior == SendModeBehavior.PROMPT_FIRST_TIME

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Ask before sending",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isPromptMode) {
                    "Show platform choice for new conversations"
                } else {
                    "Automatically use highest priority platform"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Switch(
            checked = isPromptMode,
            onCheckedChange = { onToggle() }
        )
    }
}

@Composable
private fun StitchRow(
    stitch: StitchUiModel,
    canDisable: Boolean,
    onSettingsClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    // Get icon based on stitch ID
    val icon = when (stitch.id) {
        BlueBubblesStitch.ID -> Icons.Default.Cloud
        SmsStitch.ID -> Icons.Default.CellTower
        else -> Icons.Default.Extension
    }

    // Connection state to color and text
    val (statusColor, statusText) = when (stitch.connectionState) {
        StitchConnectionState.Connected -> MaterialTheme.colorScheme.tertiary to "Connected"
        StitchConnectionState.Connecting -> MaterialTheme.colorScheme.secondary to "Connecting..."
        StitchConnectionState.Disconnected -> MaterialTheme.colorScheme.error to "Disconnected"
        StitchConnectionState.NotConfigured -> MaterialTheme.colorScheme.outline to "Not configured"
        is StitchConnectionState.Error -> MaterialTheme.colorScheme.error to "Error"
    }

    val animatedStatusColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "statusColor"
    )

    SettingsMenuItem(
        icon = icon,
        title = stitch.displayName,
        iconTint = SettingsIconColors.Connectivity,
        subtitle = statusText,
        onClick = {
            if (stitch.hasSettings) {
                HapticUtils.onTap(haptic)
                onSettingsClick()
            }
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status indicator dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(animatedStatusColor)
                )

                // Settings arrow if has settings
                if (stitch.hasSettings) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Configure",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

@Composable
private fun FeatureRow(
    feature: FeatureUiModel,
    onSettingsClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    // Get icon based on feature ID
    val icon = when (feature.id) {
        "reels" -> Icons.Default.Slideshow
        else -> Icons.Default.Extension
    }

    val statusText = if (feature.isEnabled) "Enabled" else "Disabled"
    val statusColor = if (feature.isEnabled) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.outline
    }

    val animatedStatusColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "featureStatusColor"
    )

    SettingsMenuItem(
        icon = icon,
        title = feature.displayName,
        iconTint = SettingsIconColors.Messaging,
        subtitle = feature.description,
        onClick = {
            if (feature.hasSettings) {
                HapticUtils.onTap(haptic)
                onSettingsClick()
            }
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status indicator dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(animatedStatusColor)
                )

                // Settings arrow if has settings
                if (feature.hasSettings) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Configure",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

/**
 * Composable content version for embedding in SettingsPanel.
 */
@Composable
fun SeamSettingsContent(
    modifier: Modifier = Modifier,
    onStitchSettingsClick: (route: String) -> Unit = {},
    onFeatureSettingsClick: (route: String) -> Unit = {},
    viewModel: SeamSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    SeamSettingsContent(
        modifier = modifier,
        uiState = uiState,
        listState = listState,
        canDisableStitch = viewModel::canDisableStitch,
        onStitchSettingsClick = { stitchId ->
            viewModel.getStitchSettingsRoute(stitchId)?.let { route ->
                onStitchSettingsClick(route)
            }
        },
        onFeatureSettingsClick = { featureId ->
            viewModel.getFeatureSettingsRoute(featureId)?.let { route ->
                onFeatureSettingsClick(route)
            }
        },
        onReorderStitches = viewModel::setPriorityOrder,
        onToggleSendModeBehavior = viewModel::toggleSendModeBehavior
    )
}
