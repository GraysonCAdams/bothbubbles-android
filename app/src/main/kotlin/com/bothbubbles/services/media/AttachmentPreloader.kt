package com.bothbubbles.services.media

import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Precision
import com.bothbubbles.ui.components.AttachmentUiModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for preloading attachment images to improve scroll performance.
 *
 * When the user scrolls through a chat, this service preloads images for
 * messages that are about to come into view, reducing load times.
 */
@Singleton
class AttachmentPreloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader
) {
    companion object {
        private const val PRELOAD_SIZE = 500 // Reduced size for faster preloading
        private const val MAX_TRACKED_URLS = 100
    }

    // Track URLs we've already preloaded to avoid duplicates
    private val preloadedUrls = mutableSetOf<String>()

    /**
     * Preload images for attachments near the visible range.
     *
     * @param attachments All attachments in the current chat
     * @param visibleRange Range of currently visible message indices
     * @param preloadCount Number of messages to preload ahead/behind visible range
     */
    fun preloadNearby(
        attachments: List<AttachmentUiModel>,
        visibleRange: IntRange,
        preloadCount: Int = 5
    ) {
        // TEMPORARILY DISABLED: Skip attachment preloading to focus on text-only performance
        return

        // Calculate preload range (visible +/- preloadCount)
        val preloadStart = (visibleRange.first - preloadCount).coerceAtLeast(0)
        val preloadEnd = (visibleRange.last + preloadCount).coerceAtMost(attachments.size - 1)

        if (preloadStart > preloadEnd) return

        // Filter to images that need preloading - minimize allocations
        val toPreload = mutableListOf<String>()
        for (i in preloadStart..preloadEnd) {
            val att = attachments[i]
            if ((att.isImage || att.isGif) && !att.needsDownload) {
                val url = att.localPath ?: att.webUrl
                if (url != null && url !in preloadedUrls) {
                    toPreload.add(url)
                }
            }
        }

        // Preload each image
        toPreload.forEach { url ->
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(PRELOAD_SIZE)
                .precision(Precision.INEXACT)
                .build()

            imageLoader.enqueue(request)
            preloadedUrls.add(url)
        }

        // Clean up tracking set if it gets too large
        if (preloadedUrls.size > MAX_TRACKED_URLS) {
            clearTracking()
        }
    }

    /**
     * Preload a specific attachment image.
     *
     * @param attachment The attachment to preload
     */
    fun preload(attachment: AttachmentUiModel) {
        // TEMPORARILY DISABLED: Skip attachment preloading to focus on text-only performance
        return

        if (!attachment.isImage && !attachment.isGif) return
        if (attachment.needsDownload) return

        val url = attachment.localPath ?: attachment.webUrl ?: return
        if (url in preloadedUrls) return

        val request = ImageRequest.Builder(context)
            .data(url)
            .size(PRELOAD_SIZE)
            .precision(Precision.INEXACT)
            .build()

        imageLoader.enqueue(request)
        preloadedUrls.add(url)
    }

    /**
     * Clear the tracking set of preloaded URLs.
     * Call this when switching chats or when memory is low.
     */
    fun clearTracking() {
        preloadedUrls.clear()
    }
}
