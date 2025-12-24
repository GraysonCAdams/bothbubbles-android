package com.bothbubbles.ui.chat.composer.components

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.content.res.ResourcesCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bothbubbles.R
import com.bothbubbles.ui.chat.composer.MentionSpan
import com.bothbubbles.ui.theme.BothBubblesTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import timber.log.Timber

/**
 * Text segment editor for the segmented composer.
 *
 * A lightweight native EditText wrapper optimized for use within a list of segments.
 * Unlike the full ComposerTextField, this has no containing Surface/shape - just the text.
 *
 * Features:
 * - Native spell checking and autocorrect
 * - Voice dictation support
 * - Mention span highlighting
 * - Backspace-at-start detection for segment deletion
 * - Focus management for navigation between segments
 * - Cursor position tracking for embed insertion
 *
 * @param segmentId Unique ID for this segment (for focus management)
 * @param text Current text content
 * @param mentions Mention spans to highlight
 * @param placeholder Placeholder text when empty
 * @param onTextChange Callback when text changes
 * @param onTextChangeWithCursor Callback with text and cursor position
 * @param onBackspaceAtStart Callback when backspace pressed at position 0 (delete previous segment)
 * @param onFocusChange Callback when focus changes
 * @param requestFocus When true, request focus on this segment
 * @param onFocusRequestHandled Callback after focus request is processed
 * @param isEnabled Whether editing is enabled
 * @param modifier Modifier for the editor
 */
@Composable
fun TextSegmentEditor(
    segmentId: String,
    text: String,
    mentions: ImmutableList<MentionSpan> = persistentListOf(),
    placeholder: String = "",
    onTextChange: (String) -> Unit,
    onTextChangeWithCursor: ((String, Int) -> Unit)? = null,
    onBackspaceAtStart: () -> Unit = {},
    onFocusChange: (Boolean) -> Unit = {},
    requestFocus: Boolean = false,
    onFocusRequestHandled: () -> Unit = {},
    isEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val inputColors = BothBubblesTheme.bubbleColors
    val density = LocalDensity.current

    // Track line count for height calculation
    var lineCount by remember { mutableIntStateOf(1) }

    // Colors
    val textColor = inputColors.inputText.toArgb()
    val hintColor = inputColors.inputPlaceholder.toArgb()
    val mentionColor = MaterialTheme.colorScheme.primary.toArgb()
    val cursorColor = MaterialTheme.colorScheme.primary.toArgb()

    // Calculate min height: base 24dp + extra lines
    val minHeight = (24 + (lineCount - 1) * 20).dp

    // Keep reference to EditText for external control
    var editTextRef by remember { mutableStateOf<EditText?>(null) }

    // Handle focus requests
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            editTextRef?.requestFocus()
            // Move cursor to end
            editTextRef?.let { et ->
                et.setSelection(et.text.length)
            }
            onFocusRequestHandled()
        }
    }

    // Sync external text changes
    LaunchedEffect(text, mentions) {
        editTextRef?.let { editText ->
            val currentText = editText.text.toString()
            if (currentText != text) {
                val cursorPos = editText.selectionStart.coerceIn(0, text.length)
                editText.setText(applyMentionSpans(text, mentions, mentionColor))
                editText.setSelection(cursorPos)
            } else if (mentions.isNotEmpty()) {
                // Update mention styling only
                val cursorPos = editText.selectionStart
                editText.setText(applyMentionSpans(text, mentions, mentionColor))
                editText.setSelection(cursorPos.coerceIn(0, text.length))
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                createSegmentEditText(
                    context = context,
                    textColor = textColor,
                    hintColor = hintColor,
                    cursorColor = cursorColor,
                    hint = placeholder,
                    initialText = text,
                    mentions = mentions,
                    mentionColor = mentionColor,
                    isEnabled = isEnabled,
                    onTextChange = onTextChange,
                    onTextChangeWithCursor = onTextChangeWithCursor,
                    onBackspaceAtStart = onBackspaceAtStart,
                    onFocusChange = onFocusChange,
                    onLineCountChanged = { newCount ->
                        lineCount = newCount.coerceIn(1, 8) // Allow more lines than main composer
                    }
                ).also { editTextRef = it }
            },
            update = { editText ->
                editText.isEnabled = isEnabled
                editText.alpha = if (isEnabled) 1f else 0.5f
                editText.hint = placeholder
                editText.setTextColor(textColor)
                editText.setHintTextColor(hintColor)

                // Sync text if changed externally
                val currentText = editText.text.toString()
                if (currentText != text) {
                    val cursorPos = editText.selectionStart.coerceIn(0, text.length)
                    editText.setText(applyMentionSpans(text, mentions, mentionColor))
                    editText.setSelection(cursorPos)
                }
            }
        )
    }
}

/**
 * Creates a native EditText for text segment editing.
 */
private fun createSegmentEditText(
    context: Context,
    textColor: Int,
    hintColor: Int,
    cursorColor: Int,
    hint: String,
    initialText: String,
    mentions: ImmutableList<MentionSpan>,
    mentionColor: Int,
    isEnabled: Boolean,
    onTextChange: (String) -> Unit,
    onTextChangeWithCursor: ((String, Int) -> Unit)?,
    onBackspaceAtStart: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onLineCountChanged: (Int) -> Unit
): EditText {
    return EditText(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Text appearance - match bodyLarge (16sp)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(textColor)
        setHintTextColor(hintColor)
        setHint(hint)

        // Apply app font
        ResourcesCompat.getFont(context, R.font.googlesansflex_regular)?.let {
            typeface = it
        }

        // Remove default styling - this is inline in a list
        background = null
        setPadding(0, 0, 0, 0)

        // Enable spell checking and autocorrect
        inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE

        // Allow multiline, no IME action button
        imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION

        // No max lines for segment editors (content can grow)
        minLines = 1
        maxLines = Int.MAX_VALUE
        setHorizontallyScrolling(false)

        gravity = Gravity.TOP or Gravity.START

        // Set initial text with mentions
        setText(applyMentionSpans(initialText, mentions, mentionColor))
        setSelection(initialText.length)

        this.isEnabled = isEnabled
        alpha = if (isEnabled) 1f else 0.5f

        // Backspace-at-start detection
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DEL &&
                event.action == KeyEvent.ACTION_DOWN &&
                selectionStart == 0 &&
                selectionEnd == 0 &&
                text.isEmpty()
            ) {
                onBackspaceAtStart()
                true
            } else {
                false
            }
        }

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
                onTextChangeWithCursor?.invoke(newText, selectionStart)

                // Track line count
                post {
                    onLineCountChanged(lineCount)
                }

                isUpdating = false
            }
        })

        // Focus listener
        setOnFocusChangeListener { _, hasFocus ->
            onFocusChange(hasFocus)
        }

        // Set cursor color
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
            spannable.setSpan(
                ForegroundColorSpan(mentionColor),
                mention.startIndex,
                mention.endIndex,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
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
