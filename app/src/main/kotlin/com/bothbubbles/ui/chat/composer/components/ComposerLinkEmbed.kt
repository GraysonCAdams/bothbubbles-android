package com.bothbubbles.ui.chat.composer.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.bothbubbles.ui.chat.composer.ComposerLinkPreviewState
import com.bothbubbles.ui.components.common.openUrl

/**
 * Max width for the inline link embed card.
 */
private val LinkEmbedMaxWidth = 280.dp

/**
 * Inline link embed for the segmented composer.
 *
 * Displays a link preview as an inline card that can be:
 * - Removed via dismiss button
 * - Tapped to open URL
 *
 * Shows different states:
 * - Loading: Shimmer animation with domain name
 * - Success: Full preview card with image, title, description
 * - Failed/NoPreview: Minimal card with domain and link icon
 *
 * @param url The URL being previewed
 * @param domain The domain name to display
 * @param previewState Current preview fetch state
 * @param onRemove Callback when dismiss button is tapped
 * @param modifier Modifier for the container
 */
@Composable
fun ComposerLinkEmbed(
    url: String,
    domain: String,
    previewState: ComposerLinkPreviewState,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .widthIn(max = LinkEmbedMaxWidth)
            .clip(RoundedCornerShape(12.dp))
    ) {
        when (previewState) {
            is ComposerLinkPreviewState.Loading -> {
                LoadingContent(domain = domain)
            }
            is ComposerLinkPreviewState.Success -> {
                SuccessContent(
                    preview = previewState.preview,
                    onClick = { openUrl(context, url) }
                )
            }
            is ComposerLinkPreviewState.Failed,
            is ComposerLinkPreviewState.NoPreview -> {
                MinimalContent(
                    url = url,
                    domain = domain,
                    onClick = { openUrl(context, url) }
                )
            }
        }

        // Dismiss button (top right, always visible)
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(24.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = CircleShape
                )
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove link preview",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * Loading state with shimmer animation and domain name.
 */
@Composable
private fun LoadingContent(domain: String) {
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
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        // Image placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .background(brush)
        )

        // Text content
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            // Domain with loading indicator
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = domain,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Title placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(brush)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Description placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(brush)
            )
        }
    }
}

/**
 * Success state with full preview content.
 */
@Composable
private fun SuccessContent(
    preview: LinkPreviewEntity,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
    ) {
        // Image (if available)
        preview.imageUrl?.let { imageUrl ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                contentAlignment = Alignment.Center
            ) {
                // Shimmer while loading
                if (imageState is AsyncImagePainter.State.Loading || imageState is AsyncImagePainter.State.Empty) {
                    ShimmerBox(
                        modifier = Modifier.fillMaxSize()
                    )
                }

                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Preview image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onState = { state -> imageState = state }
                )

                // Video play button
                if (preview.isVideo && imageState is AsyncImagePainter.State.Success) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Video",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
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
            // Site name
            Text(
                text = preview.displaySiteName.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Title
            preview.title?.takeIf { it.isNotBlank() }?.let { title ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Minimal content for failed or no-preview states.
 * Shows domain with link icon (like the screenshot reference).
 */
@Composable
private fun MinimalContent(
    url: String,
    domain: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
    ) {
        // Placeholder image area with link icon
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Link,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        // Domain
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = domain,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Simple shimmer box for loading states.
 */
@Composable
private fun ShimmerBox(modifier: Modifier = Modifier) {
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

    Box(
        modifier = modifier.background(brush)
    )
}
