package com.bothbubbles.ui.components.attachment

import android.net.Uri
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import com.bothbubbles.ui.theme.MotionTokens
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import coil.size.Precision
import com.bothbubbles.ui.components.message.AttachmentUiModel

/**
 * Renders an image attachment within a message bubble.
 * Handles loading, error states, transparency, and upload progress.
 *
 * @param attachment The attachment to render
 * @param interactions Callbacks and state for user interactions
 * @param modifier Modifier for the component
 */
@Composable
fun ImageAttachment(
    attachment: AttachmentUiModel,
    interactions: AttachmentInteractions,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    // Use full-resolution image for crisp display in chat (thumbnails are only 300px)
    // Coil handles memory-efficient loading via size() constraint
    val imageUrl = attachment.localPath ?: attachment.webUrl

    // Calculate aspect ratio for proper sizing
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        1f
    }

    // For transparent images, don't clip corners or add background
    val isTransparent = attachment.mayHaveTransparency

    // Calculate target size in pixels for memory-efficient loading
    val density = LocalDensity.current
    val maxWidthPx = with(density) { 250.dp.toPx().toInt() }
    val targetHeightPx = (maxWidthPx / aspectRatio.coerceIn(0.5f, 2f)).toInt()

    Box(
        modifier = modifier
            .widthIn(max = 250.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = interactions.onClick),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .size(maxWidthPx, targetHeightPx)
                    .precision(Precision.INEXACT)
                    .build(),
                contentDescription = attachment.transferName ?: "Image",
                modifier = Modifier
                    .widthIn(max = 250.dp)
                    .aspectRatio(aspectRatio.coerceIn(0.5f, 2f)),
                contentScale = ContentScale.Fit,
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                    isError = state is AsyncImagePainter.State.Error
                }
            )
        } else {
            isError = true
        }

        // Loading indicator with fade animation - minimal for transparent images
        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(tween(MotionTokens.Duration.QUICK, easing = MotionTokens.Easing.Standard)),
            exit = fadeOut(tween(MotionTokens.Duration.FAST, easing = MotionTokens.Easing.Standard))
        ) {
            if (isTransparent) {
                // No background for transparent images - just show spinner
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Box(
                    modifier = Modifier
                        .widthIn(max = 250.dp)
                        .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Error state with fade animation - minimal for transparent images
        AnimatedVisibility(
            visible = isError,
            enter = fadeIn(tween(MotionTokens.Duration.QUICK, easing = MotionTokens.Easing.Standard)) + scaleIn(initialScale = 0.92f, animationSpec = tween(MotionTokens.Duration.QUICK)),
            exit = fadeOut(tween(MotionTokens.Duration.FAST, easing = MotionTokens.Easing.Standard))
        ) {
            if (isTransparent) {
                // Compact error for transparent images
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = "Failed to load",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.BrokenImage,
                        contentDescription = "Failed to load",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Upload progress overlay for outbound attachments
        AnimatedVisibility(
            visible = interactions.isUploading,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150))
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (interactions.uploadProgress > 0f) {
                            CircularProgressIndicator(
                                progress = { interactions.uploadProgress },
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp,
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp,
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * GIF attachment that loops automatically and opens previewer on tap.
 * GIFs can have transparency, so we handle that case specially.
 *
 * @param attachment The attachment to render
 * @param interactions Callbacks and state for user interactions
 * @param modifier Modifier for the component
 */
@Composable
fun GifAttachment(
    attachment: AttachmentUiModel,
    interactions: AttachmentInteractions,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    val imageUrl = attachment.localPath ?: attachment.webUrl

    // Calculate aspect ratio for proper sizing
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        1f
    }

    // GIFs can have transparency - don't clip corners or add background
    val isTransparent = attachment.mayHaveTransparency

    // Calculate target size in pixels for memory-efficient loading
    val density = LocalDensity.current
    val maxWidthPx = with(density) { 250.dp.toPx().toInt() }
    val targetHeightPx = (maxWidthPx / aspectRatio.coerceIn(0.5f, 2f)).toInt()

    Box(
        modifier = modifier
            .widthIn(max = 250.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = interactions.onClick),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .size(maxWidthPx, targetHeightPx)
                    .precision(Precision.INEXACT)
                    .build(),
                contentDescription = attachment.transferName ?: "GIF",
                modifier = Modifier
                    .widthIn(max = 250.dp)
                    .aspectRatio(aspectRatio.coerceIn(0.5f, 2f)),
                contentScale = ContentScale.Fit,
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                    isError = state is AsyncImagePainter.State.Error
                }
            )
        } else {
            isError = true
        }

        // Loading indicator - minimal for transparent GIFs
        if (isLoading) {
            if (isTransparent) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Box(
                    modifier = Modifier
                        .widthIn(max = 250.dp)
                        .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // GIF badge - only show for non-transparent GIFs to avoid clutter
        if (!isLoading && !isError && !isTransparent) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = "GIF",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // Error state with fade animation - minimal for transparent GIFs
        AnimatedVisibility(
            visible = isError,
            enter = fadeIn(tween(150, easing = FastOutSlowInEasing)) + scaleIn(initialScale = 0.92f, animationSpec = tween(150)),
            exit = fadeOut(tween(100, easing = FastOutSlowInEasing))
        ) {
            if (isTransparent) {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = "Failed to load",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.BrokenImage,
                        contentDescription = "Failed to load",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Upload progress overlay for outbound attachments
        AnimatedVisibility(
            visible = interactions.isUploading,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150))
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (interactions.uploadProgress > 0f) {
                            CircularProgressIndicator(
                                progress = { interactions.uploadProgress },
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp,
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp,
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// BORDERLESS IMAGE COMPONENTS
// Used when rendering media outside message bubbles as standalone elements
// =============================================================================

/**
 * Renders an image attachment without bubble container styling.
 * For transparent images, removes corners and background entirely.
 * For placed stickers, applies a slight rotation for a "slapped on" effect.
 *
 * @param attachment The attachment to render
 * @param interactions Callbacks for user interactions
 * @param maxWidth Maximum width constraint for the media
 * @param modifier Modifier for the component
 * @param isPlacedSticker Whether this is a sticker placed on another message
 * @param messageGuid The message GUID (used for deterministic sticker rotation)
 */
@Composable
fun BorderlessImageAttachment(
    attachment: AttachmentUiModel,
    interactions: AttachmentInteractions,
    maxWidth: Dp = 300.dp,
    modifier: Modifier = Modifier,
    isPlacedSticker: Boolean = false,
    messageGuid: String = ""
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    // For stickers, don't fall back to webUrl (HEIC doesn't work, needs PNG conversion)
    // Force download first to trigger HEIC->PNG conversion
    // For regular images, use full-resolution for crisp display (thumbnails are only 300px)
    val imageUrl = if (attachment.isSticker) {
        attachment.localPath  // null if not downloaded, will show error/placeholder
    } else {
        val path = attachment.localPath
        if (path != null) {
            // Check if file exists (if it's a file path or file URI)
            // This prevents broken thumbnails if the local file was deleted (e.g. pending attachment cleanup)
            val file = try {
                if (path.startsWith("file://")) {
                    File(Uri.parse(path).path ?: "")
                } else if (path.startsWith("/")) {
                    File(path)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }

            if (file != null && !file.exists()) {
                attachment.webUrl // Fallback if file missing
            } else {
                path // Use local path (it exists or is a content URI we can't easily check)
            }
        } else {
            attachment.webUrl
        }
    }

    // Calculate aspect ratio for proper sizing
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        1f
    }

    // For transparent images, don't clip corners
    val isTransparent = attachment.mayHaveTransparency

    // Deterministic rotation based on guid hash for placed stickers (-15 to +15)
    val rotation = if (isPlacedSticker) {
        ((messageGuid.hashCode() % 30) - 15).toFloat()
    } else 0f

    // Slight size variance for placed stickers (0.9x to 1.1x)
    val sizeScale = if (isPlacedSticker) {
        0.9f + ((messageGuid.hashCode() and 0xFF) / 255f) * 0.2f
    } else 1f

    // Calculate target size in pixels for memory-efficient loading
    val density = LocalDensity.current
    val effectiveMaxWidth = maxWidth * sizeScale
    val maxWidthPx = with(density) { effectiveMaxWidth.toPx().toInt() }
    val targetHeightPx = (maxWidthPx / aspectRatio.coerceIn(0.5f, 2f)).toInt()

    Box(
        modifier = modifier
            .widthIn(max = effectiveMaxWidth)
            .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (isPlacedSticker) {
                    Modifier.graphicsLayer { rotationZ = rotation }
                } else Modifier
            )
            .clickable(onClick = interactions.onClick),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .size(maxWidthPx, targetHeightPx)
                    .precision(Precision.INEXACT)
                    .build(),
                contentDescription = attachment.transferName ?: "Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = if (isTransparent) ContentScale.Fit else ContentScale.Crop,
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                    isError = state is AsyncImagePainter.State.Error
                }
            )
        } else {
            isError = true
        }

        // Loading indicator - minimal for transparent images
        if (isLoading) {
            if (isTransparent) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Error state - minimal for transparent images
        if (isError) {
            if (isTransparent) {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = "Failed to load",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.BrokenImage,
                        contentDescription = "Failed to load",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

/**
 * Borderless GIF attachment that loops and opens previewer on tap.
 * Supports transparency - no corners or background for transparent GIFs.
 *
 * @param attachment The attachment to render
 * @param interactions Callbacks for user interactions
 * @param maxWidth Maximum width constraint for the media
 * @param modifier Modifier for the component
 * @param isPlacedSticker Whether this is a sticker placed on another message
 * @param messageGuid The message GUID (used for deterministic sticker rotation)
 */
@Composable
fun BorderlessGifAttachment(
    attachment: AttachmentUiModel,
    interactions: AttachmentInteractions,
    maxWidth: Dp = 300.dp,
    modifier: Modifier = Modifier,
    isPlacedSticker: Boolean = false,
    messageGuid: String = ""
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    // For stickers, don't fall back to webUrl (HEIC doesn't work, needs PNG conversion)
    // Force download first to trigger HEIC->PNG conversion
    // For regular images, use full-resolution for crisp display (thumbnails are only 300px)
    val imageUrl = if (attachment.isSticker) {
        attachment.localPath  // null if not downloaded, will show error/placeholder
    } else {
        val path = attachment.localPath
        if (path != null) {
            // Check if file exists (if it's a file path or file URI)
            // This prevents broken thumbnails if the local file was deleted (e.g. pending attachment cleanup)
            val file = try {
                if (path.startsWith("file://")) {
                    File(Uri.parse(path).path ?: "")
                } else if (path.startsWith("/")) {
                    File(path)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }

            if (file != null && !file.exists()) {
                attachment.webUrl // Fallback if file missing
            } else {
                path // Use local path (it exists or is a content URI we can't easily check)
            }
        } else {
            attachment.webUrl
        }
    }

    // Calculate aspect ratio for proper sizing
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        1f
    }

    // GIFs can have transparency
    val isTransparent = attachment.mayHaveTransparency

    // Deterministic rotation based on guid hash for placed stickers (-15 to +15)
    val rotation = if (isPlacedSticker) {
        ((messageGuid.hashCode() % 30) - 15).toFloat()
    } else 0f

    // Slight size variance for placed stickers (0.9x to 1.1x)
    val sizeScale = if (isPlacedSticker) {
        0.9f + ((messageGuid.hashCode() and 0xFF) / 255f) * 0.2f
    } else 1f

    val effectiveMaxWidth = maxWidth * sizeScale

    // Calculate target size in pixels for memory-efficient loading
    val density = LocalDensity.current
    val maxWidthPx = with(density) { effectiveMaxWidth.toPx().toInt() }
    val targetHeightPx = (maxWidthPx / aspectRatio.coerceIn(0.5f, 2f)).toInt()

    Box(
        modifier = modifier
            .widthIn(max = effectiveMaxWidth)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (isPlacedSticker) {
                    Modifier.graphicsLayer { rotationZ = rotation }
                } else Modifier
            )
            .clickable(onClick = interactions.onClick),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .size(maxWidthPx, targetHeightPx)
                    .precision(Precision.INEXACT)
                    .build(),
                contentDescription = attachment.transferName ?: "GIF",
                modifier = Modifier
                    .widthIn(max = effectiveMaxWidth)
                    .aspectRatio(aspectRatio.coerceIn(0.5f, 2f)),
                contentScale = ContentScale.Fit,
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                    isError = state is AsyncImagePainter.State.Error
                }
            )
        } else {
            isError = true
        }

        // Loading indicator - minimal for transparent GIFs
        if (isLoading) {
            if (isTransparent) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Box(
                    modifier = Modifier
                        .widthIn(max = maxWidth)
                        .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // GIF badge - only show for non-transparent GIFs
        if (!isLoading && !isError && !isTransparent) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = "GIF",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // Error state - minimal for transparent GIFs
        if (isError) {
            if (isTransparent) {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = "Failed to load",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.BrokenImage,
                        contentDescription = "Failed to load",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
