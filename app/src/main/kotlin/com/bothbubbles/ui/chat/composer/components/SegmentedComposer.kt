package com.bothbubbles.ui.chat.composer.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.composer.ComposerDocument
import com.bothbubbles.ui.chat.composer.ComposerLinkPreviewState
import com.bothbubbles.ui.chat.composer.ComposerSegment
import com.bothbubbles.ui.chat.composer.MentionSpan
import com.bothbubbles.ui.chat.composer.animations.ComposerMotionTokens
import com.bothbubbles.ui.theme.BothBubblesTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Segmented composer container for inline embeds.
 *
 * Renders a [ComposerDocument] as a vertical list of segments:
 * - [ComposerSegment.Text] → Text editor with spell checking
 * - [ComposerSegment.Media] → Media thumbnail card
 * - [ComposerSegment.LinkEmbed] → Link preview card
 * - [ComposerSegment.Location] → Location preview card
 *
 * Features:
 * - Inline embed cards that can be dismissed
 * - Focus management between text segments
 * - Backspace-at-start detection for segment deletion
 * - Dynamic height based on content
 *
 * @param document The composer document containing all segments
 * @param onTextChange Callback when a text segment's content changes
 * @param onTextChangeWithCursor Callback with text, cursor position, and segment ID
 * @param onRemoveSegment Callback to remove a segment by ID
 * @param onBackspaceAtStart Callback when backspace pressed at start of a text segment
 * @param sendMode Current send mode for placeholder text
 * @param isEnabled Whether editing is enabled
 * @param focusedSegmentId ID of the segment that should have focus (null for none)
 * @param onFocusChanged Callback when focus changes to a different segment
 * @param leadingContent Optional composable before the first segment (e.g., add button)
 * @param trailingContent Optional composable after the last segment (e.g., media buttons)
 * @param modifier Modifier for the container
 */
@Composable
fun SegmentedComposer(
    document: ComposerDocument,
    onTextChange: (segmentId: String, newText: String) -> Unit,
    onTextChangeWithCursor: ((segmentId: String, text: String, cursor: Int) -> Unit)? = null,
    onRemoveSegment: (segmentId: String) -> Unit,
    onBackspaceAtStart: (segmentId: String) -> Unit = {},
    sendMode: ChatSendMode = ChatSendMode.IMESSAGE,
    isEnabled: Boolean = true,
    isGroupChat: Boolean = false,
    focusedSegmentId: String? = null,
    onFocusChanged: (segmentId: String?, hasFocus: Boolean) -> Unit = { _, _ -> },
    onEditAttachment: ((segmentId: String) -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val inputColors = BothBubblesTheme.bubbleColors

    // Track focus request handling
    var pendingFocusRequest by remember { mutableStateOf<String?>(null) }

    // Set pending focus when focusedSegmentId changes
    LaunchedEffect(focusedSegmentId) {
        pendingFocusRequest = focusedSegmentId
    }

    // Determine placeholder based on send mode
    val placeholder = when (sendMode) {
        ChatSendMode.SMS -> "Text message"
        ChatSendMode.IMESSAGE -> "iMessage"
        ChatSendMode.AUTO -> "Message"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ComposerMotionTokens.Dimension.InputCornerRadius),
        color = inputColors.inputFieldBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            // Single text segment case - render inline with leading/trailing content
            if (document.segments.size == 1 && document.segments[0] is ComposerSegment.Text) {
                val textSegment = document.segments[0] as ComposerSegment.Text
                SingleTextSegmentLayout(
                    segment = textSegment,
                    placeholder = placeholder,
                    isEnabled = isEnabled,
                    isGroupChat = isGroupChat,
                    onTextChange = { onTextChange(textSegment.id, it) },
                    onTextChangeWithCursor = onTextChangeWithCursor?.let { callback ->
                        { text, cursor -> callback(textSegment.id, text, cursor) }
                    },
                    onBackspaceAtStart = { onBackspaceAtStart(textSegment.id) },
                    onFocusChange = { hasFocus -> onFocusChanged(textSegment.id, hasFocus) },
                    requestFocus = pendingFocusRequest == textSegment.id,
                    onFocusRequestHandled = { pendingFocusRequest = null },
                    leadingContent = leadingContent,
                    trailingContent = trailingContent
                )
            } else {
                // Multiple segments - render as column with embeds
                MultiSegmentLayout(
                    document = document,
                    placeholder = placeholder,
                    isEnabled = isEnabled,
                    isGroupChat = isGroupChat,
                    onTextChange = onTextChange,
                    onTextChangeWithCursor = onTextChangeWithCursor,
                    onRemoveSegment = onRemoveSegment,
                    onBackspaceAtStart = onBackspaceAtStart,
                    onFocusChanged = onFocusChanged,
                    onEditAttachment = onEditAttachment,
                    pendingFocusRequest = pendingFocusRequest,
                    onFocusRequestHandled = { pendingFocusRequest = null },
                    leadingContent = leadingContent,
                    trailingContent = trailingContent
                )
            }
        }
    }
}

