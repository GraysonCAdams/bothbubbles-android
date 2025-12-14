package com.bothbubbles.ui.components.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.bothbubbles.data.local.db.entity.LinkPreviewEntity

/**
 * Link preview card shown inside message bubbles.
 * Displays title, description, and thumbnail image from URL metadata.
 * Tap to open URL, long-press to copy URL to clipboard.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LinkPreviewCard(
    preview: LinkPreviewEntity,
    isFromMe: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current

    val handleClick = onClick ?: {
        openUrl(context, preview.url)
    }

    val handleLongClick = {
        copyUrlToClipboard(context, preview.url)
    }

    // Use theme-aware colors for both sent and received messages
    // This ensures proper contrast in both light and dark modes
    val cardColors = linkPreviewCardColors(LinkPreviewSurfaceLevel.Highest)

    val textColor = MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .combinedClickable(
                onClick = handleClick,
                onLongClick = handleLongClick
            ),
        colors = cardColors,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            // Thumbnail image (if available) with shimmer loading
            preview.imageUrl?.let { imageUrl ->
                var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
                val imageRequest = remember(imageUrl) {
                    ImageRequest.Builder(context)
                        .data(imageUrl)
                        .crossfade(true)
                        .build()
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // Shimmer placeholder while loading
                    if (imageState is AsyncImagePainter.State.Loading || imageState is AsyncImagePainter.State.Empty) {
                        ShimmerPlaceholder(
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    AsyncImage(
                        model = imageRequest,
                        contentDescription = "Link preview image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentScale = ContentScale.Crop,
                        onState = { state ->
                            imageState = state
                        }
                    )

                    // Play button overlay for video content
                    if (preview.isVideo && imageState is AsyncImagePainter.State.Success) {
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

            // Text content
            Column(
                modifier = Modifier.padding(10.dp)
            ) {
                // Site name / domain
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Favicon
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
                if (!preview.title.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = preview.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = textColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Description
                if (!preview.description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = preview.description,
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
 * Skeleton loading placeholder for link previews.
 * Shows shimmer effect while OpenGraph data is being fetched.
 */
@Composable
fun LinkPreviewSkeleton(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            // Image placeholder with shimmer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            ) {
                ShimmerPlaceholder(modifier = Modifier.fillMaxSize())
            }

            // Text content placeholders
            Column(
                modifier = Modifier.padding(10.dp)
            ) {
                // Site name placeholder
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    ShimmerPlaceholder(modifier = Modifier.fillMaxSize())
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Title placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    ShimmerPlaceholder(modifier = Modifier.fillMaxSize())
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Second title line placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    ShimmerPlaceholder(modifier = Modifier.fillMaxSize())
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Description placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    ShimmerPlaceholder(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
