package com.bothbubbles.ui.chat.composer

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.bothbubbles.data.local.db.entity.LinkPreviewEntity
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.util.UUID

/**
 * Represents a segment of content in the composer document.
 * Segments are ordered and can be text, media, link embeds, or locations.
 *
 * The document model enables inline embeds that can be:
 * - Moved around via cut/paste
 * - Deleted with backspace
 * - Reordered within the document
 * - Split into separate messages on send
 */
@Stable
sealed interface ComposerSegment {
    /** Unique identifier for this segment */
    val id: String

    /**
     * Plain text segment.
     * Text remains unformatted (no rich text styling).
     */
    data class Text(
        override val id: String = generateSegmentId(),
        val content: String,
        val mentions: ImmutableList<MentionSpan> = persistentListOf()
    ) : ComposerSegment {
        companion object {
            /** Creates an empty text segment */
            fun empty() = Text(content = "")
        }
    }

    /**
     * Media attachment segment (photo, video, audio, document).
     * Renders as a thumbnail card with dismiss button.
     */
    data class Media(
        override val id: String = generateSegmentId(),
        val attachment: AttachmentItem
    ) : ComposerSegment

    /**
     * Link embed segment with preview metadata.
     * Shows loading/success/failed state as an inline card.
     */
    data class LinkEmbed(
        override val id: String = generateSegmentId(),
        val url: String,
        val domain: String,
        val previewState: ComposerLinkPreviewState = ComposerLinkPreviewState.Loading
    ) : ComposerSegment

    /**
     * Location embed segment.
     * Shows a static map preview with address.
     */
    data class Location(
        override val id: String = generateSegmentId(),
        val latitude: Double,
        val longitude: Double,
        val address: String?,
        val name: String?,
        /** The underlying attachment item for sending */
        val attachment: AttachmentItem
    ) : ComposerSegment

    companion object {
        /** Generates a unique segment ID */
        fun generateSegmentId(): String = UUID.randomUUID().toString()
    }
}

/**
 * State for link preview in the composer.
 * Similar to LinkPreviewState but optimized for composer use.
 */
@Immutable
sealed interface ComposerLinkPreviewState {
    /** Fetching preview metadata */
    data object Loading : ComposerLinkPreviewState

    /** Preview fetched successfully */
    data class Success(val preview: LinkPreviewEntity) : ComposerLinkPreviewState

    /** Preview fetch failed - show minimal card with domain */
    data object Failed : ComposerLinkPreviewState

    /** URL doesn't support previews - show minimal card */
    data object NoPreview : ComposerLinkPreviewState
}

/**
 * Represents the composer content as an ordered list of segments.
 *
 * This replaces the previous model of separate `text: String` and
 * `attachments: List<AttachmentItem>` with a unified document model
 * that preserves ordering and enables inline embeds.
 */
