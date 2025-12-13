package com.bothbubbles.ui.components.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(preview.url))
            context.startActivity(intent)
        } catch (e: Exception) {
            // Ignore if no browser available
        }
    }

    val handleLongClick = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("URL", preview.url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
    }

    // Use theme-aware colors for both sent and received messages
    // This ensures proper contrast in both light and dark modes
    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    )

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
                        .height(140.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = "Link preview image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentScale = ContentScale.Crop,
                        onState = { state ->
                            // Could handle loading/error states here
                        }
                    )

                    // Play button overlay for video content
                    if (preview.isVideo) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.6f),
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
 * Loading state for link preview
 */
@Composable
fun LinkPreviewLoading(
    domain: String,
    isFromMe: Boolean,
    modifier: Modifier = Modifier
) {
    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    )

    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        colors = cardColors,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = textColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Loading preview from $domain...",
                style = MaterialTheme.typography.bodySmall,
                color = textColor
            )
        }
    }
}

/**
 * Error/retry state for link preview
 */
@Composable
fun LinkPreviewError(
    url: String,
    domain: String,
    isFromMe: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    )

    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clickable {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Ignore
                }
            },
        colors = cardColors,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
                color = textColor,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onRetry,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Retry",
                    modifier = Modifier.size(16.dp),
                    tint = textColor
                )
            }
        }
    }
}

/**
 * Minimal link preview shown when no metadata is available
 */
@Composable
fun LinkPreviewMinimal(
    url: String,
    domain: String,
    isFromMe: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    )

    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clickable {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Ignore
                }
            },
        colors = cardColors,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
}

// =============================================================================
// BORDERLESS LINK PREVIEW COMPONENTS
// Used when rendering link previews outside message bubbles as standalone elements
// =============================================================================

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
    maxWidth: androidx.compose.ui.unit.Dp = 300.dp,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current

    val handleClick = onClick ?: {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(preview.url))
            context.startActivity(intent)
        } catch (e: Exception) {
            // Ignore if no browser available
        }
    }

    val handleLongClick = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("URL", preview.url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
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
                        color = Color.Black.copy(alpha = 0.6f),
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
 * Borderless shimmer loading for link preview
 */
@Composable
fun BorderlessLinkPreviewShimmer(
    showImage: Boolean = true,
    modifier: Modifier = Modifier,
    maxWidth: androidx.compose.ui.unit.Dp = 300.dp
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200)
        ),
        label = "shimmer"
    )

    val shimmerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val shimmerHighlight = MaterialTheme.colorScheme.surfaceContainerHighest

    val brush = Brush.linearGradient(
        colors = listOf(shimmerColor, shimmerHighlight, shimmerColor),
        start = Offset(translateAnim - 500f, 0f),
        end = Offset(translateAnim, 0f)
    )

    Column(
        modifier = modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(12.dp))
    ) {
        // Image placeholder
        if (showImage) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(brush)
            )
        }

        // Text placeholders
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                .padding(10.dp)
        ) {
            // Site name placeholder
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(brush)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Title placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(brush)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Description placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(brush)
            )
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
    maxWidth: androidx.compose.ui.unit.Dp = 300.dp
) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Ignore
                }
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

/**
 * Shimmer loading effect for link preview
 */
@Composable
fun LinkPreviewShimmer(
    isFromMe: Boolean,
    showImage: Boolean = true,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200)
        ),
        label = "shimmer"
    )

    val shimmerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val shimmerHighlight = MaterialTheme.colorScheme.surfaceContainerHighest

    val brush = Brush.linearGradient(
        colors = listOf(shimmerColor, shimmerHighlight, shimmerColor),
        start = Offset(translateAnim - 500f, 0f),
        end = Offset(translateAnim, 0f)
    )

    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        colors = cardColors,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            // Image placeholder
            if (showImage) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(brush)
                )
            }

            // Text placeholders
            Column(
                modifier = Modifier.padding(10.dp)
            ) {
                // Site name placeholder
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(brush)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Title placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(brush)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Description placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(brush)
                )
            }
        }
    }
}
