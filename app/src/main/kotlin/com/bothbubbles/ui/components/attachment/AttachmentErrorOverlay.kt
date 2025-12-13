package com.bothbubbles.ui.components.attachment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SignalWifiOff
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.message.AttachmentUiModel
import com.bothbubbles.util.rememberBlurhashBitmap

/**
 * Error overlay for failed attachment downloads/uploads.
 * Shows the error message with a retry button, optionally over a blurhash placeholder.
 *
 * @param attachment The attachment that failed
 * @param onRetryClick Callback when user taps to retry
 * @param isRetrying Whether a retry is currently in progress
 * @param modifier Modifier for the overlay
 */
@Composable
fun AttachmentErrorOverlay(
    attachment: AttachmentUiModel,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
    isRetrying: Boolean = false
) {
    val isMedia = attachment.isImage || attachment.isVideo

    // Calculate aspect ratio for media
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else if (attachment.isVideo) {
        16f / 9f
    } else {
        1f
    }

    // Decode blurhash for placeholder preview (for media)
    val blurhashBitmap = rememberBlurhashBitmap(
        blurhash = attachment.blurhash,
        aspectRatio = aspectRatio
    )

    // Get appropriate icon based on error type
    val (errorIcon, iconColor) = getErrorIconAndColor(attachment.errorType)

    if (isMedia) {
        // Media error overlay with blurhash background
        Box(
            modifier = modifier
                .widthIn(max = 250.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(enabled = !isRetrying && attachment.isRetryable, onClick = onRetryClick),
            contentAlignment = Alignment.Center
        ) {
            // Blurhash preview background (or fallback to error color tint)
            Box(
                modifier = Modifier
                    .widthIn(max = 250.dp)
                    .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
            ) {
                if (blurhashBitmap != null) {
                    Image(
                        bitmap = blurhashBitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    // Dark overlay to make error content readable
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                    )
                }
            }

            // Error content overlay
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                // Retry button or loading indicator
                AnimatedVisibility(
                    visible = !isRetrying,
                    enter = fadeIn(tween(100)) + scaleIn(initialScale = 0.8f, animationSpec = tween(100)),
                    exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.8f, animationSpec = tween(100))
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (attachment.isRetryable) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            Color.Black.copy(alpha = 0.6f)
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (attachment.isRetryable) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Tap to retry",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(28.dp)
                                )
                            } else {
                                Icon(
                                    errorIcon,
                                    contentDescription = "Error",
                                    tint = iconColor,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isRetrying,
                    enter = fadeIn(tween(100)) + scaleIn(initialScale = 0.8f, animationSpec = tween(100)),
                    exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.8f, animationSpec = tween(100))
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp,
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                        }
                    }
                }

                // Error message
                if (!isRetrying) {
                    Text(
                        text = attachment.errorMessage ?: "Download failed",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }
        }
    } else {
        // Non-media file error (audio, documents, etc.)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
            modifier = modifier
                .widthIn(min = 180.dp, max = 250.dp)
                .clickable(enabled = !isRetrying && attachment.isRetryable, onClick = onRetryClick)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Error icon / retry button
                Box(contentAlignment = Alignment.Center) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isRetrying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onError
                                )
                            } else if (attachment.isRetryable) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Icon(
                                    errorIcon,
                                    contentDescription = "Error",
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                // Error info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = attachment.transferName ?: "File",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 1
                    )
                    Text(
                        text = if (isRetrying) "Retrying..." else (attachment.errorMessage ?: "Download failed"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Returns an appropriate icon and color based on error type.
 */
@Composable
private fun getErrorIconAndColor(errorType: String?): Pair<ImageVector, Color> {
    return when (errorType) {
        "NETWORK_TIMEOUT", "NO_CONNECTION" -> Pair(
            Icons.Outlined.SignalWifiOff,
            MaterialTheme.colorScheme.error
        )
        "SERVER_ERROR" -> Pair(
            Icons.Outlined.CloudOff,
            MaterialTheme.colorScheme.error
        )
        "STORAGE_FULL" -> Pair(
            Icons.Outlined.Storage,
            MaterialTheme.colorScheme.error
        )
        "FILE_TOO_LARGE", "FORMAT_UNSUPPORTED" -> Pair(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.tertiary
        )
        else -> Pair(
            Icons.Outlined.ErrorOutline,
            MaterialTheme.colorScheme.error
        )
    }
}