@Immutable
data class ComposerDocument(
    val segments: ImmutableList<ComposerSegment> = persistentListOf(ComposerSegment.Text.empty())
) {
    /**
     * Whether the document has any sendable content.
     */
    val canSend: Boolean
        get() = segments.any { segment ->
            when (segment) {
                is ComposerSegment.Text -> segment.content.isNotBlank()
                is ComposerSegment.Media -> true
                is ComposerSegment.LinkEmbed -> true
                is ComposerSegment.Location -> true
            }
        }

    /**
     * Whether the document is empty (only contains empty text).
     */
    val isEmpty: Boolean
        get() = segments.isEmpty() ||
                (segments.size == 1 && segments[0] is ComposerSegment.Text &&
                 (segments[0] as ComposerSegment.Text).content.isEmpty())

    /**
     * Extract all text content concatenated (for drafts, character counting, etc.).
     */
    val fullText: String
        get() = segments
            .filterIsInstance<ComposerSegment.Text>()
            .joinToString("\n") { it.content }

    /**
     * All media segments in document order.
     */
    val mediaSegments: List<ComposerSegment.Media>
        get() = segments.filterIsInstance<ComposerSegment.Media>()

    /**
     * All link embed segments in document order.
     */
    val linkEmbeds: List<ComposerSegment.LinkEmbed>
        get() = segments.filterIsInstance<ComposerSegment.LinkEmbed>()

    /**
     * All location segments in document order.
     */
    val locationSegments: List<ComposerSegment.Location>
        get() = segments.filterIsInstance<ComposerSegment.Location>()

    /**
     * Total number of attachments (media + locations).
     */
    val attachmentCount: Int
        get() = mediaSegments.size + locationSegments.size

    /**
     * Whether there are any image attachments that can be compressed.
     */
    val hasCompressibleImages: Boolean
        get() = mediaSegments.any { it.attachment.isImage }

    /**
     * Insert a segment at the specified index.
     * Returns a new document with the segment inserted.
     */
    fun insertSegment(index: Int, segment: ComposerSegment): ComposerDocument {
        val newSegments = segments.toMutableList()
        val safeIndex = index.coerceIn(0, segments.size)
        newSegments.add(safeIndex, segment)
        return copy(segments = newSegments.toImmutableList())
    }

    /**
     * Insert a segment after the segment with the given ID.
     * If the ID is not found, appends to the end.
     */
    fun insertAfter(afterId: String, segment: ComposerSegment): ComposerDocument {
        val index = segments.indexOfFirst { it.id == afterId }
        return if (index >= 0) {
            insertSegment(index + 1, segment)
        } else {
            insertSegment(segments.size, segment)
        }
    }

    /**
     * Append a segment to the end of the document.
     */
    fun appendSegment(segment: ComposerSegment): ComposerDocument {
        return copy(segments = (segments + segment).toImmutableList())
    }

    /**
     * Remove a segment by ID.
     * If removing the last text segment, replaces it with an empty text segment.
     */
    fun removeSegment(segmentId: String): ComposerDocument {
        val newSegments = segments.filter { it.id != segmentId }

        // Ensure at least one text segment exists
        val finalSegments = if (newSegments.none { it is ComposerSegment.Text }) {
            newSegments + ComposerSegment.Text.empty()
        } else {
            newSegments
        }

        return copy(segments = finalSegments.toImmutableList())
    }

    /**
     * Update a segment by ID.
     */
    fun updateSegment(segmentId: String, updater: (ComposerSegment) -> ComposerSegment): ComposerDocument {
        val newSegments = segments.map { segment ->
            if (segment.id == segmentId) updater(segment) else segment
        }
        return copy(segments = newSegments.toImmutableList())
    }

    /**
     * Update the text content of a text segment.
     */
    fun updateTextSegment(segmentId: String, newContent: String): ComposerDocument {
        return updateSegment(segmentId) { segment ->
            if (segment is ComposerSegment.Text) {
                segment.copy(content = newContent)
            } else {
                segment
            }
        }
    }

    /**
     * Update the preview state of a link embed segment.
     */
    fun updateLinkPreviewState(segmentId: String, state: ComposerLinkPreviewState): ComposerDocument {
        return updateSegment(segmentId) { segment ->
            if (segment is ComposerSegment.LinkEmbed) {
                segment.copy(previewState = state)
            } else {
                segment
            }
        }
    }

    /**
     * Move a segment from one position to another.
     */
    fun moveSegment(segmentId: String, toIndex: Int): ComposerDocument {
        val currentIndex = segments.indexOfFirst { it.id == segmentId }
        if (currentIndex < 0 || currentIndex == toIndex) return this

        val newSegments = segments.toMutableList()
        val segment = newSegments.removeAt(currentIndex)
        val adjustedIndex = if (toIndex > currentIndex) toIndex - 1 else toIndex
        newSegments.add(adjustedIndex.coerceIn(0, newSegments.size), segment)

        return copy(segments = newSegments.toImmutableList())
    }

    /**
     * Get the segment at the specified index.
     */
    fun getSegmentAt(index: Int): ComposerSegment? = segments.getOrNull(index)

    /**
     * Get a segment by ID.
     */
    fun getSegmentById(id: String): ComposerSegment? = segments.find { it.id == id }

    /**
     * Get the index of a segment by ID.
     */
    fun indexOfSegment(segmentId: String): Int = segments.indexOfFirst { it.id == segmentId }

    /**
     * Find the first text segment (for initial focus).
     */
    fun findFirstTextSegment(): ComposerSegment.Text? =
        segments.filterIsInstance<ComposerSegment.Text>().firstOrNull()

    /**
     * Find the last text segment.
     */
    fun findLastTextSegment(): ComposerSegment.Text? =
        segments.filterIsInstance<ComposerSegment.Text>().lastOrNull()

    /**
     * Split a text segment at the cursor position, inserting a new segment between.
     * Used when pasting a link or adding media at cursor position.
     *
     * @param segmentId The ID of the text segment to split
     * @param cursorPosition The position within the text to split at
     * @param newSegment The segment to insert at the split point
     * @return New document with the text split and new segment inserted
     */
    fun splitTextAndInsert(
        segmentId: String,
        cursorPosition: Int,
        newSegment: ComposerSegment
    ): ComposerDocument {
        val segment = getSegmentById(segmentId)
        if (segment !is ComposerSegment.Text) return appendSegment(newSegment)

        val text = segment.content
        val safeCursor = cursorPosition.coerceIn(0, text.length)

        val textBefore = text.substring(0, safeCursor)
        val textAfter = text.substring(safeCursor)

        val index = indexOfSegment(segmentId)
        val newSegments = segments.toMutableList()

        // Remove the original segment
        newSegments.removeAt(index)

        // Insert: text before (if not empty) + new segment + text after (if not empty)
        var insertIndex = index

        if (textBefore.isNotEmpty()) {
            newSegments.add(insertIndex++, segment.copy(content = textBefore))
        }

        newSegments.add(insertIndex++, newSegment)

        if (textAfter.isNotEmpty()) {
            newSegments.add(insertIndex, ComposerSegment.Text(content = textAfter))
        }

        // Ensure at least one text segment exists
        if (newSegments.none { it is ComposerSegment.Text }) {
            newSegments.add(ComposerSegment.Text.empty())
        }

        return copy(segments = newSegments.toImmutableList())
    }

    /**
     * Merge adjacent text segments (cleanup operation).
     */
    fun mergeAdjacentTextSegments(): ComposerDocument {
        val result = mutableListOf<ComposerSegment>()

        for (segment in segments) {
            val last = result.lastOrNull()
            if (segment is ComposerSegment.Text && last is ComposerSegment.Text) {
                // Merge with previous text segment
                result[result.lastIndex] = last.copy(
                    content = last.content + "\n" + segment.content,
                    mentions = (last.mentions + segment.mentions).toImmutableList()
                )
            } else {
                result.add(segment)
            }
        }

        return copy(segments = result.toImmutableList())
    }

    /**
     * Clear all content, returning to initial state.
     */
    fun clear(): ComposerDocument = ComposerDocument()

    companion object {
        /**
         * Create a document from legacy text + attachments model.
         * Used for migration/compatibility.
         */
        fun fromLegacy(text: String, attachments: List<AttachmentItem>): ComposerDocument {
            val segments = mutableListOf<ComposerSegment>()

            // Add text segment first
            if (text.isNotEmpty()) {
                segments.add(ComposerSegment.Text(content = text))
            }

            // Add attachment segments
            attachments.forEach { attachment ->
                if (attachment.isVLocation) {
                    // Location attachments are handled separately
                    // For now, treat as media
                    segments.add(ComposerSegment.Media(attachment = attachment))
                } else {
                    segments.add(ComposerSegment.Media(attachment = attachment))
                }
            }

            // Ensure at least one text segment
            if (segments.none { it is ComposerSegment.Text }) {
                segments.add(0, ComposerSegment.Text.empty())
            }

            return ComposerDocument(segments = segments.toImmutableList())
        }

        /**
         * Create a document with just text content.
         */
        fun fromText(text: String): ComposerDocument {
            return ComposerDocument(
                segments = persistentListOf(ComposerSegment.Text(content = text))
            )
        }
    }
}
