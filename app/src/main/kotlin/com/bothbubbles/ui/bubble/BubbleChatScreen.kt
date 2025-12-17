package com.bothbubbles.ui.bubble

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.chat.composer.ChatComposer
import com.bothbubbles.ui.chat.composer.ComposerEvent
import com.bothbubbles.ui.components.common.Avatar
import com.bothbubbles.ui.components.message.MessageBubble
import com.bothbubbles.ui.components.message.MessageGroupPosition
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
    val composerState by viewModel.composerState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
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
            ChatComposer(
                state = composerState,
                onEvent = { event ->
                    viewModel.onComposerEvent(event)
                    // Scroll to bottom after sending
                    if (event is ComposerEvent.Send) {
                        scope.launch {
                            listState.animateScrollToItem(0)
                        }
                    }
                },
                modifier = Modifier.imePadding()
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