/**
 * Layout for a single text segment with leading/trailing content inline.
 */
@Composable
private fun SingleTextSegmentLayout(
    segment: ComposerSegment.Text,
    placeholder: String,
    isEnabled: Boolean,
    isGroupChat: Boolean,
    onTextChange: (String) -> Unit,
    onTextChangeWithCursor: ((String, Int) -> Unit)?,
    onBackspaceAtStart: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    requestFocus: Boolean,
    onFocusRequestHandled: () -> Unit,
    leadingContent: @Composable (() -> Unit)?,
    trailingContent: @Composable (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Leading content (add button)
        if (leadingContent != null) {
            Box(modifier = Modifier.padding(start = 2.dp)) {
                leadingContent()
            }
        }

        // Text segment
        Box(modifier = Modifier.weight(1f)) {
            TextSegmentEditor(
                segmentId = segment.id,
                text = segment.content,
                mentions = segment.mentions,
                placeholder = placeholder,
                onTextChange = onTextChange,
                onTextChangeWithCursor = onTextChangeWithCursor,
                onBackspaceAtStart = onBackspaceAtStart,
                onFocusChange = onFocusChange,
                requestFocus = requestFocus,
                onFocusRequestHandled = onFocusRequestHandled,
                isEnabled = isEnabled
            )
        }

        // Trailing content (media buttons)
        if (trailingContent != null) {
            Box(modifier = Modifier.padding(end = 2.dp)) {
                trailingContent()
            }
        }
    }
}

/**
 * Layout for multiple segments with embeds inline.
 */
@Composable
private fun MultiSegmentLayout(
    document: ComposerDocument,
    placeholder: String,
    isEnabled: Boolean,
    isGroupChat: Boolean,
    onTextChange: (segmentId: String, newText: String) -> Unit,
    onTextChangeWithCursor: ((segmentId: String, text: String, cursor: Int) -> Unit)?,
    onRemoveSegment: (segmentId: String) -> Unit,
    onBackspaceAtStart: (segmentId: String) -> Unit,
    onFocusChanged: (segmentId: String?, hasFocus: Boolean) -> Unit,
    onEditAttachment: ((segmentId: String) -> Unit)?,
    pendingFocusRequest: String?,
    onFocusRequestHandled: () -> Unit,
    leadingContent: @Composable (() -> Unit)?,
    trailingContent: @Composable (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        document.segments.forEachIndexed { index, segment ->
            val isFirst = index == 0
            val isLast = index == document.segments.lastIndex

            when (segment) {
                is ComposerSegment.Text -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Leading content only on first text segment
                        if (isFirst && leadingContent != null) {
                            Box(modifier = Modifier.padding(start = 2.dp)) {
                                leadingContent()
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            TextSegmentEditor(
                                segmentId = segment.id,
                                text = segment.content,
                                mentions = segment.mentions,
                                placeholder = if (isFirst) placeholder else "",
                                onTextChange = { onTextChange(segment.id, it) },
                                onTextChangeWithCursor = onTextChangeWithCursor?.let { callback ->
                                    { text, cursor -> callback(segment.id, text, cursor) }
                                },
                                onBackspaceAtStart = { onBackspaceAtStart(segment.id) },
                                onFocusChange = { hasFocus -> onFocusChanged(segment.id, hasFocus) },
                                requestFocus = pendingFocusRequest == segment.id,
                                onFocusRequestHandled = onFocusRequestHandled,
                                isEnabled = isEnabled
                            )
                        }

                        // Trailing content only on last text segment
                        if (isLast && trailingContent != null) {
                            Box(modifier = Modifier.padding(end = 2.dp)) {
                                trailingContent()
                            }
                        }
                    }
                }

                is ComposerSegment.Media -> {
                    // Media embed with padding for alignment
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 40.dp, end = 8.dp) // Align with text
                    ) {
                        ComposerMediaEmbed(
                            attachment = segment.attachment,
                            onRemove = { onRemoveSegment(segment.id) },
                            onEdit = onEditAttachment?.let { { it(segment.id) } }
                        )
                    }
                }

                is ComposerSegment.LinkEmbed -> {
                    // Link embed with padding for alignment
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 40.dp, end = 8.dp)
                    ) {
                        ComposerLinkEmbed(
                            url = segment.url,
                            domain = segment.domain,
                            previewState = segment.previewState,
                            onRemove = { onRemoveSegment(segment.id) }
                        )
                    }
                }

                is ComposerSegment.Location -> {
                    // Location embed with padding for alignment
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 40.dp, end = 8.dp)
                    ) {
                        ComposerLocationEmbed(
                            latitude = segment.latitude,
                            longitude = segment.longitude,
                            name = segment.name,
                            address = segment.address,
                            onRemove = { onRemoveSegment(segment.id) }
                        )
                    }
                }
            }
        }
    }
}
