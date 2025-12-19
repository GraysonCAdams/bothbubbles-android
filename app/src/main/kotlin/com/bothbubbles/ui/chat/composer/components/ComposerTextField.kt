package com.bothbubbles.ui.chat.composer.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bothbubbles.R
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.composer.MentionSpan
import com.bothbubbles.ui.chat.composer.animations.ComposerMotionTokens
import com.bothbubbles.ui.theme.BothBubblesTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Material Design 3 styled text input field for the chat composer.
 *
 * Features:
 * - Stadium/pill shape with 24dp corner radius (Google Messages style)
 * - Surface variant background for subtle contrast
 * - Smooth height animation for multiline input (max 4 lines)
 * - Dynamic placeholder based on send mode (iMessage vs SMS/Text)
 * - Support for disabled state when SMS input is blocked
 * - Mention highlighting with styled spans
 *
 * @param text Current text value
 * @param onTextChange Callback when text changes
 * @param sendMode Current send mode for placeholder text
 * @param mentions List of mention spans to highlight in the text
 * @param onTextChangedWithCursor Callback with text and cursor position (for mention detection)
 * @param isEnabled Whether the text field is enabled
 * @param onSmsBlockedClick Callback when user taps disabled field
 * @param onFocusChanged Callback when focus state changes
 * @param shouldRequestFocus When true, requests focus on the text field (e.g., after camera capture)
 * @param onFocusRequested Callback when focus request has been handled (to clear the trigger)
 * @param leadingContent Optional composable for content before the text field
 * @param trailingContent Optional composable for content after the text field
 * @param modifier Modifier for the outer container
 */
@Composable
fun ComposerTextField(
    text: String,
    onTextChange: (String) -> Unit,
    sendMode: ChatSendMode,
    mentions: ImmutableList<MentionSpan> = persistentListOf(),
    onTextChangedWithCursor: ((String, Int) -> Unit)? = null,
    isEnabled: Boolean = true,
    onSmsBlockedClick: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
    shouldRequestFocus: Boolean = false,
    onFocusRequested: () -> Unit = {},
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val inputColors = BothBubblesTheme.bubbleColors
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    // Track line count to force height updates when text wraps
    var lineCount by remember { mutableIntStateOf(1) }

    // Use TextFieldValue to track both text and cursor position
    var textFieldValue by remember { mutableStateOf(TextFieldValue(text)) }

    // Request focus when triggered (e.g., after camera capture)
    LaunchedEffect(shouldRequestFocus) {
        if (shouldRequestFocus) {
            focusRequester.requestFocus()
            onFocusRequested()
        }
    }

    // Sync external text changes (e.g., when mention is inserted)
    LaunchedEffect(text) {
        if (textFieldValue.text != text) {
            // Keep cursor at end when text changes externally
            textFieldValue = TextFieldValue(
                text = text,
                selection = TextRange(text.length)
            )
        }
    }

    // Calculate minimum height based on line count
    // Line height ~24dp, base height 40dp for single line
    val calculatedMinHeight = (40 + (lineCount - 1) * 24).dp

    // Placeholder text based on send mode and enabled state
    val placeholderText = when {
        !isEnabled -> "Not default SMS app"
        sendMode == ChatSendMode.SMS -> stringResource(R.string.message_placeholder_text)
        else -> stringResource(R.string.message_placeholder_imessage)
    }

    // Mention color for visual transformation
    val mentionColor = MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = calculatedMinHeight),
        shape = RoundedCornerShape(ComposerMotionTokens.Dimension.InputCornerRadius),
        color = inputColors.inputFieldBackground
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .then(
                    if (!isEnabled) {
                        Modifier.clickable { onSmsBlockedClick() }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            // Leading content (e.g., add button)
            if (leadingContent != null) {
                Box(modifier = Modifier.align(Alignment.CenterStart)) {
                    leadingContent()
                }
            }

            // Text field with placeholder
            BasicTextField(
                value = textFieldValue,
                onValueChange = { newValue ->
                    if (isEnabled) {
                        textFieldValue = newValue
                        onTextChange(newValue.text)
                        // Notify about cursor position for mention detection
                        onTextChangedWithCursor?.invoke(newValue.text, newValue.selection.start)
                    }
                },
                enabled = isEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (leadingContent != null) 36.dp else 12.dp,
                        end = if (trailingContent != null) 72.dp else 12.dp,
                        top = 8.dp,
                        bottom = 8.dp
                    )
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        isFocused = focusState.isFocused
                        onFocusChanged(focusState.isFocused)
                    },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (isEnabled) {
                        inputColors.inputText
                    } else {
                        inputColors.inputText.copy(alpha = 0.5f)
                    }
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default
                ),
                minLines = 1,
                maxLines = 4,
                onTextLayout = { textLayoutResult ->
                    // Track line count changes to trigger recomposition for height
                    val newLineCount = textLayoutResult.lineCount.coerceIn(1, 4)
                    if (newLineCount != lineCount) {
                        lineCount = newLineCount
                    }
                },
                visualTransformation = MentionVisualTransformation(mentions, mentionColor),
                decorationBox = { innerTextField ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = placeholderText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = inputColors.inputPlaceholder.copy(
                                    alpha = if (isEnabled) 1f else 0.5f
                                )
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // Trailing content (e.g., emoji, image buttons)
            if (trailingContent != null) {
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    trailingContent()
                }
            }
        }
    }
}

/**
 * Build an AnnotatedString with mention spans styled.
 */
@Composable
private fun buildMentionAnnotatedString(
    text: String,
    mentions: ImmutableList<MentionSpan>,
    mentionColor: androidx.compose.ui.graphics.Color
): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        append(text)
        mentions.forEach { mention ->
            if (mention.startIndex >= 0 && mention.endIndex <= text.length) {
                addStyle(
                    style = SpanStyle(
                        color = mentionColor,
                        fontWeight = FontWeight.SemiBold
                    ),
                    start = mention.startIndex,
                    end = mention.endIndex
                )
            }
        }
    }
}

/**
 * Visual transformation that applies mention styling to text.
 */
private class MentionVisualTransformation(
    private val mentions: ImmutableList<MentionSpan>,
    private val mentionColor: androidx.compose.ui.graphics.Color
) : androidx.compose.ui.text.input.VisualTransformation {

    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val builder = androidx.compose.ui.text.AnnotatedString.Builder(text)

        mentions.forEach { mention ->
            if (mention.startIndex >= 0 && mention.endIndex <= text.length) {
                builder.addStyle(
                    style = SpanStyle(
                        color = mentionColor,
                        fontWeight = FontWeight.SemiBold
                    ),
                    start = mention.startIndex,
                    end = mention.endIndex
                )
            }
        }

        return androidx.compose.ui.text.input.TransformedText(
            builder.toAnnotatedString(),
            androidx.compose.ui.text.input.OffsetMapping.Identity
        )
    }
}
