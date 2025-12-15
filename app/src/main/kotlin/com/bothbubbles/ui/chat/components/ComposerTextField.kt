package com.bothbubbles.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.bothbubbles.R
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.theme.BubbleColors

/**
 * Controlled text field for composing messages.
 *
 * This component is purely controlled - it receives [value] and emits [onValueChange]
 * without owning any internal text state. State should be hoisted to the parent.
 *
 * @param value The current text value (controlled)
 * @param onValueChange Callback when text changes
 * @param modifier Modifier for the text field
 * @param onFocusChanged Callback when focus state changes
 * @param placeholder Placeholder text to display when empty
 * @param enabled Whether the text field is enabled
 * @param readOnly Whether the text field is read-only
 * @param maxLines Maximum number of lines to display
 * @param inputColors Colors for the input styling
 * @param keyboardOptions Keyboard configuration
 * @param keyboardActions Keyboard action handlers
 */
@Composable
fun ComposerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
    placeholder: String = stringResource(R.string.message_placeholder_imessage),
    enabled: Boolean = true,
    readOnly: Boolean = false,
    maxLines: Int = 4,
    inputColors: BubbleColors? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default.copy(
        imeAction = ImeAction.Default
    ),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    onDisabledClick: (() -> Unit)? = null
) {
    val colors = inputColors ?: com.bothbubbles.ui.theme.BothBubblesTheme.bubbleColors

    TextField(
        value = value,
        onValueChange = { if (enabled && !readOnly) onValueChange(it) },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                onFocusChanged(focusState.isFocused)
            }
            .then(
                if (!enabled && onDisabledClick != null) {
                    Modifier.clickable { onDisabledClick() }
                } else Modifier
            ),
        enabled = enabled,
        readOnly = readOnly,
        placeholder = {
            Text(
                text = placeholder,
                color = colors.inputPlaceholder.copy(
                    alpha = if (!enabled) 0.5f else 1f
                )
            )
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedTextColor = colors.inputText,
            unfocusedTextColor = colors.inputText,
            disabledTextColor = colors.inputText.copy(alpha = 0.5f),
            cursorColor = MaterialTheme.colorScheme.primary
        ),
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions
    )
}

/**
 * Returns the appropriate placeholder text for the given send mode.
 */
@Composable
fun getPlaceholderForSendMode(
    sendMode: ChatSendMode,
    isBlocked: Boolean = false
): String {
    return when {
        isBlocked -> "Not default SMS app"
        sendMode == ChatSendMode.SMS -> stringResource(R.string.message_placeholder_text)
        else -> stringResource(R.string.message_placeholder_imessage)
    }
}
