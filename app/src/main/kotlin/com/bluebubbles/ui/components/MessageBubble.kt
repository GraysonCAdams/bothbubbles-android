package com.bluebubbles.ui.components

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bluebubbles.data.local.db.entity.MessageSource
import com.bluebubbles.ui.theme.BlueBubblesTheme
import com.bluebubbles.ui.theme.MessageShapes
import java.util.Calendar

/**
 * UI model for a message bubble
 */
data class MessageUiModel(
    val guid: String,
    val text: String?,
    val dateCreated: Long,
    val formattedTime: String,
    val isFromMe: Boolean,
    val isSent: Boolean,
    val isDelivered: Boolean,
    val isRead: Boolean,
    val hasError: Boolean,
    val isReaction: Boolean,
    val attachments: List<AttachmentUiModel>,
    val senderName: String?,
    val messageSource: String,
    val reactions: List<ReactionUiModel> = emptyList(),
    val myReactions: Set<Tapback> = emptySet()
)

data class AttachmentUiModel(
    val guid: String,
    val mimeType: String?,
    val localPath: String?,
    val webUrl: String?,
    val width: Int?,
    val height: Int?
)

@Composable
fun MessageBubble(
    message: MessageUiModel,
    onLongPress: () -> Unit,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    searchQuery: String? = null,
    isCurrentSearchMatch: Boolean = false
) {
    val bubbleColors = BlueBubblesTheme.bubbleColors
    val isIMessage = message.messageSource == MessageSource.IMESSAGE.name
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current

    // Detect dates in message text
    val detectedDates = remember(message.text) {
        if (message.text.isNullOrBlank()) emptyList()
        else DateParsingUtils.detectDates(message.text)
    }
    val hasCalendarDates = detectedDates.isNotEmpty()

    // Get first detected date for the calendar button
    val firstDetectedDate = detectedDates.firstOrNull()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromMe) {
            Arrangement.End
        } else {
            Arrangement.Start
        },
        verticalAlignment = Alignment.Top
    ) {
        // Calendar button on left side for received messages
        if (!message.isFromMe && hasCalendarDates) {
            CalendarEventButton(
                onClick = {
                    firstDetectedDate?.let { date ->
                        openCalendarIntent(context, date, message.text ?: "")
                    }
                },
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        Column(
            horizontalAlignment = if (message.isFromMe) {
                Alignment.End
            } else {
                Alignment.Start
            },
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            // Sender name for group chats
            if (!message.isFromMe && message.senderName != null) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
                )
            }

            // Message bubble with floating reactions overlay
            Box {
                // Message bubble with long-press gesture
                val bubbleShape = if (message.isFromMe) {
                    MessageShapes.sentWithTail
                } else {
                    MessageShapes.receivedWithTail
                }

                Surface(
                    shape = bubbleShape,
                    color = when {
                        message.isFromMe && isIMessage -> bubbleColors.iMessageSent
                        message.isFromMe -> bubbleColors.smsSent
                        else -> bubbleColors.received
                    },
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .then(
                            if (isCurrentSearchMatch) {
                                Modifier.border(
                                    width = 2.dp,
                                    color = Color(0xFFFF9800), // Orange highlight for current match
                                    shape = bubbleShape
                                )
                            } else {
                                Modifier
                            }
                        )
                        .pointerInput(message.guid) {
                            detectTapGestures(
                                onLongPress = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onLongPress()
                                }
                            )
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 12.dp
                        )
                    ) {
                        // Attachments
                        message.attachments.forEach { attachment ->
                            // TODO: Render attachments
                        }

                        // Text content with clickable dates and search highlighting
                        if (!message.text.isNullOrBlank()) {
                            val textColor = when {
                                message.isFromMe && isIMessage -> bubbleColors.iMessageSentText
                                message.isFromMe -> bubbleColors.smsSentText
                                else -> bubbleColors.receivedText
                            }

                            // Build annotated string with search highlighting
                            val annotatedText = if (!searchQuery.isNullOrBlank() && message.text.contains(searchQuery, ignoreCase = true)) {
                                buildSearchHighlightedText(
                                    text = message.text,
                                    searchQuery = searchQuery,
                                    textColor = textColor,
                                    detectedDates = detectedDates
                                )
                            } else if (detectedDates.isNotEmpty()) {
                                buildAnnotatedStringWithDates(
                                    text = message.text,
                                    detectedDates = detectedDates,
                                    textColor = textColor
                                )
                            } else {
                                null
                            }

                            if (annotatedText != null) {
                                ClickableText(
                                    text = annotatedText,
                                    style = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                                    onClick = { offset ->
                                        annotatedText.getStringAnnotations(
                                            tag = "DATE",
                                            start = offset,
                                            end = offset
                                        ).firstOrNull()?.let { annotation ->
                                            val dateIndex = annotation.item.toIntOrNull()
                                            if (dateIndex != null && dateIndex < detectedDates.size) {
                                                openCalendarIntent(
                                                    context,
                                                    detectedDates[dateIndex],
                                                    message.text
                                                )
                                            }
                                        }
                                    }
                                )
                            } else {
                                Text(
                                    text = message.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = textColor
                                )
                            }
                        }
                    }
                }

                // Display reactions floating at bottom corner outside the bubble
                // For outbound messages: bottom-left; for received: bottom-right
                if (message.reactions.isNotEmpty()) {
                    ReactionsDisplay(
                        reactions = message.reactions,
                        isFromMe = message.isFromMe,
                        modifier = Modifier
                            .align(if (message.isFromMe) Alignment.BottomStart else Alignment.BottomEnd)
                            .offset(
                                x = if (message.isFromMe) (-16).dp else 16.dp,
                                y = 18.dp
                            )
                    )
                }
            }

            // Timestamp, message type, and status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = message.formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Show message type indicator (SMS/MMS)
                if (message.messageSource == MessageSource.LOCAL_SMS.name ||
                    message.messageSource == MessageSource.LOCAL_MMS.name
                ) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (message.messageSource == MessageSource.LOCAL_MMS.name) "MMS" else "SMS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (message.isFromMe) {
                    Spacer(modifier = Modifier.width(4.dp))
                    DeliveryIndicator(
                        isSent = message.isSent,
                        isDelivered = message.isDelivered,
                        isRead = message.isRead,
                        hasError = message.hasError
                    )
                }
            }
        }

        // Calendar button on right side for sent messages
        if (message.isFromMe && hasCalendarDates) {
            Spacer(modifier = Modifier.width(4.dp))
            CalendarEventButton(
                onClick = {
                    firstDetectedDate?.let { date ->
                        openCalendarIntent(context, date, message.text ?: "")
                    }
                },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * Builds an AnnotatedString with underlined clickable dates
 */
@Composable
private fun buildAnnotatedStringWithDates(
    text: String,
    detectedDates: List<DetectedDate>,
    textColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        var lastIndex = 0

        detectedDates.forEachIndexed { index, date ->
            // Add text before the date
            if (date.startIndex > lastIndex) {
                append(text.substring(lastIndex, date.startIndex))
            }

            // Add the date with underline style and annotation
            pushStringAnnotation(tag = "DATE", annotation = index.toString())
            withStyle(
                SpanStyle(
                    textDecoration = TextDecoration.Underline,
                    color = textColor
                )
            ) {
                append(date.matchedText)
            }
            pop()

            lastIndex = date.endIndex
        }

        // Add remaining text after last date
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}

/**
 * Builds an AnnotatedString with search query matches highlighted
 */
@Composable
private fun buildSearchHighlightedText(
    text: String,
    searchQuery: String,
    textColor: Color,
    detectedDates: List<DetectedDate> = emptyList()
): AnnotatedString {
    val highlightColor = Color(0xFFFFEB3B) // Yellow highlight
    val highlightTextColor = Color.Black

    return buildAnnotatedString {
        var currentIndex = 0
        val lowerText = text.lowercase()
        val lowerQuery = searchQuery.lowercase()

        while (currentIndex < text.length) {
            val matchIndex = lowerText.indexOf(lowerQuery, currentIndex)

            if (matchIndex == -1) {
                // No more matches, append remaining text
                append(text.substring(currentIndex))
                break
            }

            // Append text before the match
            if (matchIndex > currentIndex) {
                append(text.substring(currentIndex, matchIndex))
            }

            // Append the highlighted match
            withStyle(
                SpanStyle(
                    background = highlightColor,
                    color = highlightTextColor
                )
            ) {
                append(text.substring(matchIndex, matchIndex + searchQuery.length))
            }

            currentIndex = matchIndex + searchQuery.length
        }
    }
}

/**
 * Calendar event button that appears beside messages with dates
 */
@Composable
private fun CalendarEventButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(36.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = Icons.Outlined.Event,
                contentDescription = "Add to calendar",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Opens the calendar app to add a new event
 */
private fun openCalendarIntent(
    context: android.content.Context,
    detectedDate: DetectedDate,
    messageText: String
) {
    val calendar = detectedDate.parsedDate

    // Set default event duration (1 hour if time is specified, all-day otherwise)
    val startMillis = calendar.timeInMillis
    val endMillis = if (detectedDate.hasTime) {
        startMillis + 60 * 60 * 1000 // 1 hour
    } else {
        // For all-day events, end at the same day
        Calendar.getInstance().apply {
            timeInMillis = startMillis
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
    }

    // Try to extract event title from message (text before "starts" or use full text)
    val eventTitle = extractEventTitle(messageText)

    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
        putExtra(CalendarContract.Events.TITLE, eventTitle)
        putExtra(CalendarContract.Events.DESCRIPTION, messageText)
        if (!detectedDate.hasTime) {
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        }
    }

    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // No calendar app found - could show a toast here
    }
}

/**
 * Attempts to extract a meaningful event title from the message text
 */
private fun extractEventTitle(messageText: String): String {
    // Common patterns like "New calendar event: Event Name starts..."
    val patterns = listOf(
        Regex("(?:New calendar event:|Event:)\\s*(.+?)\\s+starts", RegexOption.IGNORE_CASE),
        Regex("(.+?)\\s+(?:starts|begins|on|at)\\s+(?:January|February|March|April|May|June|July|August|September|October|November|December)", RegexOption.IGNORE_CASE),
        Regex("(.+?)\\s+(?:starts|begins|on|at)\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)", RegexOption.IGNORE_CASE)
    )

    for (pattern in patterns) {
        val match = pattern.find(messageText)
        if (match != null && match.groupValues.size > 1) {
            val title = match.groupValues[1].trim()
            if (title.isNotBlank() && title.length <= 100) {
                return title
            }
        }
    }

    // Fallback: use first 50 chars of message
    return if (messageText.length > 50) {
        messageText.take(50) + "..."
    } else {
        messageText
    }
}

@Composable
private fun DeliveryIndicator(
    isSent: Boolean,
    isDelivered: Boolean,
    isRead: Boolean,
    hasError: Boolean
) {
    val (icon, color) = when {
        hasError -> Icons.Default.Error to MaterialTheme.colorScheme.error
        isRead -> Icons.Default.DoneAll to Color(0xFF34B7F1)
        isDelivered -> Icons.Default.DoneAll to MaterialTheme.colorScheme.onSurfaceVariant
        isSent -> Icons.Default.Check to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Icon(
        imageVector = icon,
        contentDescription = when {
            hasError -> "Failed"
            isRead -> "Read"
            isDelivered -> "Delivered"
            isSent -> "Sent"
            else -> "Sending"
        },
        tint = color,
        modifier = Modifier.size(14.dp)
    )
}

/**
 * Typing indicator bubble with animated dots
 */
@Composable
fun TypingIndicator(
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.padding(end = 48.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(3) { index ->
                TypingDot(
                    delay = index * 150,
                    modifier = Modifier.size(8.dp)
                )
            }
        }
    }
}

@Composable
private fun TypingDot(
    delay: Int,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delay.toLong())
        while (true) {
            visible = !visible
            kotlinx.coroutines.delay(500)
        }
    }

    val alpha by animateColorAsState(
        targetValue = if (visible)
            MaterialTheme.colorScheme.onSurfaceVariant
        else
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "dotAlpha"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(alpha)
    )
}

/**
 * Date separator between message groups
 */
@Composable
fun DateSeparator(
    date: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = date,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * New messages indicator floating button
 */
@Composable
fun NewMessagesIndicator(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (count > 0) {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 4.dp,
            modifier = modifier
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (count == 1) "1 new message" else "$count new messages",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/**
 * Message type chip indicator (SMS/MMS/iMessage)
 */
@Composable
fun MessageTypeChip(
    messageSource: String,
    modifier: Modifier = Modifier
) {
    val (label, color) = when (messageSource) {
        MessageSource.LOCAL_SMS.name -> "SMS" to Color(0xFF34C759)
        MessageSource.LOCAL_MMS.name -> "MMS" to Color(0xFF34C759)
        else -> "iMessage" to MaterialTheme.colorScheme.primary
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
