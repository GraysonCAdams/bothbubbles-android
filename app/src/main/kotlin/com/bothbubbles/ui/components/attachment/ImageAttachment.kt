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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import coil.size.Precision
import com.bothbubbles.ui.components.message.AttachmentUiModel
import com.bothbubbles.ui.theme.MediaSizing
import timber.log.Timber

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
    var containerHeight by remember { mutableIntStateOf(0) }
    val hapticFeedback = LocalHapticFeedback.current

    // Use displayUrl from model - handles localPath vs webUrl logic centrally
    // IMPORTANT: For inbound attachments, webUrl won't work (Coil lacks auth headers)
    val imageUrl = attachment.displayUrl

    // Calculate aspect ratio for proper sizing
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        1f
    }

    // For transparent images, use Fit to preserve content
    val isTransparent = attachment.mayHaveTransparency

    // Calculate natural height at max width, determine if cropping is needed
    val density = LocalDensity.current
    val maxWidthPx = with(density) { MediaSizing.MAX_WIDTH.toPx().toInt() }

    // Natural height if we fill max width
    val naturalHeightAtMaxWidth = MediaSizing.MAX_WIDTH / aspectRatio
    // Only crop if natural height exceeds max height
    val needsCropping = naturalHeightAtMaxWidth > MediaSizing.MAX_HEIGHT
    // Container height: natural height capped at max
    val containerHeightDp = if (needsCropping) MediaSizing.MAX_HEIGHT else naturalHeightAtMaxWidth
    val containerHeightPx = with(density) { containerHeightDp.toPx().toInt() }

    // DEBUG LOGGING
    SideEffect {
        Timber.tag("AttachmentDebug").d("🖼️ ImageAttachment RENDER: guid=${attachment.guid}")
        Timber.tag("AttachmentDebug").d("   RESOLVED imageUrl=$imageUrl")
        Timber.tag("AttachmentDebug").d("   localPath=${attachment.localPath}, webUrl=${attachment.webUrl}")
        Timber.tag("AttachmentDebug").d("   isLoading=$isLoading, isError=$isError")
        Timber.tag("AttachmentDebug").d("   aspectRatio=$aspectRatio, size=${maxWidthPx}x$containerHeightPx, needsCropping=$needsCropping")
    }

    Box(
        modifier = modifier
            .width(MediaSizing.MAX_WIDTH)
            .height(containerHeightDp)
            .onSizeChanged { containerHeight = it.height }
            .clip(RoundedCornerShape(MediaSizing.CORNER_RADIUS))
            .pointerInput(interactions) {
                detectTapGestures(
                    onTap = { offset ->
                        // Lower 20% of image = timestamp zone
                        val timestampZoneStart = containerHeight * 0.8f
                        if (offset.y >= timestampZoneStart) {
                            interactions.onTimestampAreaClick()
                        } else {
                            interactions.onClick()
                        }
                    },
                    onLongPress = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        interactions.onLongPress()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .size(maxWidthPx, containerHeightPx)
                    .precision(Precision.INEXACT)
                    .build(),
                contentDescription = attachment.transferName ?: "Image",
                modifier = Modifier.fillMaxSize(),
                // Crop only when height is capped; for transparent images always use Fit
                contentScale = when {
                    isTransparent -> ContentScale.Fit
                    needsCropping -> ContentScale.Crop
                    else -> ContentScale.FillWidth
                },
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                    isError = state is AsyncImagePainter.State.Error
                    Timber.tag("AttachmentDebug").d("🖼️ ImageAttachment STATE: guid=${attachment.guid}, state=${state::class.simpleName}")
                    if (state is AsyncImagePainter.State.Error) {
                        Timber.tag("AttachmentDebug").e(state.result.throwable, "🖼️ ImageAttachment ERROR: guid=${attachment.guid}")
                    }
                }
            )
        } else if (attachment.isAwaitingDownload) {
            // No URL available yet - waiting for auto-download to complete
            Timber.tag("AttachmentDebug").d("🖼️ ImageAttachment AWAITING DOWNLOAD: guid=${attachment.guid}")
        } else {
            isError = true
            Timber.tag("AttachmentDebug").e("🖼️ ImageAttachment NO URL: guid=${attachment.guid}")
        }

        // Loading indicator with fade animation - show when Coil loading OR awaiting download
        AnimatedVisibility(
            visible = isLoading || attachment.isAwaitingDownload,
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
                        .fillMaxSize()
                        .clip(RoundedCornerShape(MediaSizing.CORNER_RADIUS))
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
                        .clip(RoundedCornerShape(MediaSizing.CORNER_RADIUS))
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
    var containerHeight by remember { mutableIntStateOf(0) }
    val hapticFeedback = LocalHapticFeedback.current

    // Use displayUrl from model - handles localPath vs webUrl logic centrally
    val imageUrl = attachment.displayUrl

    // Calculate aspect ratio for proper sizing
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        1f
    }

    // GIFs CAN have transparency, but most don't - only use Fit for stickers
    // Regular GIFs should use Crop to avoid letterboxing (matches iMessage behavior)
    val isTransparent = attachment.isSticker

    // Calculate natural height at max width, determine if cropping is needed
    val density = LocalDensity.current
    val maxWidthPx = with(density) { MediaSizing.MAX_WIDTH.toPx().toInt() }
    val maxHeightPx = with(density) { MediaSizing.MAX_HEIGHT.toPx().toInt() }

    // Natural height if we fill max width
    val naturalHeightAtMaxWidth = MediaSizing.MAX_WIDTH / aspectRatio
    // Only crop if natural height exceeds max height
    val needsCropping = naturalHeightAtMaxWidth > MediaSizing.MAX_HEIGHT
    // Container height: natural height capped at max
    val containerHeightDp = if (needsCropping) MediaSizing.MAX_HEIGHT else naturalHeightAtMaxWidth
    val containerHeightPx = with(density) { containerHeightDp.toPx().toInt() }

    Box(
        modifier = modifier
            .width(MediaSizing.MAX_WIDTH)
            .height(containerHeightDp)
            .onSizeChanged { containerHeight = it.height }
            .clip(RoundedCornerShape(MediaSizing.CORNER_RADIUS))
            .pointerInput(interactions) {
                detectTapGestures(
                    onTap = { offset ->
                        // Lower 20% of image = timestamp zone
                        val timestampZoneStart = containerHeight * 0.8f
                        if (offset.y >= timestampZoneStart) {
                            interactions.onTimestampAreaClick()
                        } else {
                            interactions.onClick()
                        }
                    },
                    onLongPress = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        interactions.onLongPress()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .size(maxWidthPx, containerHeightPx)
                    .precision(Precision.INEXACT)
                    .build(),
                contentDescription = attachment.transferName ?: "GIF",
                modifier = Modifier.fillMaxSize(),
                // Crop only when height is capped; for stickers always use Fit
                contentScale = when {
                    isTransparent -> ContentScale.Fit
                    needsCropping -> ContentScale.Crop
                    else -> ContentScale.FillWidth
                },
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                    isError = state is AsyncImagePainter.State.Error
                }
            )
        } else if (attachment.isAwaitingDownload) {
            // No URL available yet - waiting for auto-download
        } else {
            isError = true
        }

        // Loading indicator - show when Coil loading OR awaiting download
        if (isLoading || attachment.isAwaitingDownload) {
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
                        .clip(RoundedCornerShape(MediaSizing.CORNER_RADIUS))
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
                        .clip(RoundedCornerShape(MediaSizing.CORNER_RADIUS))
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
    maxWidth: Dp = MediaSizing.BORDERLESS_MAX_WIDTH,
    modifier: Modifier = Modifier,
    isPlacedSticker: Boolean = false,
    messageGuid: String = ""
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }
    var containerHeight by remember { mutableIntStateOf(0) }
    val hapticFeedback = LocalHapticFeedback.current

    // Memoize file resolution to avoid File allocations and exists() checks on every recomposition
    // Key on localPath and webUrl to recalculate only when attachment data changes
    // IMPORTANT: Don't fall back to webUrl - Coil doesn't have auth headers and will get 401
    // Instead, return null and show loading state while auto-download completes
    val imageUrl = remember(attachment.localPath, attachment.webUrl, attachment.isSticker, attachment.isOutgoing) {
        // For stickers, don't fall back to webUrl (HEIC doesn't work, needs PNG conversion)
        // Force download first to trigger HEIC->PNG conversion
        // For regular images, use full-resolution for crisp display (thumbnails are only 300px)
        if (attachment.isSticker) {
            Timber.tag("AttachmentDebug").d("🖼️ BorderlessImage: isSticker=true, using localPath only")
            attachment.localPath  // null if not downloaded, will show loading/placeholder
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
                    Timber.tag("AttachmentDebug").e(e, "🖼️ BorderlessImage: Exception checking file: $path")
                    null
                }

                if (file != null && !file.exists()) {
                    // File was deleted - for outgoing, try webUrl; for inbound, return null (await re-download)
                    if (attachment.isOutgoing) {
                        Timber.tag("AttachmentDebug").w("🖼️ BorderlessImage: Outgoing local file MISSING, falling back to webUrl: $path")
                        attachment.webUrl
                    } else {
                        Timber.tag("AttachmentDebug").w("🖼️ BorderlessImage: Inbound local file MISSING, awaiting download: $path")
                        null  // Will trigger auto-download, show loading
                    }
                } else {
                    Timber.tag("AttachmentDebug").d("🖼️ BorderlessImage: Using localPath: $path (exists=${file?.exists()})")
                    path // Use local path (it exists or is a content URI we can't easily check)
                }
            } else {
                // No localPath - use centralized displayUrl logic (handles isOutgoing check)
                Timber.tag("AttachmentDebug").d("🖼️ BorderlessImage: No localPath, using displayUrl: ${attachment.displayUrl}")
                attachment.displayUrl
            }
        }
    }

    // Determine if we should show downloading state
    // Note: Can't use attachment.isAwaitingDownload directly because our imageUrl
    // may differ due to file existence checks that displayUrl doesn't perform
    val isAwaitingDownload = imageUrl == null && !attachment.isOutgoing

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

    // Calculate natural height at max width, determine if cropping is needed
    val density = LocalDensity.current
    val effectiveMaxWidth = maxWidth * sizeScale
    val maxWidthPx = with(density) { effectiveMaxWidth.toPx().toInt() }

    // Natural height if we fill effective max width
    val naturalHeightAtMaxWidth = effectiveMaxWidth / aspectRatio
    // Only crop if natural height exceeds max height
    val needsCropping = naturalHeightAtMaxWidth > MediaSizing.MAX_HEIGHT
    // Container height: natural height capped at max
    val containerHeightDp = if (needsCropping) MediaSizing.MAX_HEIGHT else naturalHeightAtMaxWidth
    val containerHeightPx = with(density) { containerHeightDp.toPx().toInt() }

    // DEBUG LOGGING
    SideEffect {
        Timber.tag("AttachmentDebug").d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Timber.tag("AttachmentDebug").d("🖼️ BorderlessImageAttachment RENDER: guid=${attachment.guid}")
        Timber.tag("AttachmentDebug").d("   FINAL imageUrl=$imageUrl")
        Timber.tag("AttachmentDebug").d("   localPath=${attachment.localPath}")
        Timber.tag("AttachmentDebug").d("   webUrl=${attachment.webUrl}")
        Timber.tag("AttachmentDebug").d("   isSticker=${attachment.isSticker}, isPlacedSticker=$isPlacedSticker")
        Timber.tag("AttachmentDebug").d("   isLoading=$isLoading, isError=$isError")
        Timber.tag("AttachmentDebug").d("   aspectRatio=$aspectRatio, maxWidth=$effectiveMaxWidth, needsCropping=$needsCropping")
        Timber.tag("AttachmentDebug").d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    Box(
        modifier = modifier
            .width(effectiveMaxWidth)
            .height(containerHeightDp)
            .onSizeChanged { containerHeight = it.height }
            .clip(RoundedCornerShape(MediaSizing.CORNER_RADIUS))
            .then(
                if (isPlacedSticker) {
                    Modifier.graphicsLayer { rotationZ = rotation }
                } else Modifier
            )
            .pointerInput(interactions) {
                detectTapGestures(
                    onTap = { offset ->
                        // Lower 20% of image = timestamp zone
                        val timestampZoneStart = containerHeight * 0.8f
                        if (offset.y >= timestampZoneStart) {
                            interactions.onTimestampAreaClick()
                        } else {
                            interactions.onClick()
                        }
                    },
                    onLongPress = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        interactions.onLongPress()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .size(maxWidthPx, containerHeightPx)
                    .precision(Precision.INEXACT)
                    .build(),
                contentDescription = attachment.transferName ?: "Image",
                modifier = Modifier.fillMaxSize(),
                // Crop only when height is capped; for transparent images always use Fit
                contentScale = when {
                    isTransparent -> ContentScale.Fit
                    needsCropping -> ContentScale.Crop
                    else -> ContentScale.FillWidth
                },
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                    isError = state is AsyncImagePainter.State.Error
                    Timber.tag("AttachmentDebug").d("🖼️ BorderlessImage STATE: guid=${attachment.guid}, state=${state::class.simpleName}")
                    if (state is AsyncImagePainter.State.Error) {
                        Timber.tag("AttachmentDebug").e(state.result.throwable, "🖼️ BorderlessImage ERROR: guid=${attachment.guid}, url=$imageUrl")
                    } else if (state is AsyncImagePainter.State.Success) {
                        Timber.tag("AttachmentDebug").d("🖼️ BorderlessImage SUCCESS: guid=${attachment.guid}")
                    }
                }
            )
        } else if (isAwaitingDownload) {
            // No URL available yet - waiting for auto-download to complete
            // Don't set isError, show loading state instead
            Timber.tag("AttachmentDebug").d("🖼️ BorderlessImage AWAITING DOWNLOAD: guid=${attachment.guid}")
        } else {
            // Truly no URL (shouldn't happen for valid attachments)
            isError = true
            Timber.tag("AttachmentDebug").e("🖼️ BorderlessImage NO URL: guid=${attachment.guid}")
        }

        // Loading indicator - show when Coil is loading OR when awaiting download
        if (isLoading || isAwaitingDownload) {
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
                        .clip(RoundedCornerShape(MediaSizing.CORNER_RADIUS))
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
    maxWidth: Dp = MediaSizing.BORDERLESS_MAX_WIDTH,
    modifier: Modifier = Modifier,
    isPlacedSticker: Boolean = false,
    messageGuid: String = ""
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }
    var containerHeight by remember { mutableIntStateOf(0) }
    val hapticFeedback = LocalHapticFeedback.current

    // Memoize file resolution to avoid File allocations and exists() checks on every recomposition
    // IMPORTANT: Don't fall back to webUrl for inbound - Coil doesn't have auth headers
    val imageUrl = remember(attachment.localPath, attachment.webUrl, attachment.isSticker, attachment.isOutgoing) {
        // For stickers, don't fall back to webUrl (HEIC doesn't work, needs PNG conversion)
        // Force download first to trigger HEIC->PNG conversion
        // For regular images, use full-resolution for crisp display (thumbnails are only 300px)
        if (attachment.isSticker) {
            attachment.localPath  // null if not downloaded, will show loading/placeholder
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
                    // File was deleted - for outgoing, try webUrl; for inbound, return null
                    if (attachment.isOutgoing) attachment.webUrl else null
                } else {
                    path // Use local path (it exists or is a content URI we can't easily check)
                }
            } else {
                // No localPath - use centralized displayUrl logic (handles isOutgoing check)
                attachment.displayUrl
            }
        }
    }

    // Determine if we should show downloading state
    // Note: Can't use attachment.isAwaitingDownload directly because our imageUrl
    // may differ due to file existence checks that displayUrl doesn't perform
    val isAwaitingDownload = imageUrl == null && !attachment.isOutgoing

    // Calculate aspect ratio for proper sizing
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        1f
    }

    // GIFs CAN have transparency, but most don't - only use Fit for stickers
    // Regular GIFs should use Crop to avoid letterboxing (matches iMessage behavior)
    val isTransparent = attachment.isSticker

    // Deterministic rotation based on guid hash for placed stickers (-15 to +15)
    val rotation = if (isPlacedSticker) {
        ((messageGuid.hashCode() % 30) - 15).toFloat()
    } else 0f

    // Slight size variance for placed stickers (0.9x to 1.1x)
    val sizeScale = if (isPlacedSticker) {
        0.9f + ((messageGuid.hashCode() and 0xFF) / 255f) * 0.2f
    } else 1f

    val effectiveMaxWidth = maxWidth * sizeScale

    // Calculate natural height at max width, determine if cropping is needed
    val density = LocalDensity.current
    val maxWidthPx = with(density) { effectiveMaxWidth.toPx().toInt() }

    // Natural height if we fill effective max width
    val naturalHeightAtMaxWidth = effectiveMaxWidth / aspectRatio
    // Only crop if natural height exceeds max height
    val needsCropping = naturalHeightAtMaxWidth > MediaSizing.MAX_HEIGHT
    // Container height: natural height capped at max
    val containerHeightDp = if (needsCropping) MediaSizing.MAX_HEIGHT else naturalHeightAtMaxWidth
    val containerHeightPx = with(density) { containerHeightDp.toPx().toInt() }

    Box(
        modifier = modifier
            .width(effectiveMaxWidth)
            .height(containerHeightDp)
            .onSizeChanged { containerHeight = it.height }
            .clip(RoundedCornerShape(MediaSizing.CORNER_RADIUS))
            .then(
                if (isPlacedSticker) {
                    Modifier.graphicsLayer { rotationZ = rotation }
                } else Modifier
            )
            .pointerInput(interactions) {
                detectTapGestures(
                    onTap = { offset ->
                        // Lower 20% of image = timestamp zone
                        val timestampZoneStart = containerHeight * 0.8f
                        if (offset.y >= timestampZoneStart) {
                            interactions.onTimestampAreaClick()
                        } else {
                            interactions.onClick()
                        }
                    },
                    onLongPress = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        interactions.onLongPress()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .size(maxWidthPx, containerHeightPx)
                    .precision(Precision.INEXACT)
                    .build(),
                contentDescription = attachment.transferName ?: "GIF",
                modifier = Modifier.fillMaxSize(),
                // Crop only when height is capped; for stickers always use Fit
                contentScale = when {
                    isTransparent -> ContentScale.Fit
                    needsCropping -> ContentScale.Crop
                    else -> ContentScale.FillWidth
                },
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                    isError = state is AsyncImagePainter.State.Error
                }
            )
        } else if (isAwaitingDownload) {
            // No URL available yet - waiting for auto-download to complete
            // Don't set isError, show loading state instead
        } else {
            isError = true
        }

        // Loading indicator - show when Coil is loading OR when awaiting download
        if (isLoading || isAwaitingDownload) {
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
                        .clip(RoundedCornerShape(MediaSizing.CORNER_RADIUS))
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
                        .clip(RoundedCornerShape(MediaSizing.CORNER_RADIUS))
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
