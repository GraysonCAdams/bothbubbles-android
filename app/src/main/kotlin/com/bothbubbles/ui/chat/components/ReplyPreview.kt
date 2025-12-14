package com.bothbubbles.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.theme.BothBubblesTheme

/**
 * Reply preview shown above the input when replying to a message.
 * Shows a thumbnail for media attachments.
 */
@Composable
fun ReplyPreview(
    message: MessageUiModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val inputColors = BothBubblesTheme.bubbleColors

    // Get first media attachment for thumbnail preview
    val mediaAttachment = message.attachments.firstOrNull { it.isImage || it.isVideo }
    val thumbnailUrl = mediaAttachment?.let {
        it.thumbnailPath ?: it.localPath ?: it.webUrl
    }

    // Build preview text - show attachment type if no text
    val previewText = when {
        !message.text.isNullOrBlank() -> message.text
        mediaAttachment?.isVideo == true -> "Video"
        mediaAttachment?.isImage == true -> "Photo"
        message.attachments.isNotEmpty() -> "Attachment"
        else -> ""
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = inputColors.inputBackground
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Replying to ${if (message.isFromMe) "yourself" else message.senderName ?: "message"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Media thumbnail preview
            if (thumbnailUrl != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(thumbnailUrl)
                            .crossfade(true)
                            .size(108) // 36dp * 3x density
                            .build(),
                        contentDescription = "Attachment preview",
                        modifier = Modifier.size(36.dp),
                        contentScale = ContentScale.Crop
                    )
                    // Video play icon overlay
                    if (mediaAttachment?.isVideo == true) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.Black.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel reply",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
