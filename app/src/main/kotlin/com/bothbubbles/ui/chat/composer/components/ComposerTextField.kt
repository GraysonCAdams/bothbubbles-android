package com.bothbubbles.ui.chat.composer.components

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.content.res.ResourcesCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bothbubbles.R
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.composer.MentionSpan
import com.bothbubbles.ui.chat.composer.animations.ComposerMotionTokens
import com.bothbubbles.ui.theme.BothBubblesTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import timber.log.Timber

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
    val density = LocalDensity.current

    // Track line count to force height updates when text wraps
    var lineCount by remember { mutableIntStateOf(1) }

    // Placeholder text based on send mode and enabled state
    val placeholderText = when {
        !isEnabled -> "Not default SMS app"
        sendMode == ChatSendMode.SMS -> stringResource(R.string.message_placeholder_text)
        else -> stringResource(R.string.message_placeholder_imessage)
    }

    // Colors for the EditText
    val textColor = inputColors.inputText.toArgb()
    val hintColor = inputColors.inputPlaceholder.toArgb()
    val mentionColor = MaterialTheme.colorScheme.primary.toArgb()
    val cursorColor = MaterialTheme.colorScheme.primary.toArgb()

    // Calculate minimum height based on line count
    // Line height ~24dp, base height 40dp for single line
    val calculatedMinHeight = (40 + (lineCount - 1) * 24).dp

    // Keep reference to EditText for external updates
    var editTextRef by remember { mutableStateOf<EditText?>(null) }

    // Handle focus requests
    LaunchedEffect(shouldRequestFocus) {
        if (shouldRequestFocus) {
            editTextRef?.requestFocus()
            onFocusRequested()
        }
    }

    // Sync external text changes (e.g., when mention is inserted, draft restored)
    // This is the ONLY place that syncs external text to the EditText to avoid race conditions.
    LaunchedEffect(text) {
        editTextRef?.let { editText ->
            val currentText = editText.text.toString()

            // IMPORTANT: Only sync if the external text is genuinely different AND
            // not a substring of what the user has typed (which would indicate we're
            // behind due to rapid typing). This prevents losing characters.
            val needsSync = currentText != text && !currentText.startsWith(text)

            if (needsSync) {
                Timber.d("ComposerTextField: syncing external text change, current='$currentText', new='$text'")
                // Capture cursor info BEFORE modifying text
                val cursorWasAtEnd = editText.selectionEnd >= editText.text.length
                val savedCursorPos = editText.selectionStart

                editText.setText(applyMentionSpans(text, mentions, mentionColor))

                // Restore cursor position intelligently:
                // - If cursor was at end, keep it at end
                // - Otherwise, try to preserve the saved position (clamped to new length)
                val newCursorPos = when {
                    cursorWasAtEnd -> text.length
                    savedCursorPos <= text.length -> savedCursorPos
                    else -> text.length
                }
                editText.setSelection(newCursorPos)
            }
        }
    }

    // Update mention styling when mentions change
    LaunchedEffect(mentions) {
        editTextRef?.let { editText ->
            val currentText = editText.text.toString()
            if (currentText == text) {
                val cursorPos = editText.selectionStart
                editText.setText(applyMentionSpans(currentText, mentions, mentionColor))
                editText.setSelection(minOf(cursorPos, currentText.length))
            }
        }
    }

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

            // Native EditText with spell checking support
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (leadingContent != null) 36.dp else 12.dp,
                        end = if (trailingContent != null) 72.dp else 12.dp,
                        top = 8.dp,
                        bottom = 8.dp
                    ),
                factory = { context ->
                    createSpellCheckEditText(
                        context = context,
                        density = density.density,
                        textColor = textColor,
                        hintColor = hintColor,
                        cursorColor = cursorColor,
                        hint = placeholderText,
                        initialText = text,
                        mentions = mentions,
                        mentionColor = mentionColor,
                        isEnabled = isEnabled,
                        onTextChange = onTextChange,
                        onTextChangedWithCursor = onTextChangedWithCursor,
                        onFocusChanged = onFocusChanged,
                        onLineCountChanged = { newCount ->
                            val clampedCount = newCount.coerceIn(1, 4)
                            if (clampedCount != lineCount) {
                                lineCount = clampedCount
                            }
                        }
                    ).also { editTextRef = it }
                },
                update = { editText ->
                    // Update enabled state
                    editText.isEnabled = isEnabled
                    editText.alpha = if (isEnabled) 1f else 0.5f

                    // Update hint text
                    editText.hint = placeholderText

                    // Update colors
                    editText.setTextColor(textColor)
                    editText.setHintTextColor(if (isEnabled) hintColor else (hintColor and 0x00FFFFFF) or 0x80000000.toInt())

                    // NOTE: Text sync is handled ONLY by LaunchedEffect(text) to avoid
                    // race conditions during fast typing. Having two sync points causes
                    // characters to be lost when typing quickly during line wraps.
                    // See LaunchedEffect(text) block above for the single sync point.
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
 * Creates a native EditText with spell checking enabled.
 */
private fun createSpellCheckEditText(
    context: Context,
    density: Float,
    textColor: Int,
    hintColor: Int,
    cursorColor: Int,
    hint: String,
    initialText: String,
    mentions: ImmutableList<MentionSpan>,
    mentionColor: Int,
    isEnabled: Boolean,
    onTextChange: (String) -> Unit,
    onTextChangedWithCursor: ((String, Int) -> Unit)?,
    onFocusChanged: (Boolean) -> Unit,
    onLineCountChanged: (Int) -> Unit
): EditText {
    return EditText(context).apply {
        // Layout params
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Text appearance - match Material bodyLarge (16sp)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(textColor)
        setHintTextColor(hintColor)
        setHint(hint)

        // Apply Google Sans Flex font to match app typography
        ResourcesCompat.getFont(context, R.font.googlesansflex_regular)?.let {
            typeface = it
        }

        // Remove default EditText styling
        background = null
        setPadding(0, 0, 0, 0)

        // Enable spell checking and autocorrect with sentence capitalization
        inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE

        // Don't let IME take over with action button - we handle send ourselves
        imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION

        // Max 4 lines, expandable
        minLines = 1
        maxLines = 4
        setHorizontallyScrolling(false)

        // Gravity for vertical centering when single line
        gravity = Gravity.CENTER_VERTICAL or Gravity.START

        // Set initial text with mention styling
        setText(applyMentionSpans(initialText, mentions, mentionColor))
        setSelection(initialText.length)

        // Enabled state
        this.isEnabled = isEnabled
        alpha = if (isEnabled) 1f else 0.5f

        // Text change listener
        addTextChangedListener(object : TextWatcher {
            private var isUpdating = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                isUpdating = true

                val newText = s?.toString() ?: ""
                onTextChange(newText)
                onTextChangedWithCursor?.invoke(newText, selectionStart)

                // Track line count for height expansion
                post {
                    onLineCountChanged(lineCount)
                }

                isUpdating = false
            }
        })

        // Focus listener
        setOnFocusChangeListener { _, hasFocus ->
            onFocusChanged(hasFocus)
        }

        // Try to set cursor color via reflection (works on most devices)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                textCursorDrawable?.setTint(cursorColor)
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not set cursor color")
        }
    }
}

/**
 * Applies mention styling to text using SpannableStringBuilder.
 */
private fun applyMentionSpans(
    text: String,
    mentions: ImmutableList<MentionSpan>,
    mentionColor: Int
): SpannableStringBuilder {
    val spannable = SpannableStringBuilder(text)

    mentions.forEach { mention ->
        if (mention.startIndex >= 0 && mention.endIndex <= text.length) {
            // Apply color
            spannable.setSpan(
                ForegroundColorSpan(mentionColor),
                mention.startIndex,
                mention.endIndex,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            // Apply bold style
            spannable.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                mention.startIndex,
                mention.endIndex,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    return spannable
}

