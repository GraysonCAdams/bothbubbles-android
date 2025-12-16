package com.bothbubbles.ui.chat.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bothbubbles.data.model.AttachmentQuality
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.ui.chat.AttachmentWarning
import com.bothbubbles.ui.theme.BubbleColors

/**
 * Displays pending attachments in a horizontal strip with a header showing count and quality options.
 *
 * @param attachments List of pending attachments to display
 * @param onRemoveAttachment Callback when an attachment should be removed
 * @param onClearAllAttachments Callback to clear all attachments
 * @param onEditAttachment Callback when an attachment should be edited
 * @param onReorderAttachments Callback when attachments are reordered
 * @param hasCompressibleImages Whether any attachments are compressible images
 * @param currentImageQuality Current image quality setting
 * @param onQualityClick Callback when quality button is clicked
 * @param inputColors Theme colors for styling
 */
@Composable
fun ChatAttachmentStrip(
    attachments: List<PendingAttachmentInput>,
    onRemoveAttachment: (Uri) -> Unit,
    onClearAllAttachments: () -> Unit,
    onEditAttachment: (Uri) -> Unit,
    onReorderAttachments: (List<PendingAttachmentInput>) -> Unit,
    hasCompressibleImages: Boolean,
    currentImageQuality: AttachmentQuality,
    onQualityClick: () -> Unit,
    inputColors: BubbleColors,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Header row with attachment count and quality indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${attachments.size} attachment${if (attachments.size > 1) "s" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = inputColors.inputText.copy(alpha = 0.7f)
                )

                // Quality indicator button - shown only when there are compressible image attachments
                if (hasCompressibleImages) {
                    QualityIndicator(
                        currentQuality = currentImageQuality,
                        onClick = onQualityClick
                    )
                }
            }

            if (attachments.size > 1) {
                TextButton(
                    onClick = onClearAllAttachments,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "Clear All",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Reorderable attachment thumbnails
        ReorderableAttachmentStrip(
            attachments = attachments,
            onRemove = onRemoveAttachment,
            onEdit = onEditAttachment,
            onReorder = onReorderAttachments,
            modifier = Modifier.fillMaxWidth()
        )

        // Secondary quality button row (only if compressible images present)
        if (hasCompressibleImages) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    onClick = onQualityClick,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.height(32.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Image,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = currentImageQuality.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

/**
 * Warning banner for attachment size issues.
 *
 * @param warning The warning to display, or null if no warning
 * @param onDismiss Callback when warning is dismissed
 * @param onRemoveAttachment Callback when problematic attachment should be removed
 */
@Composable
fun AttachmentWarningBanner(
    warning: AttachmentWarning?,
    onDismiss: () -> Unit,
    onRemoveAttachment: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = warning != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        warning?.let { currentWarning ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                color = if (currentWarning.isError)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.tertiaryContainer,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (currentWarning.isError)
                                Icons.Default.ErrorOutline
                            else
                                Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (currentWarning.isError)
                                MaterialTheme.colorScheme.onErrorContainer
                            else
                                MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = currentWarning.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (currentWarning.isError)
                                MaterialTheme.colorScheme.onErrorContainer
                            else
                                MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (currentWarning.isError && currentWarning.affectedUri != null) {
                            TextButton(
                                onClick = onRemoveAttachment,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = "Remove",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        } else if (!currentWarning.isError) {
                            TextButton(
                                onClick = onDismiss,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = "Dismiss",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
