package com.bluebubbles.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.bluebubbles.data.local.db.entity.MessageSource
import com.bluebubbles.ui.theme.BlueBubblesTheme
import com.bluebubbles.ui.theme.MessageShapes
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Position of a message within a consecutive group from the same sender.
 * Used to determine bubble shape and spacing for visual grouping.
 */
enum class MessageGroupPosition {
    /** Standalone message - not grouped with adjacent messages */
    SINGLE,
    /** First message in a group - rounded top, tight bottom corners */
    FIRST,
    /** Middle message in a group - tight corners on both ends */
    MIDDLE,
    /** Last message in a group - tight top corners, tail at bottom */
    LAST
}

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
    val myReactions: Set<Tapback> = emptySet(),
    val expressiveSendStyleId: String? = null,
    val effectPlayed: Boolean = false
)

data class AttachmentUiModel(
    val guid: String,
    val mimeType: String?,
    val localPath: String?,
    val webUrl: String?,
    val width: Int?,
    val height: Int?,
    val transferName: String? = null,
    val totalBytes: Long? = null,
    val isSticker: Boolean = false
) {
    val isImage: Boolean
        get() = mimeType?.startsWith("image/") == true

    val isVideo: Boolean
        get() = mimeType?.startsWith("video/") == true

    val isAudio: Boolean
        get() = mimeType?.startsWith("audio/") == true

    val friendlySize: String
        get() = when {
            totalBytes == null -> ""
            totalBytes < 1024 -> "$totalBytes B"
            totalBytes < 1024 * 1024 -> "${totalBytes / 1024} KB"
            totalBytes < 1024 * 1024 * 1024 -> "${totalBytes / (1024 * 1024)} MB"
            else -> "${totalBytes / (1024 * 1024 * 1024)} GB"
        }

    val fileExtension: String?
        get() = transferName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
}

