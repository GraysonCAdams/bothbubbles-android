package com.bothbubbles.ui.components.attachment

import androidx.compose.runtime.staticCompositionLocalOf
import com.bothbubbles.services.media.ExoPlayerPool

/**
 * Shared interaction callbacks and state for all attachment types.
 * This prevents passing 10+ individual lambdas to every composable.
 *
 * @param onClick Generic click callback (e.g., open media viewer)
 * @param onFullscreenClick Callback when fullscreen/expand is requested (videos)
 * @param onTimestampAreaClick Callback for tapping the lower portion of images (toggles timestamp)
 * @param onLongPress Callback for long pressing images (opens fullscreen)
 * @param onDownloadClick Optional callback for manual download mode
 * @param onRetryClick Optional callback for retrying failed downloads
 * @param isUploading Whether the attachment is currently being uploaded
 * @param uploadProgress Upload progress (0.0 to 1.0) when isUploading is true
 */
data class AttachmentInteractions(
    val onClick: () -> Unit,
    val onFullscreenClick: () -> Unit = {},
    val onTimestampAreaClick: () -> Unit = {},
    val onLongPress: () -> Unit = {},
    val onDownloadClick: (() -> Unit)? = null,
    val onRetryClick: (() -> Unit)? = null,
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f
)

/**
 * CompositionLocal for providing ExoPlayerPool to video composables.
 * When provided, videos will use pooled players with automatic eviction
 * to limit memory usage and prevent multiple videos playing simultaneously.
 */
val LocalExoPlayerPool = staticCompositionLocalOf<ExoPlayerPool?> { null }
