package com.bothbubbles.ui.components.attachment

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.message.AttachmentUiModel

/**
 * Renders an attachment within a message bubble.
 * Supports images, videos, audio, contacts, and generic files.
 *
 * This composable acts as a dispatcher to specialized attachment components:
 * - [ImageAttachment] for images
 * - [GifAttachment] for GIFs
 * - [VideoAttachment] for videos
 * - [AudioAttachment] for audio files
 * - [ContactAttachment] for vCards
 * - [FileAttachment] for generic files
 *
 * @param attachment The attachment to render
 * @param isFromMe Whether this message is from the current user
 * @param onMediaClick Callback when media is clicked for viewing
 * @param onDownloadClick Optional callback for manual download mode. When provided and attachment
 *                        needs download, shows a placeholder with download button instead of
 *                        streaming from webUrl.
 * @param isDownloading Whether this attachment is currently being downloaded
 * @param downloadProgress Download progress (0.0 to 1.0) when isDownloading is true
 * @param uploadProgress Upload progress (0.0 to 1.0) for outbound attachments being uploaded
 * @param onRetryClick Optional callback for retrying failed downloads
 * @param isRetrying Whether a retry is currently in progress
 */
@Composable
fun AttachmentContent(
    attachment: AttachmentUiModel,
    isFromMe: Boolean,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDownloadClick: ((String) -> Unit)? = null,
    isDownloading: Boolean = false,
    downloadProgress: Float = 0f,
    uploadProgress: Float = 0f,
    onRetryClick: ((String) -> Unit)? = null,
    isRetrying: Boolean = false
) {
    // Show error overlay for failed attachments with error details
    val showError = attachment.hasError && !isDownloading && !isRetrying

    // Show placeholder for inbound attachments that need download
    // This provides blurhash preview while downloading, regardless of auto/manual mode
    // For stickers, ALWAYS show placeholder when not downloaded (they need HEIC→PNG conversion)
    val showPlaceholder = !showError && (attachment.needsDownload || attachment.isDownloading ||
        (attachment.isSticker && attachment.localPath == null))

    // Determine effective downloading state
    val effectiveIsDownloading = isDownloading || attachment.isDownloading ||
        (attachment.isSticker && attachment.localPath == null && onDownloadClick == null)

    // Determine if we're uploading (outbound, not yet uploaded)
    val isUploading = attachment.isUploading
    val effectiveUploadProgress = if (isUploading) uploadProgress.coerceIn(0f, 1f) else 0f

    Column(modifier = modifier) {
        when {
            showError -> AttachmentErrorOverlay(
                attachment = attachment,
                onRetryClick = { onRetryClick?.invoke(attachment.guid) },
                isRetrying = isRetrying
            )
            showPlaceholder -> AttachmentPlaceholder(
                attachment = attachment,
                isFromMe = isFromMe,
                onDownloadClick = { onDownloadClick?.invoke(attachment.guid) },
                isDownloading = effectiveIsDownloading,
                downloadProgress = downloadProgress
            )
            attachment.isGif -> GifAttachment(
                attachment = attachment,
                interactions = AttachmentInteractions(
                    onClick = { onMediaClick(attachment.guid) },
                    isUploading = isUploading,
                    uploadProgress = effectiveUploadProgress
                )
            )
            attachment.isImage -> ImageAttachment(
                attachment = attachment,
                interactions = AttachmentInteractions(
                    onClick = { onMediaClick(attachment.guid) },
                    isUploading = isUploading,
                    uploadProgress = effectiveUploadProgress
                )
            )
            attachment.isVideo -> VideoAttachment(
                attachment = attachment,
                interactions = AttachmentInteractions(
                    onClick = { onMediaClick(attachment.guid) },
                    onFullscreenClick = { onMediaClick(attachment.guid) },
                    isUploading = isUploading,
                    uploadProgress = effectiveUploadProgress
                )
            )
            attachment.isAudio -> AudioAttachment(
                attachment = attachment,
                interactions = AttachmentInteractions(
                    onClick = { onMediaClick(attachment.guid) }
                )
            )
            attachment.isVCard -> ContactAttachment(
                attachment = attachment,
                interactions = AttachmentInteractions(
                    onClick = { onMediaClick(attachment.guid) }
                ),
                isFromMe = isFromMe
            )
            else -> FileAttachment(
                attachment = attachment,
                interactions = AttachmentInteractions(
                    onClick = { onMediaClick(attachment.guid) }
                )
            )
        }

        // Caption display (if present)
        attachment.caption?.let { captionText ->
            Text(
                text = captionText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isFromMe) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

// =============================================================================
// BORDERLESS MEDIA COMPONENTS
// Used when rendering media outside message bubbles as standalone elements
// =============================================================================

/**
 * Renders media content (image or video) without bubble container styling.
 * Used for standalone media segments in segmented message rendering.
 *
 * This composable dispatches to specialized borderless attachment components:
 * - [BorderlessImageAttachment] for images
 * - [BorderlessGifAttachment] for GIFs
 * - [BorderlessInlineVideoAttachment] for videos
 *
 * @param attachment The attachment to render
 * @param isFromMe Whether this message is from the current user
 * @param onMediaClick Callback when media is clicked for viewing
 * @param maxWidth Maximum width constraint for the media
 * @param onDownloadClick Optional callback for manual download mode
 * @param isDownloading Whether this attachment is currently being downloaded
 * @param downloadProgress Download progress (0.0 to 1.0)
 * @param isPlacedSticker Whether this is a sticker placed on another message
 * @param messageGuid The message GUID (used for deterministic sticker rotation)
 * @param onRetryClick Optional callback for retrying failed downloads
 * @param isRetrying Whether a retry is currently in progress
 */
@Composable
fun BorderlessMediaContent(
    attachment: AttachmentUiModel,
    isFromMe: Boolean,
    onMediaClick: (String) -> Unit,
    maxWidth: androidx.compose.ui.unit.Dp = 300.dp,
    modifier: Modifier = Modifier,
    onDownloadClick: ((String) -> Unit)? = null,
    isDownloading: Boolean = false,
    downloadProgress: Float = 0f,
    isPlacedSticker: Boolean = false,
    messageGuid: String = "",
    onRetryClick: ((String) -> Unit)? = null,
    isRetrying: Boolean = false
) {
    // Show error overlay for failed attachments with error details
    val showError = attachment.hasError && !isDownloading && !isRetrying

    // Show placeholder if manual download mode and attachment needs download
    // For stickers, ALWAYS show placeholder when not downloaded (they need HEIC→PNG conversion)
    val showPlaceholder = !showError && attachment.needsDownload && (onDownloadClick != null || attachment.isSticker)

    // For stickers in auto-download mode, show as "downloading" even if not actively downloading yet
    val effectiveIsDownloading = isDownloading || (attachment.isSticker && attachment.needsDownload && onDownloadClick == null)

    // Use smaller max width for placed stickers
    val effectiveMaxWidth = if (isPlacedSticker) 140.dp else maxWidth

    when {
        showError -> AttachmentErrorOverlay(
            attachment = attachment,
            onRetryClick = { onRetryClick?.invoke(attachment.guid) },
            isRetrying = isRetrying,
            modifier = modifier
        )
        showPlaceholder -> BorderlessAttachmentPlaceholder(
            attachment = attachment,
            onDownloadClick = { onDownloadClick?.invoke(attachment.guid) },
            isDownloading = effectiveIsDownloading,
            downloadProgress = downloadProgress,
            maxWidth = effectiveMaxWidth,
            modifier = modifier
        )
        attachment.isGif -> BorderlessGifAttachment(
            attachment = attachment,
            interactions = AttachmentInteractions(
                onClick = { onMediaClick(attachment.guid) }
            ),
            maxWidth = effectiveMaxWidth,
            modifier = modifier,
            isPlacedSticker = isPlacedSticker,
            messageGuid = messageGuid
        )
        attachment.isImage -> BorderlessImageAttachment(
            attachment = attachment,
            interactions = AttachmentInteractions(
                onClick = { onMediaClick(attachment.guid) }
            ),
            maxWidth = effectiveMaxWidth,
            modifier = modifier,
            isPlacedSticker = isPlacedSticker,
            messageGuid = messageGuid
        )
        attachment.isVideo -> BorderlessInlineVideoAttachment(
            attachment = attachment,
            onFullscreenClick = { onMediaClick(attachment.guid) },
            maxWidth = effectiveMaxWidth,
            modifier = modifier
        )
    }
}