@Composable
fun MessageBubble(
    message: MessageUiModel,
    onLongPress: () -> Unit,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    groupPosition: MessageGroupPosition = MessageGroupPosition.SINGLE,
    searchQuery: String? = null,
    isCurrentSearchMatch: Boolean = false
) {
    val bubbleColors = BlueBubblesTheme.bubbleColors
    val isIMessage = message.messageSource == MessageSource.IMESSAGE.name
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // Swipe-to-reveal timestamp state
    val dragOffset = remember { Animatable(0f) }
    val maxDragPx = with(density) { 80.dp.toPx() }

    // Tap-to-show timestamp state (default hidden)
    var showTimestamp by remember { mutableStateOf(false) }

    // Detect dates in message text for underlining
    val detectedDates = remember(message.text) {
        if (message.text.isNullOrBlank()) emptyList()
        else DateParsingUtils.detectDates(message.text)
    }

    // Detect phone numbers in message text for underlining
    val detectedPhoneNumbers = remember(message.text) {
        if (message.text.isNullOrBlank()) emptyList()
        else PhoneAndCodeParsingUtils.detectPhoneNumbers(message.text)
    }

    // Detect verification codes in message text for underlining
    val detectedCodes = remember(message.text) {
        if (message.text.isNullOrBlank()) emptyList()
        else PhoneAndCodeParsingUtils.detectCodes(message.text)
    }

    // Detect first URL in message text for link preview
    val firstUrl = remember(message.text) {
        UrlParsingUtils.getFirstUrl(message.text)
    }

    // Phone number context menu state
    var showPhoneMenu by remember { mutableStateOf(false) }
    var selectedPhoneNumber by remember { mutableStateOf<DetectedPhoneNumber?>(null) }
    var phoneMenuOffset by remember { mutableStateOf(DpOffset.Zero) }

    // Determine message type label for swipe reveal
    val messageTypeLabel = when (message.messageSource) {
        MessageSource.LOCAL_SMS.name -> "SMS"
        MessageSource.LOCAL_MMS.name -> "MMS"
        MessageSource.SERVER_SMS.name -> "SMS"
        else -> "iMessage"
    }

    // Swipe-to-reveal timestamp container
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        // Sliding timestamp from the edge (same side as bubble)
        val timestampAlpha = (dragOffset.value.absoluteValue / maxDragPx).coerceIn(0f, 1f)

        // Timestamp positioned at the edge - slides in from outside screen edge
        if (message.isFromMe) {
            // Right-side timestamp for sent messages (slides in from right)
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (80 - (dragOffset.value.absoluteValue / maxDragPx * 80)).dp)
                    .alpha(timestampAlpha)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = message.formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = messageTypeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        } else {
            // Left-side timestamp for received messages (slides in from left)
            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-80 + (dragOffset.value.absoluteValue / maxDragPx * 80)).dp)
                    .alpha(timestampAlpha)
                    .padding(start = 8.dp)
            ) {
                Text(
                    text = message.formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = messageTypeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(dragOffset.value.roundToInt(), 0) },
        horizontalArrangement = if (message.isFromMe) {
            Arrangement.End
        } else {
            Arrangement.Start
        },
        verticalAlignment = Alignment.Top
    ) {
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
                // Select shape based on group position for visual grouping
                val bubbleShape = when (groupPosition) {
                    MessageGroupPosition.SINGLE -> if (message.isFromMe) {
                        MessageShapes.sentSingle
                    } else {
                        MessageShapes.receivedSingle
                    }
                    MessageGroupPosition.FIRST -> if (message.isFromMe) {
                        MessageShapes.sentFirst
                    } else {
                        MessageShapes.receivedFirst
                    }
                    MessageGroupPosition.MIDDLE -> if (message.isFromMe) {
                        MessageShapes.sentMiddle
                    } else {
                        MessageShapes.receivedMiddle
                    }
                    MessageGroupPosition.LAST -> if (message.isFromMe) {
                        MessageShapes.sentLast
                    } else {
                        MessageShapes.receivedLast
                    }
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
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    // Animate back to original position
                                    coroutineScope.launch {
                                        dragOffset.animateTo(
                                            0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            )
                                        )
                                    }
                                },
                                onDragCancel = {
                                    coroutineScope.launch {
                                        dragOffset.animateTo(0f)
                                    }
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    coroutineScope.launch {
                                        // For sent messages (right side): allow drag left (negative)
                                        // For received messages (left side): allow drag right (positive)
                                        val newOffset = if (message.isFromMe) {
                                            (dragOffset.value + dragAmount).coerceIn(-maxDragPx, 0f)
                                        } else {
                                            (dragOffset.value + dragAmount).coerceIn(0f, maxDragPx)
                                        }
                                        dragOffset.snapTo(newOffset)
                                    }
                                }
                            )
                        }
                        .pointerInput(message.guid) {
                            detectTapGestures(
                                onTap = {
                                    showTimestamp = !showTimestamp
                                },
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
                        if (message.attachments.isNotEmpty()) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(bottom = if (message.text.isNullOrBlank()) 0.dp else 8.dp)
                            ) {
                                message.attachments.forEach { attachment ->
                                    AttachmentContent(
                                        attachment = attachment,
                                        isFromMe = message.isFromMe,
                                        onMediaClick = onMediaClick
                                    )
                                }
                            }
                        }

                        // Text content with clickable dates, phone numbers, codes, and search highlighting
                        // If there's a link preview, strip the URL from the displayed text
                        val displayText = if (firstUrl != null && !message.text.isNullOrBlank()) {
                            message.text.replace(firstUrl.matchedText, "").trim()
                        } else {
                            message.text
                        }

                        if (!displayText.isNullOrBlank()) {
                            val textColor = when {
                                message.isFromMe && isIMessage -> bubbleColors.iMessageSentText
                                message.isFromMe -> bubbleColors.smsSentText
                                else -> bubbleColors.receivedText
                            }

                            val hasClickableContent = detectedDates.isNotEmpty() ||
                                    detectedPhoneNumbers.isNotEmpty() ||
                                    detectedCodes.isNotEmpty()

                            // Build annotated string with search highlighting
                            val annotatedText = if (!searchQuery.isNullOrBlank() && displayText.contains(searchQuery, ignoreCase = true)) {
                                buildSearchHighlightedText(
                                    text = displayText,
                                    searchQuery = searchQuery,
                                    textColor = textColor,
                                    detectedDates = detectedDates
                                )
                            } else if (hasClickableContent) {
                                buildAnnotatedStringWithClickables(
                                    text = displayText,
                                    detectedDates = detectedDates,
                                    detectedPhoneNumbers = detectedPhoneNumbers,
                                    detectedCodes = detectedCodes,
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
                                        // Check for date clicks
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
                                                    message.text ?: "",
                                                    detectedDates
                                                )
                                            }
                                            return@ClickableText
                                        }

                                        // Check for phone number clicks - show context menu
                                        annotatedText.getStringAnnotations(
                                            tag = "PHONE",
                                            start = offset,
                                            end = offset
                                        ).firstOrNull()?.let { annotation ->
                                            val phoneIndex = annotation.item.toIntOrNull()
                                            if (phoneIndex != null && phoneIndex < detectedPhoneNumbers.size) {
                                                selectedPhoneNumber = detectedPhoneNumbers[phoneIndex]
                                                showPhoneMenu = true
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                            return@ClickableText
                                        }

                                        // Check for code clicks - copy to clipboard
                                        annotatedText.getStringAnnotations(
                                            tag = "CODE",
                                            start = offset,
                                            end = offset
                                        ).firstOrNull()?.let { annotation ->
                                            val codeIndex = annotation.item.toIntOrNull()
                                            if (codeIndex != null && codeIndex < detectedCodes.size) {
                                                copyToClipboard(
                                                    context,
                                                    detectedCodes[codeIndex].code,
                                                    "Code copied"
                                                )
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            return@ClickableText
                                        }

                                        // Default tap behavior - toggle timestamp
                                        showTimestamp = !showTimestamp
                                    }
                                )

                                // Phone number context menu
                                DropdownMenu(
                                    expanded = showPhoneMenu,
                                    onDismissRequest = {
                                        showPhoneMenu = false
                                        selectedPhoneNumber = null
                                    },
                                    offset = phoneMenuOffset
                                ) {
                                    selectedPhoneNumber?.let { phone ->
                                        DropdownMenuItem(
                                            text = { Text("Send message") },
                                            onClick = {
                                                openSmsIntent(context, phone.normalizedNumber)
                                                showPhoneMenu = false
                                                selectedPhoneNumber = null
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Message, contentDescription = null)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Call") },
                                            onClick = {
                                                openDialerIntent(context, phone.normalizedNumber)
                                                showPhoneMenu = false
                                                selectedPhoneNumber = null
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Phone, contentDescription = null)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Add to contacts") },
                                            onClick = {
                                                openAddContactIntent(context, phone.normalizedNumber)
                                                showPhoneMenu = false
                                                selectedPhoneNumber = null
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.PersonAdd, contentDescription = null)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Copy") },
                                            onClick = {
                                                copyToClipboard(context, phone.matchedText, "Phone number copied")
                                                showPhoneMenu = false
                                                selectedPhoneNumber = null
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                            }
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = displayText ?: "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = textColor
                                )
                            }
                        }

                        // Link preview for the first URL in the message
                        firstUrl?.let { detectedUrl ->
                            LinkPreview(
                                url = detectedUrl.url,
                                isFromMe = message.isFromMe
                            )
                        }
                    }
                }

                // Display reactions floating at top corner outside the bubble
                // For outbound messages: top-left; for received: top-right
                if (message.reactions.isNotEmpty()) {
                    ReactionsDisplay(
                        reactions = message.reactions,
                        isFromMe = message.isFromMe,
                        modifier = Modifier
                            .align(if (message.isFromMe) Alignment.TopStart else Alignment.TopEnd)
                            .offset(
                                x = if (message.isFromMe) (-20).dp else 20.dp,
                                y = (-14).dp
                            )
                    )
                }
            }

            // Tap-to-reveal timestamp and message type below the bubble
            if (showTimestamp) {
                Text(
                    text = "${message.formattedTime} · $messageTypeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(
                            start = if (message.isFromMe) 0.dp else 12.dp,
                            end = if (message.isFromMe) 12.dp else 0.dp,
                            top = 2.dp
                        )
                )
            }

            // Delivery status indicator for outbound messages
            if (message.isFromMe) {
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 4.dp, top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DeliveryIndicator(
                        isSent = message.isSent,
                        isDelivered = message.isDelivered,
                        isRead = message.isRead,
                        hasError = message.hasError
                    )
                }
            }
        }

    }
    } // Close Box
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
 * Represents a clickable span with its position and type
 */
