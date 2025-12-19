package com.bothbubbles.ui.components.message

import com.bothbubbles.ui.components.message.AttachmentUiModel
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.util.parsing.DetectedUrl
import com.bothbubbles.util.parsing.YouTubeUrlParser

/**
 * Represents a distinct renderable segment of a message.
 * Messages are segmented to allow media and link previews to render
 * outside the bubble container while text remains inside bubbles.
 */
sealed class MessageSegment {
    /** Text content - rendered inside a bubble */
    data class TextSegment(val text: String) : MessageSegment()

    /** Media (image/video) - rendered outside bubble, borderless */
    data class MediaSegment(val attachment: AttachmentUiModel) : MessageSegment()

    /** Link preview - rendered outside bubble, borderless */
    data class LinkPreviewSegment(val url: String, val detectedUrl: DetectedUrl) : MessageSegment()

    /** YouTube video - rendered with inline player, borderless */
    data class YouTubeVideoSegment(
        val videoId: String,
        val originalUrl: String,
        val thumbnailUrl: String,
        val startTimeSeconds: Int? = null,
        val isShort: Boolean = false
    ) : MessageSegment()

    /** Non-media attachment (audio, docs, vCards) - rendered in compact container */
    data class FileSegment(val attachment: AttachmentUiModel) : MessageSegment()
}

/**
 * Parses a message into segments for rendering.
 * Handles the logic of separating media/links from text content.
 */
object MessageSegmentParser {

    /**
     * Parses a MessageUiModel into an ordered list of segments.
     *
     * Order: media first → text bubble → YouTube/link preview → file attachments
     *
     * This matches typical messaging app behavior where media leads.
     */
    fun parse(message: MessageUiModel, detectedUrl: DetectedUrl?): List<MessageSegment> {
        val segments = mutableListOf<MessageSegment>()

        // Separate media (images/videos) from files (audio, documents, etc.)
        val mediaAttachments = message.attachments.filter { it.isImage || it.isVideo }
        val fileAttachments = message.attachments.filter { !it.isImage && !it.isVideo }

        // 1. Media segments first (images/videos rendered borderless)
        mediaAttachments.forEach { attachment ->
            segments.add(MessageSegment.MediaSegment(attachment))
        }

        // 2. Text segment (if any text remains after stripping URL)
        val displayText = if (detectedUrl != null && !message.text.isNullOrBlank()) {
            message.text.replace(detectedUrl.matchedText, "").trim()
        } else {
            message.text?.trim()
        }

        if (!displayText.isNullOrBlank()) {
            segments.add(MessageSegment.TextSegment(displayText))
        }

        // 3. YouTube video or link preview segment (rendered borderless)
        detectedUrl?.let { url ->
            // Check if this is a YouTube URL
            val youtubeVideo = YouTubeUrlParser.parseUrl(url.url)
            if (youtubeVideo != null) {
                segments.add(
                    MessageSegment.YouTubeVideoSegment(
                        videoId = youtubeVideo.videoId,
                        originalUrl = youtubeVideo.originalUrl,
                        thumbnailUrl = youtubeVideo.thumbnailUrl,
                        startTimeSeconds = youtubeVideo.startTimeSeconds,
                        isShort = youtubeVideo.isShort
                    )
                )
            } else {
                segments.add(MessageSegment.LinkPreviewSegment(url.url, url))
            }
        }

        // 4. File attachments (audio, docs, vCards - keep in containers)
        fileAttachments.forEach { attachment ->
            segments.add(MessageSegment.FileSegment(attachment))
        }

        return segments
    }

    /**
     * Returns true if the message needs segmented rendering.
     * Simple text-only messages can use the optimized single-bubble path.
     */
    fun needsSegmentation(message: MessageUiModel, hasLinkPreview: Boolean): Boolean {
        // Always segment if there are attachments.
        // Even non-media attachments (files) should be rendered as FileSegments
        // rather than being hidden by SimpleBubbleContent (which doesn't render attachments).
        val hasAttachments = message.attachments.isNotEmpty()
        return hasAttachments || hasLinkPreview
    }
}
