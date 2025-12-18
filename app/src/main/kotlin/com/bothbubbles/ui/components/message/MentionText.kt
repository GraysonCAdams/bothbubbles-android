package com.bothbubbles.ui.components.message

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Annotation tag for mention links.
 */
private const val MENTION_TAG = "MENTION"

/**
 * Renders message text with clickable mentions.
 *
 * Mentions are styled with the primary color and are clickable to navigate
 * to the mentioned person's contact details.
 *
 * @param text The message text
 * @param mentions List of mentions in the text
 * @param onMentionClick Callback when a mention is clicked (provides the mentioned address)
 * @param textColor Base text color
 * @param mentionColor Color for mention text (defaults to primary)
 * @param style Text style to use
 * @param modifier Modifier for the text
 */
@Composable
fun MentionText(
    text: String,
    mentions: List<MentionUiModel>,
    onMentionClick: (String) -> Unit,
    textColor: Color,
    mentionColor: Color = MaterialTheme.colorScheme.primary,
    style: TextStyle = LocalTextStyle.current,
    modifier: Modifier = Modifier
) {
    // Build annotated string with mention styling
    val annotatedText = remember(text, mentions, textColor, mentionColor) {
        buildMentionAnnotatedString(text, mentions, textColor, mentionColor)
    }

    if (mentions.isEmpty()) {
        // No mentions - use regular Text for better performance
        androidx.compose.material3.Text(
            text = text,
            color = textColor,
            style = style,
            modifier = modifier
        )
    } else {
        // Has mentions - use ClickableText
        ClickableText(
            text = annotatedText,
            style = style.copy(color = textColor),
            onClick = { offset ->
                annotatedText
                    .getStringAnnotations(MENTION_TAG, offset, offset)
                    .firstOrNull()
                    ?.let { annotation ->
                        onMentionClick(annotation.item)
                    }
            },
            modifier = modifier
        )
    }
}

/**
 * Build an AnnotatedString with mention spans styled and annotated.
 */
private fun buildMentionAnnotatedString(
    text: String,
    mentions: List<MentionUiModel>,
    textColor: Color,
    mentionColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        // Start with base text style
        withStyle(SpanStyle(color = textColor)) {
            append(text)
        }

        // Add mention styling and annotations
        mentions.forEach { mention ->
            if (mention.startIndex >= 0 && mention.endIndex <= text.length) {
                // Apply styling
                addStyle(
                    style = SpanStyle(
                        color = mentionColor,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline
                    ),
                    start = mention.startIndex,
                    end = mention.endIndex
                )

                // Add clickable annotation
                addStringAnnotation(
                    tag = MENTION_TAG,
                    annotation = mention.mentionedAddress,
                    start = mention.startIndex,
                    end = mention.endIndex
                )
            }
        }
    }
}

/**
 * Extension to check if a message has any mentions.
 */
val MessageUiModel.hasMentions: Boolean
    get() = mentions.isNotEmpty()
