package com.bothbubbles.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bothbubbles.ui.chat.composer.ChatComposer
import com.bothbubbles.ui.components.message.MessageBubble
import com.bothbubbles.ui.components.message.MessageUiModel

/**
 * Apple-style compose screen with recipient field and inline conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    onNavigateBack: () -> Unit,
    onNavigateToChat: (String) -> Unit,
    viewModel: ComposeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val composerState by viewModel.composerState.collectAsState()
    val gifPickerState by viewModel.gifPickerState.collectAsState()
    val gifSearchQuery by viewModel.gifSearchQuery.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle navigation after send
    LaunchedEffect(uiState.navigateToChatGuid) {
        uiState.navigateToChatGuid?.let { chatGuid ->
            viewModel.onNavigated()
            onNavigateToChat(chatGuid)
        }
    }

    // Show error in snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "New Message",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onNavigateBack) {
                        Text("Cancel")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.ime
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Recipient Field
            Box {
                RecipientField(
                    chips = uiState.chips,
                    inputText = uiState.recipientInput,
                    effectiveService = uiState.effectiveService,
                    isLocked = uiState.isRecipientFieldLocked,
                    onInputChange = viewModel::onRecipientInputChange,
                    onChipRemove = viewModel::onChipRemove,
                    onEnterPressed = viewModel::onRecipientEnterPressed,
                    focusRequester = focusRequester
                )

                // Suggestion dropdown (positioned below recipient field)
                RecipientSuggestionPopup(
                    visible = uiState.showSuggestions,
                    suggestions = uiState.suggestions,
                    selectedIndex = 0, // TODO: Track selected index
                    allowGroups = uiState.chips.isEmpty(),
                    onSelect = viewModel::onSuggestionSelected,
                    modifier = Modifier
                        .padding(top = 56.dp) // Position below recipient field
                        .padding(horizontal = 8.dp)
                )
            }

            HorizontalDivider()

            // Conversation Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (val state = uiState.conversationState) {
                    is ComposeConversationState.Empty -> {
                        EmptyConversationPlaceholder()
                    }
                    is ComposeConversationState.Loading -> {
                        LoadingIndicator()
                    }
                    is ComposeConversationState.NewConversation -> {
                        NewConversationPlaceholder()
                    }
                    is ComposeConversationState.Existing -> {
                        MessageList(
                            messages = state.messages,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            HorizontalDivider()

            // Full ChatComposer with GIF picker, attachments, camera, etc.
            ChatComposer(
                state = composerState,
                onEvent = viewModel::onComposerEvent,
                gifPickerState = gifPickerState,
                gifSearchQuery = gifSearchQuery,
                onGifSearchQueryChange = viewModel::onGifSearchQueryChange,
                onGifSearch = viewModel::onGifSearch,
                onGifSelected = viewModel::onGifSelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
            )
        }
    }
}

@Composable
private fun EmptyConversationPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Add recipients to start a conversation",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NewConversationPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Start a new conversation",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Preview message list using the real MessageBubble component for proper styling.
 */
@Composable
private fun MessageList(
    messages: List<MessageUiModel>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when messages load
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = messages,
            key = { it.guid }
        ) { message ->
            // Use the real MessageBubble component with minimal callbacks
            MessageBubble(
                message = message,
                onLongPress = { /* No long-press menu in preview */ },
                onMediaClick = { /* No media viewer in preview */ },
                showDeliveryIndicator = false // Hide delivery status in preview
            )
        }
    }
}

