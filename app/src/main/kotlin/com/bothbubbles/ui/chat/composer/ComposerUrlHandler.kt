package com.bothbubbles.ui.chat.composer

import com.bothbubbles.data.local.db.entity.LinkPreviewEntity
import com.bothbubbles.data.local.db.entity.LinkPreviewFetchStatus
import com.bothbubbles.data.repository.LinkPreviewRepository
import com.bothbubbles.util.parsing.DetectedUrl
import com.bothbubbles.util.parsing.UrlParsingUtils
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Handles URL detection and link preview fetching for the composer.
 *
 * Responsibilities:
 * - Detects pasted URLs and converts them to LinkEmbed segments
 * - Manages link preview fetching via LinkPreviewRepository
 * - Updates document with preview state changes
 * - Handles clipboard URL detection
 *
 * Usage:
 * ```kotlin
 * val handler = ComposerUrlHandler(linkPreviewRepository, scope)
 *
 * // When user pastes a URL
 * val document = handler.handlePastedUrl(url, currentDocument)
 *
 * // Handler automatically fetches preview and updates segment state
 * ```
 */
class ComposerUrlHandler @Inject constructor(
    private val linkPreviewRepository: LinkPreviewRepository
) {
    private var scope: CoroutineScope? = null
    private val activePreviewJobs = mutableMapOf<String, Job>()

    // Document with current link embed states
    private val _document = MutableStateFlow(ComposerDocument())
    val document: StateFlow<ComposerDocument> = _document.asStateFlow()

    /**
     * Initialize with a coroutine scope for fetching previews.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
    }

    /**
     * Set the current document (for external state management).
     */
    fun setDocument(document: ComposerDocument) {
        _document.value = document
    }

    /**
     * Checks if the given text is a URL that should become a link embed.
     *
     * @param text The text to check (typically pasted content)
     * @return DetectedUrl if the text is a URL, null otherwise
     */
    fun detectUrl(text: String): DetectedUrl? {
        val trimmed = text.trim()

        // Quick check: must look like a URL
        if (!trimmed.contains(".")) return null
        if (trimmed.contains("\n")) return null // Multi-line text is not a URL
        if (trimmed.contains(" ") && !trimmed.startsWith("http")) return null // Space in non-URL

        // Use UrlParsingUtils to properly detect
        return UrlParsingUtils.getFirstUrl(trimmed)
    }

    /**
     * Creates a link embed segment from a detected URL.
     * Automatically starts fetching the preview.
     *
     * @param url The URL to create an embed for
     * @return A new LinkEmbed segment with Loading state
     */
    fun createLinkEmbed(url: String): ComposerSegment.LinkEmbed {
        val domain = UrlParsingUtils.extractDomain(url)
        val segment = ComposerSegment.LinkEmbed(
            url = url,
            domain = domain,
            previewState = ComposerLinkPreviewState.Loading
        )

        // Start fetching preview
        fetchPreviewForSegment(segment.id, url)

        return segment
    }

    /**
     * Handles pasted text that might contain a URL.
     * If the pasted content is a standalone URL, creates a link embed.
     * Otherwise, returns the text as-is.
     *
     * @param pastedText The pasted content
     * @param currentDocument The current composer document
     * @param textSegmentId The ID of the text segment where paste occurred
     * @param cursorPosition The cursor position within the text segment
     * @return Updated document with link embed inserted, or null if not a URL
     */
    fun handlePastedUrl(
        pastedText: String,
        currentDocument: ComposerDocument,
        textSegmentId: String,
        cursorPosition: Int
    ): ComposerDocument? {
        val detectedUrl = detectUrl(pastedText) ?: return null

        // Only convert to embed if the entire pasted text is the URL
        // (or very close - allow for trailing whitespace)
        val textIsUrl = pastedText.trim() == detectedUrl.matchedText.trim() ||
                        pastedText.trim() == detectedUrl.url.trim()

        if (!textIsUrl) {
            Timber.d("Pasted text contains URL but is not a standalone URL, skipping embed conversion")
            return null
        }

        // Create link embed and insert into document
        val linkEmbed = createLinkEmbed(detectedUrl.url)
        val updatedDocument = currentDocument.splitTextAndInsert(
            segmentId = textSegmentId,
            cursorPosition = cursorPosition,
            newSegment = linkEmbed
        )

        _document.value = updatedDocument
        return updatedDocument
    }

    /**
     * Fetches a link preview for a segment and updates the document.
     */
    private fun fetchPreviewForSegment(segmentId: String, url: String) {
        val currentScope = scope ?: run {
            Timber.w("ComposerUrlHandler not initialized with scope, cannot fetch preview")
            return
        }

        // Cancel any existing fetch for this segment
        activePreviewJobs[segmentId]?.cancel()

        activePreviewJobs[segmentId] = currentScope.launch {
            try {
                Timber.d("Fetching preview for URL: $url")

                // Observe the preview flow for reactive updates
                linkPreviewRepository.observeLinkPreview(url).collect { preview ->
                    if (preview != null) {
                        val newState = when (preview.fetchStatus) {
                            LinkPreviewFetchStatus.SUCCESS.name -> {
                                ComposerLinkPreviewState.Success(preview)
                            }
                            LinkPreviewFetchStatus.FAILED.name -> {
                                ComposerLinkPreviewState.Failed
                            }
                            LinkPreviewFetchStatus.NO_PREVIEW.name -> {
                                ComposerLinkPreviewState.NoPreview
                            }
                            else -> {
                                // Still pending/loading
                                ComposerLinkPreviewState.Loading
                            }
                        }

                        // Update the document with new preview state
                        updateSegmentPreviewState(segmentId, newState)

                        // Stop observing once we have a final state
                        if (newState !is ComposerLinkPreviewState.Loading) {
                            Timber.d("Preview fetch complete for segment $segmentId: ${newState::class.simpleName}")
                            // Don't cancel here - let flow complete naturally
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error fetching preview for segment $segmentId")
                updateSegmentPreviewState(segmentId, ComposerLinkPreviewState.Failed)
            }
        }
    }

    /**
     * Updates the preview state for a specific segment.
     */
    private fun updateSegmentPreviewState(segmentId: String, state: ComposerLinkPreviewState) {
        _document.update { doc ->
            doc.updateLinkPreviewState(segmentId, state)
        }
    }

    /**
     * Cancels preview fetching for a segment (e.g., when segment is removed).
     */
    fun cancelPreviewFetch(segmentId: String) {
        activePreviewJobs[segmentId]?.cancel()
        activePreviewJobs.remove(segmentId)
    }

    /**
     * Clears all state and cancels pending operations.
     */
    fun clear() {
        activePreviewJobs.values.forEach { it.cancel() }
        activePreviewJobs.clear()
        _document.value = ComposerDocument()
    }

    /**
     * Detects URLs in a text segment and converts them to link embeds.
     * Used at send time for typed URLs (not pasted).
     *
     * @param document The current document
     * @return Updated document with URLs converted to link embeds
     */
    fun convertTypedUrlsToEmbeds(document: ComposerDocument): ComposerDocument {
        var currentDoc = document
        var modified = true

        // Keep processing until no more URLs found (handles multiple URLs)
        while (modified) {
            modified = false

            for (segment in currentDoc.segments) {
                if (segment !is ComposerSegment.Text) continue
                if (segment.content.isBlank()) continue

                val firstUrl = UrlParsingUtils.getFirstUrl(segment.content)
                if (firstUrl != null) {
                    // Found a URL - split the text and insert a link embed
                    val textBefore = segment.content.substring(0, firstUrl.startIndex)
                    val textAfter = segment.content.substring(firstUrl.endIndex)

                    val linkEmbed = ComposerSegment.LinkEmbed(
                        url = firstUrl.url,
                        domain = firstUrl.domain,
                        // At send time, we don't need to fetch preview - just mark as success/loading
                        // The preview will be fetched when the message is received
                        previewState = ComposerLinkPreviewState.Loading
                    )

                    // Build new segments list
                    val newSegments = mutableListOf<ComposerSegment>()
                    for (s in currentDoc.segments) {
                        if (s.id == segment.id) {
                            // Replace with: textBefore (if any) + linkEmbed + textAfter (if any)
                            if (textBefore.isNotBlank()) {
                                newSegments.add(ComposerSegment.Text(content = textBefore.trim()))
                            }
                            newSegments.add(linkEmbed)
                            if (textAfter.isNotBlank()) {
                                newSegments.add(ComposerSegment.Text(content = textAfter.trim()))
                            }
                        } else {
                            newSegments.add(s)
                        }
                    }

                    // Ensure at least one text segment
                    if (newSegments.none { it is ComposerSegment.Text }) {
                        newSegments.add(ComposerSegment.Text.empty())
                    }

                    currentDoc = ComposerDocument(
                        segments = newSegments.toImmutableList()
                    )
                    modified = true
                    break // Restart loop with new document
                }
            }
        }

        return currentDoc
    }

    /**
     * Gets the cached preview for a URL if available.
     * Used when sending to include preview data in link embed messages.
     */
    suspend fun getCachedPreview(url: String): LinkPreviewEntity? {
        return try {
            linkPreviewRepository.getLinkPreview(url)
        } catch (e: Exception) {
            Timber.w(e, "Error getting cached preview for $url")
            null
        }
    }
}
