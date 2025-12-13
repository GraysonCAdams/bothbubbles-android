package com.bothbubbles.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bothbubbles.data.model.AttachmentQuality

/**
 * Bottom sheet for selecting image quality when sending attachments.
 * Shows quality options with descriptions and highlights the current selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySelectionSheet(
    visible: Boolean,
    currentQuality: AttachmentQuality,
    onQualitySelected: (AttachmentQuality) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Title
            Text(
                text = "Image Quality",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // Info text
            Text(
                text = "Choose quality for attached images. Higher quality means larger files.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            Spacer(Modifier.height(8.dp))

            // Quality options
            AttachmentQuality.entries.forEach { quality ->
                QualityOption(
                    quality = quality,
                    isSelected = quality == currentQuality,
                    onClick = {
                        onQualitySelected(quality)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun QualityOption(
    quality: AttachmentQuality,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val icon = when (quality) {
        AttachmentQuality.AUTO -> Icons.Outlined.Tune
        AttachmentQuality.STANDARD -> Icons.Outlined.Image
        AttachmentQuality.HIGH -> Icons.Outlined.HighQuality
        AttachmentQuality.ORIGINAL -> Icons.Outlined.PhotoSizeSelectLarge
    }

    Surface(
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = quality.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = quality.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Check mark if selected
            if (isSelected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Compact quality indicator button for the message composer.
 * Shows current quality level and opens the selection sheet when clicked.
 */
@Composable
fun QualityIndicator(
    currentQuality: AttachmentQuality,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = when (currentQuality) {
        AttachmentQuality.AUTO -> Icons.Outlined.Tune
        AttachmentQuality.STANDARD -> Icons.Outlined.Image
        AttachmentQuality.HIGH -> Icons.Outlined.HighQuality
        AttachmentQuality.ORIGINAL -> Icons.Outlined.PhotoSizeSelectLarge
    }

    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(36.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Quality: ${currentQuality.displayName}",
            modifier = Modifier.size(20.dp)
        )
    }
}
