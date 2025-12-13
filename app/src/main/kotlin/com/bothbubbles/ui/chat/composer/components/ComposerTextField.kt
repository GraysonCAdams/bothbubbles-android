package com.bothbubbles.ui.chat.composer.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.bothbubbles.R
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.composer.animations.ComposerMotionTokens
import com.bothbubbles.ui.theme.BothBubblesTheme

/**
 * Material Design 3 styled text input field for the chat composer.
 *
 * Features:
 * - Stadium/pill shape with 24dp corner radius (Google Messages style)
 * - Surface variant background for subtle contrast
 * - Smooth height animation for multiline input (max 4 lines)
 * - Dynamic placeholder based on send mode (iMessage vs SMS/Text)
 * - Support for disabled state when SMS input is blocked
 *
 * @param text Current text value
 * @param onTextChange Callback when text changes
 * @param sendMode Current send mode for placeholder text
 * @param isEnabled Whether the text field is enabled
 * @param onSmsBlockedClick Callback when user taps disabled field
 * @param onFocusChanged Callback when focus state changes
 * @param leadingContent Optional composable for content before the text field
 * @param trailingContent Optional composable for content after the text field
 * @param modifier Modifier for the outer container
 */
@Composable
fun ComposerTextField(
    text: String,
    onTextChange: (String) -> Unit,
    sendMode: ChatSendMode,
    isEnabled: Boolean = true,
    onSmsBlockedClick: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val inputColors = BothBubblesTheme.bubbleColors
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    // Placeholder text based on send mode and enabled state
    val placeholderText = when {
        !isEnabled -> "Not default SMS app"
        sendMode == ChatSendMode.SMS -> stringResource(R.string.message_placeholder_text)
        else -> stringResource(R.string.message_placeholder_imessage)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ),
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
                value = text,
                onValueChange = { if (isEnabled) onTextChange(it) },
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
                maxLines = 4,
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