private sealed class ClickableSpan(
    open val startIndex: Int,
    open val endIndex: Int,
    open val matchedText: String,
    open val index: Int
) {
    data class DateSpan(
        override val startIndex: Int,
        override val endIndex: Int,
        override val matchedText: String,
        override val index: Int
    ) : ClickableSpan(startIndex, endIndex, matchedText, index)

    data class PhoneSpan(
        override val startIndex: Int,
        override val endIndex: Int,
        override val matchedText: String,
        override val index: Int
    ) : ClickableSpan(startIndex, endIndex, matchedText, index)

    data class CodeSpan(
        override val startIndex: Int,
        override val endIndex: Int,
        override val matchedText: String,
        override val index: Int
    ) : ClickableSpan(startIndex, endIndex, matchedText, index)
}

/**
 * Builds an AnnotatedString with all clickable elements: dates, phone numbers, and codes
 */
@Composable
private fun buildAnnotatedStringWithClickables(
    text: String,
    detectedDates: List<DetectedDate>,
    detectedPhoneNumbers: List<DetectedPhoneNumber>,
    detectedCodes: List<DetectedCode>,
    textColor: Color
): AnnotatedString {
    // Combine all clickable spans and sort by position
    val allSpans = mutableListOf<ClickableSpan>()

    detectedDates.forEachIndexed { index, date ->
        allSpans.add(ClickableSpan.DateSpan(date.startIndex, date.endIndex, date.matchedText, index))
    }
    detectedPhoneNumbers.forEachIndexed { index, phone ->
        allSpans.add(ClickableSpan.PhoneSpan(phone.startIndex, phone.endIndex, phone.matchedText, index))
    }
    detectedCodes.forEachIndexed { index, code ->
        allSpans.add(ClickableSpan.CodeSpan(code.startIndex, code.endIndex, code.matchedText, index))
    }

    // Sort by start index and filter overlapping spans (prefer dates > phones > codes)
    val sortedSpans = allSpans.sortedBy { it.startIndex }
    val filteredSpans = mutableListOf<ClickableSpan>()
    var lastEndIndex = 0

    for (span in sortedSpans) {
        if (span.startIndex >= lastEndIndex) {
            filteredSpans.add(span)
            lastEndIndex = span.endIndex
        }
    }

    return buildAnnotatedString {
        var currentIndex = 0

        for (span in filteredSpans) {
            // Add text before the clickable element
            if (span.startIndex > currentIndex) {
                append(text.substring(currentIndex, span.startIndex))
            }

            // Add the clickable element with underline style and annotation
            val tag = when (span) {
                is ClickableSpan.DateSpan -> "DATE"
                is ClickableSpan.PhoneSpan -> "PHONE"
                is ClickableSpan.CodeSpan -> "CODE"
            }

            pushStringAnnotation(tag = tag, annotation = span.index.toString())
            withStyle(
                SpanStyle(
                    textDecoration = TextDecoration.Underline,
                    color = textColor
                )
            ) {
                append(span.matchedText)
            }
            pop()

            currentIndex = span.endIndex
        }

        // Add remaining text after last clickable element
        if (currentIndex < text.length) {
            append(text.substring(currentIndex))
        }
    }
}

