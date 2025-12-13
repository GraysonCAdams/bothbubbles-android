package com.bothbubbles.ui.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.R
import com.bothbubbles.ui.components.common.Avatar
import com.bothbubbles.ui.components.message.MessageBubble
import com.bothbubbles.ui.components.message.MessageGroupPosition
import com.bothbubbles.ui.theme.BothBubblesTheme
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.launch

/**
 * Compact chat screen designed for Android conversation bubbles.
 * Provides a minimal interface for quick message viewing and replies.
 *
 * Features:
 * - Compact header with avatar and conversation title
 * - Recent messages (last 10 messages displayed)
 * - Simple text input with send button
 * - Expand button to open full chat in main app
 * - Close button to dismiss the bubble
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleChatScreen(
    chatGuid: String,
    chatTitle: String,
    onExpandClick: () -> Unit,
    onCloseClick: () -> Unit,
    viewModel: BubbleChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val inputColors = BothBubblesTheme.bubbleColors

    // Use title from ViewModel if loaded, otherwise fall back to passed title
    val displayTitle = uiState.chatTitle.ifEmpty { chatTitle }

    // Limit messages shown in bubble to last 10 for performance
    val recentMessages = remember(uiState.messages) {
        uiState.messages.take(10)
    }

    // Auto-scroll to newest message
    LaunchedEffect(recentMessages.firstOrNull()?.guid) {
        if (recentMessages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BubbleTopBar(
                title = displayTitle,
                onExpandClick = onExpandClick,
                onCloseClick = onCloseClick
            )
        },
        bottomBar = {
            BubbleInputBar(
                text = uiState.draftText,
                onTextChange = viewModel::updateDraft,
                onSendClick = {
                    if (uiState.draftText.isNotBlank()) {
                        viewModel.sendMessage()
                        scope.launch {
                            // Scroll to show sent message
                            listState.animateScrollToItem(0)
                        }
                    }
                },
                isSending = uiState.isSending,
                isLocalSmsChat = uiState.isLocalSmsChat,
                modifier = Modifier.navigationBarsPadding().imePadding()
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                recentMessages.isEmpty() -> {
                    Text(
                        text = "No messages yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(
                            horizontal = 12.dp,
                            vertical = 8.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = recentMessages,
                            key = { it.guid }
                        ) { message ->
                            // Skip reaction messages in bubble view
                            if (!message.isReaction) {
                                MessageBubble(
                                    message = message,
                                    onLongPress = { /* No tapback in bubble */ },
                                    onMediaClick = { /* Open in full app */ onExpandClick() },
                                    groupPosition = MessageGroupPosition.SINGLE,
                                    searchQuery = null,
                                    isCurrentSearchMatch = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact top bar for the bubble chat screen.
 * Shows avatar, title, expand button, and close button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BubbleTopBar(
    title: String,
    onExpandClick: () -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(
                    name = title,
                    size = 32.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (PhoneNumberFormatter.isPhoneNumber(title)) {
                        PhoneNumberFormatter.format(title)
                    } else {
                        title
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        actions = {
            // Expand to full app button
            IconButton(onClick = onExpandClick) {
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = "Open in app",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Close bubble button
            IconButton(onClick = onCloseClick) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

/**
 * Compact input bar for the bubble chat screen.
 * Simplified version with just text input and send button.
 */
@Composable
private fun BubbleInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    isSending: Boolean,
    isLocalSmsChat: Boolean,
    modifier: Modifier = Modifier
) {
    val inputColors = BothBubblesTheme.bubbleColors
    val hasContent = text.isNotBlank()

    // Protocol-based coloring: green for SMS, blue for iMessage
    val sendButtonColor = if (isLocalSmsChat) {
        Color(0xFF34C759) // Green for SMS
    } else {
        MaterialTheme.colorScheme.primary // Blue for iMessage
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 0.dp,
        color = inputColors.inputBackground
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rounded text input
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp),
                shape = RoundedCornerShape(22.dp),
                color = inputColors.inputFieldBackground
            ) {
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = stringResource(
                                if (isLocalSmsChat) R.string.message_placeholder_text
                                else R.string.message_placeholder_imessage
                            ),
                            color = inputColors.inputPlaceholder
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = inputColors.inputText,
                        unfocusedTextColor = inputColors.inputText,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    maxLines = 3,
                    singleLine = false
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Send button (only visible when there's content)
            if (hasContent) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSending) sendButtonColor.copy(alpha = 0.38f) else sendButtonColor
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        IconButton(
                            onClick = onSendClick,
                            enabled = !isSending
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.send_message),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
