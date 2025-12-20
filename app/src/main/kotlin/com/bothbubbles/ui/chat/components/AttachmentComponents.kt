package com.bothbubbles.ui.chat.components

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bothbubbles.ui.chat.getAttachmentInfo

/**
 * Attachment preview thumbnail with remove button, edit button, file size, and video duration.
 * Uses MD3 ElevatedCard for proper elevation and surface treatment.
 */
@Composable
fun AttachmentPreview(
    uri: Uri,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    onEdit: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current

    // Get file info
    val fileInfo = remember(uri) {
        Log.d("AttachmentPreview", "Getting attachment info for URI: $uri")
        val info = getAttachmentInfo(context, uri)
        Log.d("AttachmentPreview", "Result: isVLocation=${info.isVLocation}, size=${info.formattedSize}")
        info
    }

    // MD3 ElevatedCard provides proper elevation and surface treatment
    // Size increased to 140dp (40% larger than original 100dp)
    ElevatedCard(
        modifier = modifier.size(140.dp),
        shape = MaterialTheme.shapes.medium, // MD3 medium shape (typically 12dp)
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        onClick = onClick ?: {}
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // vLocation: Show location icon placeholder
            if (fileInfo.isVLocation) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Current Location",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            } else {
                // Regular media: Show image/video preview
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Attachment",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // Semi-transparent gradient overlay at bottom for text visibility
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(32.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.7f)
                                )
                            )
                        )
                )

                // File size at bottom left, or caption indicator
                Text(
                    text = if (caption != null) "\uD83D\uDCAC" else fileInfo.formattedSize,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 6.dp)
                )

                // Video duration badge at bottom right (for videos)
                if (fileInfo.isVideo && fileInfo.durationFormatted != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 8.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = fileInfo.durationFormatted,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }

                // Edit button overlay (top left) - only show for images
                // Uses FilledTonalIconButton for MD3 consistency
                if (onEdit != null && fileInfo.isImage) {
                    FilledTonalIconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .size(17.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.6f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit attachment",
                            modifier = Modifier.size(10.dp)
                        )
                    }
                }
            }

            // Remove button overlay (top right) - always shown
            // Uses FilledTonalIconButton for MD3 consistency
            FilledTonalIconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(17.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove attachment",
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}
