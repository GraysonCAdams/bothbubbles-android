package com.bothbubbles.ui.chat.composer.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bothbubbles.ui.chat.composer.MessagePreview
import com.bothbubbles.ui.chat.composer.animations.ComposerMotionTokens
import com.bothbubbles.ui.theme.BothBubblesTheme
import java.io.File

/**
 * Reply preview bar for the chat composer.
 *
 * Displays a quote preview of the message being replied to with:
 * - Colored accent bar on the left (uses primary color)
 * - Sender name or "yourself" if replying to own message
 * - Message text preview (truncated to single line)
 * - Attachment indicator if the message has attachments
 * - Dismiss button to cancel the reply
 * - Smooth slide-down animation when appearing/disappearing
 *
 * Layout:
 * ```
 * ┌──────────────────────────────────────────────────────────────┐
 * │ █ Replying to John                                       [X] │
 * │ █ Hey, did you see the game last night?                      │
 * └──────────────────────────────────────────────────────────────┘
 * ```
 *
 * @param replyTo The message preview being replied to, or null to hide
 * @param onDismiss Callback when the dismiss button is tapped
 * @param modifier Modifier for the bar container
 */
@Composable
fun ReplyPreviewBar(
    replyTo: MessagePreview?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = replyTo != null,
        enter = fadeIn(tween(ComposerMotionTokens.Duration.NORMAL)) + expandVertically(
            expandFrom = Alignment.Top,
            animationSpec = spring(
                dampingRatio = ComposerMotionTokens.Spring.Responsive.dampingRatio,
                stiffness = ComposerMotionTokens.Spring.Responsive.stiffness
            )
        ),
        exit = fadeOut(tween(ComposerMotionTokens.Duration.FAST)) + shrinkVertically(
            shrinkTowards = Alignment.Top,
            animationSpec = tween(ComposerMotionTokens.Duration.NORMAL)
        ),
        modifier = modifier
    ) {
        replyTo?.let { message ->
            ReplyPreviewContent(
                message = message,
                onDismiss = onDismiss
            )
        }
    }
}

/**
 * Content of the reply preview bar.
 */
@Composable
private fun ReplyPreviewContent(
    message: MessagePreview,
    onDismiss: () -> Unit
) {
    val inputColors = BothBubblesTheme.bubbleColors

    // Build accessibility description
    val senderDescription = if (message.isFromMe) "yourself" else (message.senderName ?: "someone")
    val contentDescription = buildString {
        append("Replying to $senderDescription")
        message.text?.let { append(": $it") }
        if (message.hasAttachments) append(". Contains attachment")
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = contentDescription },
        color = inputColors.inputBackground
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Accent bar on the left
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Thumbnail preview for images/videos
            if (message.thumbnailUri != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = File(message.thumbnailUri),
                        contentDescription = "Attachment preview",
                        modifier = Modifier.size(40.dp),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Reply content
            Column(modifier = Modifier.weight(1f)) {
                // "Replying to..." label
                Text(
                    text = buildReplyLabel(message),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                // Message preview
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    // Attachment icon if no thumbnail and has attachments with no text
                    if (message.thumbnailUri == null && message.hasAttachments && message.text.isNullOrBlank()) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = null,
                            modifier = Modifier
                                .size(14.dp)
                                .padding(end = 4.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = message.text ?: if (message.hasAttachments) "Attachment" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Dismiss button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel reply",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Build the "Replying to..." label text.
 */
private fun buildReplyLabel(message: MessagePreview): String {
    return if (message.isFromMe) {
        "Replying to yourself"
    } else {
        "Replying to ${message.senderName ?: "message"}"
    }
}
