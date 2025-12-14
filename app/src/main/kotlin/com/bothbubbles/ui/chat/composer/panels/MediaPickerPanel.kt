package com.bothbubbles.ui.chat.composer.panels

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.chat.composer.animations.ComposerMotionTokens

/**
 * Media picker option type for the grid.
 */
enum class MediaPickerOption(
    val label: String,
    val icon: ImageVector,
    val contentDescription: String,
    val backgroundColor: Color
) {
    GALLERY(
        label = "Gallery",
        icon = Icons.Outlined.Image,
        contentDescription = "Open photo gallery",
        backgroundColor = Color(0xFF4CAF50) // Green
    ),
    CAMERA(
        label = "Camera",
        icon = Icons.Outlined.CameraAlt,
        contentDescription = "Take a photo",
        backgroundColor = Color(0xFF2196F3) // Blue
    ),
    GIF(
        label = "GIF",
        icon = Icons.Outlined.Gif,
        contentDescription = "Search for GIFs",
        backgroundColor = Color(0xFF9C27B0) // Purple
    ),
    FILES(
        label = "Files",
        icon = Icons.AutoMirrored.Outlined.InsertDriveFile,
        contentDescription = "Attach a file",
        backgroundColor = Color(0xFFFF9800) // Orange
    ),
    LOCATION(
        label = "Location",
        icon = Icons.Outlined.LocationOn,
        contentDescription = "Share location",
        backgroundColor = Color(0xFFE91E63) // Pink
    ),
    AUDIO(
        label = "Audio",
        icon = Icons.Outlined.Mic,
        contentDescription = "Record audio message",
        backgroundColor = Color(0xFF00BCD4) // Cyan
    ),
    CONTACT(
        label = "Contact",
        icon = Icons.Outlined.Contacts,
        contentDescription = "Share a contact",
        backgroundColor = Color(0xFF795548) // Brown
    )
}

/**
 * Google Messages-style media picker panel.
 *
 * Displays a grid of options for attaching media:
 * - Gallery (opens Android Photo Picker)
 * - Camera (opens camera for capture)
 * - GIF (opens GIF search panel)
 * - Files (opens file picker)
 * - Location (shares current location)
 * - Audio (starts voice recording)
 * - Contact (shares a contact)
 *
 * @param visible Whether the panel is visible
 * @param onMediaSelected Callback when media is selected from gallery
 * @param onCameraClick Callback when camera option is tapped
 * @param onGifClick Callback when GIF option is tapped
 * @param onFileClick Callback when files option is tapped
 * @param onLocationClick Callback when location option is tapped
 * @param onAudioClick Callback when audio option is tapped
 * @param onContactClick Callback when contact option is tapped
 * @param onDismiss Callback when panel should be dismissed
 * @param modifier Modifier for the panel
 */
@Composable
fun MediaPickerPanel(
    visible: Boolean,
    onMediaSelected: (List<Uri>) -> Unit,
    onCameraClick: () -> Unit,
    onGifClick: () -> Unit,
    onFileClick: () -> Unit,
    onLocationClick: () -> Unit,
    onAudioClick: () -> Unit,
    onContactClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Android Photo Picker launcher
    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onMediaSelected(uris)
            onDismiss()
        }
    }

    val options = remember {
        listOf(
            MediaPickerOption.GALLERY,
            MediaPickerOption.CAMERA,
            MediaPickerOption.GIF,
            MediaPickerOption.FILES,
            MediaPickerOption.LOCATION,
            MediaPickerOption.AUDIO,
            MediaPickerOption.CONTACT
        )
    }

    // Drag state for swipe-to-dismiss
    val density = LocalDensity.current
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val dismissThreshold = with(density) { 120.dp.toPx() } // 120dp threshold for dismissal

    val draggableState = rememberDraggableState { delta ->
        // Only allow dragging down (positive delta)
        dragOffset = (dragOffset + delta).coerceAtLeast(0f)
    }

    // Animate back to 0 only when not actively dragging
    val displayOffset by animateFloatAsState(
        targetValue = if (isDragging) dragOffset else 0f,
        animationSpec = spring(
            dampingRatio = ComposerMotionTokens.Spring.Responsive.dampingRatio,
            stiffness = ComposerMotionTokens.Spring.Responsive.stiffness
        ),
        label = "dragOffset"
    )

    // Use raw offset while dragging, animated offset when releasing
    // Convert pixels to dp for the offset modifier
    val currentOffsetDp = with(density) {
        (if (isDragging) dragOffset else displayOffset).toDp()
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(
                dampingRatio = ComposerMotionTokens.Spring.Responsive.dampingRatio,
                stiffness = ComposerMotionTokens.Spring.Responsive.stiffness
            )
        ) + fadeIn(tween(ComposerMotionTokens.Duration.FAST)),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(ComposerMotionTokens.Duration.NORMAL)
        ) + fadeOut(tween(ComposerMotionTokens.Duration.FAST)),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = currentOffsetDp)
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Vertical,
                    onDragStarted = { isDragging = true },
                    onDragStopped = {
                        isDragging = false
                        if (dragOffset > dismissThreshold) {
                            onDismiss()
                        }
                        dragOffset = 0f
                    }
                ),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp)
            ) {
                // Drag handle (visual indicator for swipe-to-dismiss)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(32.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )

                // Options grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(options) { option ->
                        MediaPickerOptionItem(
                            option = option,
                            onClick = {
                                when (option) {
                                    MediaPickerOption.GALLERY -> {
                                        pickMedia.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                            )
                                        )
                                    }
                                    MediaPickerOption.CAMERA -> {
                                        onCameraClick()
                                        onDismiss()
                                    }
                                    MediaPickerOption.GIF -> {
                                        onGifClick()
                                        // Don't dismiss - switch to GIF panel
                                    }
                                    MediaPickerOption.FILES -> {
                                        onFileClick()
                                        onDismiss()
                                    }
                                    MediaPickerOption.LOCATION -> {
                                        onLocationClick()
                                        onDismiss()
                                    }
                                    MediaPickerOption.AUDIO -> {
                                        onAudioClick()
                                        onDismiss()
                                    }
                                    MediaPickerOption.CONTACT -> {
                                        onContactClick()
                                        onDismiss()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual media picker option item.
 */
@Composable
private fun MediaPickerOptionItem(
    option: MediaPickerOption,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .semantics {
                contentDescription = option.contentDescription
                role = Role.Button
            }
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Icon container with colored background
        Surface(
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(16.dp),
            color = option.backgroundColor.copy(alpha = 0.15f)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = option.icon,
                    contentDescription = null,
                    tint = option.backgroundColor,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Label
        Text(
            text = option.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