/**
 * Copies text to clipboard and shows a toast
 */
private fun copyToClipboard(context: Context, text: String, toastMessage: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Copied text", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}

/**
 * Opens SMS app to compose a message to the given phone number
 */
private fun openSmsIntent(context: Context, phoneNumber: String) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("smsto:$phoneNumber")
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No SMS app found", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Opens the phone dialer with the given phone number
 */
private fun openDialerIntent(context: Context, phoneNumber: String) {
    val intent = Intent(Intent.ACTION_DIAL).apply {
        data = Uri.parse("tel:$phoneNumber")
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No dialer app found", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Opens the add contact screen with the given phone number pre-filled
 */
private fun openAddContactIntent(context: Context, phoneNumber: String) {
    val intent = Intent(Intent.ACTION_INSERT).apply {
        type = ContactsContract.Contacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No contacts app found", Toast.LENGTH_SHORT).show()
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
 * Opens the calendar app to add a new event
 */
private fun openCalendarIntent(
    context: android.content.Context,
    detectedDate: DetectedDate,
    messageText: String,
    allDetectedDates: List<DetectedDate> = emptyList()
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

    // Extract event title by removing date parts and prepositions
    val eventTitle = extractEventTitle(messageText, allDetectedDates.ifEmpty { listOf(detectedDate) })

    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
        // Only set title if we extracted something meaningful
        if (eventTitle.isNotBlank()) {
            putExtra(CalendarContract.Events.TITLE, eventTitle)
        }
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
 * Extracts event title by removing date parts and common prepositions from the message.
 * Returns empty string if no meaningful title can be extracted.
 */
private fun extractEventTitle(messageText: String, detectedDates: List<DetectedDate>): String {
    var result = messageText

    // Remove all detected date substrings from the message
    // Process in reverse order to maintain correct indices
    detectedDates.sortedByDescending { it.startIndex }.forEach { date ->
        result = result.removeRange(date.startIndex, date.endIndex)
    }

    // Remove common prepositions and connecting words that precede/follow dates
    val prepositionsToRemove = listOf(
        "\\bat\\b", "\\bon\\b", "\\bfor\\b", "\\bto\\b", "\\bfrom\\b",
        "\\buntil\\b", "\\bby\\b", "\\bstarting\\b", "\\bbegins\\b",
        "\\bstarts\\b", "\\bscheduled\\b", "\\bset\\b", "\\bplanned\\b",
        "\\bthe\\b", "\\bis\\b", "\\bare\\b",
        // Relative date words that might remain after date removal
        "\\btomorrow\\b", "\\btoday\\b", "\\bnext\\b", "\\bthis\\b",
        "\\bweek\\b", "\\bmonth\\b", "\\byear\\b", "\\bweekend\\b",
        "\\bmonday\\b", "\\btuesday\\b", "\\bwednesday\\b", "\\bthursday\\b",
        "\\bfriday\\b", "\\bsaturday\\b", "\\bsunday\\b"
    )

    for (prep in prepositionsToRemove) {
        result = result.replace(Regex(prep, RegexOption.IGNORE_CASE), " ")
    }

    // Clean up the result
    result = result
        // Remove multiple spaces
        .replace(Regex("\\s+"), " ")
        // Remove leading/trailing punctuation and whitespace
        .trim()
        .trimStart(':', '-', ',', '.', '!', '?', ';')
        .trimEnd(':', '-', ',', '.', '!', '?', ';')
        .trim()

    // If the result is too short or just punctuation/whitespace, return empty
    if (result.length < 2 || result.all { !it.isLetterOrDigit() }) {
        return ""
    }

    // Capitalize first letter if needed
    return result.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
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
        Text(
            text = date,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
