package com.bothbubbles.services.messaging

import com.bothbubbles.data.local.db.entity.LinkPreviewEntity
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.ui.chat.composer.AttachmentItem
import com.bothbubbles.ui.chat.composer.ComposerDocument
import com.bothbubbles.ui.chat.composer.ComposerLinkPreviewState
import com.bothbubbles.ui.chat.composer.ComposerSegment
import com.bothbubbles.util.parsing.DetectedUrl
import com.bothbubbles.util.parsing.UrlParsingUtils

/**
 * Represents a single sendable unit extracted from a composer document.
 * Each unit becomes a separate message when sent.
 */
sealed interface SendableUnit {
    /** Order index for maintaining sequence */
    val orderIndex: Int

    /**
     * Plain text message.
     */
    data class TextMessage(
        override val orderIndex: Int,
        val text: String
    ) : SendableUnit

    /**
     * Media attachment message (photo, video, audio, document).
     */
    data class MediaMessage(
        override val orderIndex: Int,
        val attachment: PendingAttachmentInput
    ) : SendableUnit

    /**
     * Link embed message with optional preview metadata.
     */
    data class LinkEmbedMessage(
        override val orderIndex: Int,
        val url: String,
        val domain: String,
        val preview: LinkPreviewEntity?
    ) : SendableUnit

    /**
     * Location message.
     */
    data class LocationMessage(
        override val orderIndex: Int,
        val attachment: PendingAttachmentInput,
        val latitude: Double,
        val longitude: Double,
        val address: String?,
        val name: String?
    ) : SendableUnit
}

/**
 * Splits a composer document into ordered sendable units for message sending.
 *
 * The splitter handles:
 * - Converting document segments to sendable units
 * - Detecting typed URLs in text segments and converting to link embeds
 * - Filtering empty text segments
 * - Maintaining order for splitBatchId grouping
 *
 * Example:
 * Input: "Hi! [image] Check this: [link embed] Cool right?"
 * Output: [
 *   TextMessage("Hi!"),
 *   MediaMessage(image),
 *   TextMessage("Check this:"),
 *   LinkEmbedMessage(url),
 *   TextMessage("Cool right?")
 * ]
 */
object DocumentSplitter {

    /**
     * Split a document into sendable units.
     *
     * @param document The composer document to split
     * @param detectTypedUrls Whether to detect URLs in text segments and convert to link embeds.
     *                        Set to true for typed URLs (not explicitly pasted as embeds).
     * @return List of sendable units in order
     */
    fun split(
        document: ComposerDocument,
        detectTypedUrls: Boolean = true
    ): List<SendableUnit> {
        val result = mutableListOf<SendableUnit>()
        var orderIndex = 0

        for (segment in document.segments) {
            when (segment) {
                is ComposerSegment.Text -> {
                    if (detectTypedUrls) {
                        // Split text around detected URLs
                        val units = splitTextWithUrls(segment.content, orderIndex)
                        units.forEach { unit ->
                            result.add(unit)
                            orderIndex++
                        }
                    } else {
                        // Keep text as-is
                        val trimmed = segment.content.trim()
                        if (trimmed.isNotBlank()) {
                            result.add(SendableUnit.TextMessage(orderIndex++, trimmed))
                        }
                    }
                }

                is ComposerSegment.Media -> {
                    result.add(
                        SendableUnit.MediaMessage(
                            orderIndex = orderIndex++,
                            attachment = segment.attachment.toPendingInput()
                        )
                    )
                }

                is ComposerSegment.LinkEmbed -> {
                    val preview = when (val state = segment.previewState) {
                        is ComposerLinkPreviewState.Success -> state.preview
                        else -> null
                    }
                    result.add(
                        SendableUnit.LinkEmbedMessage(
                            orderIndex = orderIndex++,
                            url = segment.url,
                            domain = segment.domain,
                            preview = preview
                        )
                    )
                }

                is ComposerSegment.Location -> {
                    result.add(
                        SendableUnit.LocationMessage(
                            orderIndex = orderIndex++,
                            attachment = segment.attachment.toPendingInput(),
                            latitude = segment.latitude,
                            longitude = segment.longitude,
                            address = segment.address,
                            name = segment.name
                        )
                    )
                }
            }
        }

        return result
    }

    /**
     * Split text content around detected URLs.
     * Returns a list of alternating TextMessage and LinkEmbedMessage units.
     */
    private fun splitTextWithUrls(text: String, startOrderIndex: Int): List<SendableUnit> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyList()

        val detectedUrls = UrlParsingUtils.detectUrls(trimmed)
        if (detectedUrls.isEmpty()) {
            return listOf(SendableUnit.TextMessage(startOrderIndex, trimmed))
        }

        val result = mutableListOf<SendableUnit>()
        var currentIndex = 0
        var orderIndex = startOrderIndex

        for (detected in detectedUrls) {
            // Add text before this URL (if any)
            if (detected.startIndex > currentIndex) {
                val textBefore = trimmed.substring(currentIndex, detected.startIndex).trim()
                if (textBefore.isNotBlank()) {
                    result.add(SendableUnit.TextMessage(orderIndex++, textBefore))
                }
            }

            // Add the URL as a link embed
            result.add(
                SendableUnit.LinkEmbedMessage(
                    orderIndex = orderIndex++,
                    url = detected.url,
                    domain = detected.domain,
                    preview = null // Preview will be fetched/cached by repository
                )
            )

            currentIndex = detected.endIndex
        }

        // Add remaining text after last URL (if any)
        if (currentIndex < trimmed.length) {
            val textAfter = trimmed.substring(currentIndex).trim()
            if (textAfter.isNotBlank()) {
                result.add(SendableUnit.TextMessage(orderIndex++, textAfter))
            }
        }

        return result
    }

    /**
     * Check if a document will result in multiple messages.
     * Useful for UI hints (e.g., showing split indicator).
     */
    fun willSplitIntoMultipleMessages(
        document: ComposerDocument,
        detectTypedUrls: Boolean = true
    ): Boolean {
        return split(document, detectTypedUrls).size > 1
    }

    /**
     * Count the number of messages that will be sent.
     */
    fun countMessages(
        document: ComposerDocument,
        detectTypedUrls: Boolean = true
    ): Int {
        return split(document, detectTypedUrls).size
    }

    /**
     * Convert AttachmentItem to PendingAttachmentInput for sending.
     */
    private fun AttachmentItem.toPendingInput(): PendingAttachmentInput {
        return PendingAttachmentInput(
            uri = uri,
            caption = caption,
            mimeType = mimeType,
            name = displayName,
            size = sizeBytes
        )
    }
}

/**
 * Result of splitting text around URLs.
 * Used internally by DocumentSplitter.
 */
internal sealed interface TextSplitPart {
    data class PlainText(val text: String) : TextSplitPart
    data class UrlPart(val detected: DetectedUrl) : TextSplitPart
}
