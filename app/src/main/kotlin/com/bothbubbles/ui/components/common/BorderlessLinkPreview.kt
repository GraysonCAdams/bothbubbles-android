package com.bothbubbles.ui.components.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.bothbubbles.data.local.db.entity.LinkPreviewEntity

/**
 * Borderless link preview components.
 * Used when rendering link previews outside message bubbles as standalone elements.
 * These render without Card containers, using only subtle rounded corners.
 */

/**
 * Borderless link preview card - renders without Card container.
 * Uses only subtle rounded corners for visual polish.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BorderlessLinkPreviewCard(
    preview: LinkPreviewEntity,
    isFromMe: Boolean,
    modifier: Modifier = Modifier,
    maxWidth: androidx.compose.ui.unit.Dp = 240.dp,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current

    val handleClick = onClick ?: {
        openUrl(context, preview.url)
    }

    val handleLongClick = {
        copyUrlToClipboard(context, preview.url)
    }

    val textColor = MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    // No Card container - just a Column with rounded corners
    Column(
        modifier = modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = handleClick,
                onLongClick = handleLongClick
            )
    ) {
        // Thumbnail image (if available)
        preview.imageUrl?.let { imageUrl ->
            val imageRequest = remember(imageUrl) {
                ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .build()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = "Link preview image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(
                            if (preview.title.isNullOrBlank() && preview.description.isNullOrBlank()) {
                                RoundedCornerShape(12.dp)
                            } else {
                                RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                            }
                        ),
                    contentScale = ContentScale.Crop
                )

                // Play button overlay for video content
                if (preview.isVideo) {
                    Surface(
                        shape = CircleShape,
                        color = videoOverlayColor,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Video",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
        }

        // Text content (with subtle background for readability)
        if (!preview.title.isNullOrBlank() || !preview.description.isNullOrBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clip(
                        if (preview.imageUrl == null) {
                            RoundedCornerShape(12.dp)
                        } else {
                            RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                        }
                    )
                    .padding(10.dp)
            ) {
                // Site name / domain
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    preview.faviconUrl?.let { faviconUrl ->
                        val faviconRequest = remember(faviconUrl) {
                            ImageRequest.Builder(context)
                                .data(faviconUrl)
                                .crossfade(true)
                                .size(32)
                                .build()
                        }
                        AsyncImage(
                            model = faviconRequest,
                            contentDescription = null,
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Text(
                        text = preview.displaySiteName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Title
                preview.title?.takeIf { it.isNotBlank() }?.let { title ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = textColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Description
                preview.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Borderless minimal link preview (when no metadata available)
 */
@Composable
fun BorderlessLinkPreviewMinimal(
    url: String,
    domain: String,
    modifier: Modifier = Modifier,
    maxWidth: androidx.compose.ui.unit.Dp = 240.dp
) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable {
                openUrl(context, url)
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Link,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = textColor
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = domain,
            style = MaterialTheme.typography.bodySmall,
            color = textColor
        )
    }
}
