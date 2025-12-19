package com.bothbubbles.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bothbubbles.data.local.db.entity.AttachmentEntity

@Composable
internal fun MediaPage(
    attachment: AttachmentEntity,
    onTap: () -> Unit,
    bottomPadding: Dp = 0.dp
) {
    val mediaUrl = attachment.localPath ?: attachment.webUrl

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            mediaUrl == null -> {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp)
                )
            }
            attachment.isImage -> {
                ZoomableImage(
                    imageUrl = mediaUrl,
                    contentDescription = attachment.transferName ?: "Image",
                    onTap = onTap
                )
            }
            attachment.isVideo -> {
                VideoPlayer(
                    videoUrl = mediaUrl,
                    onTap = onTap,
                    bottomPadding = bottomPadding
                )
            }
            else -> {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp)
                )
            }
        }
    }
}
