package com.bothbubbles.ui.settings.archived

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.components.common.Avatar
import com.bothbubbles.util.PhoneNumberFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedChatsScreen(
    onNavigateBack: () -> Unit,
    onChatClick: (String) -> Unit,
    viewModel: ArchivedChatsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archived") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        ArchivedChatsContent(
            modifier = Modifier.padding(padding),
            uiState = uiState,
            viewModel = viewModel,
            onChatClick = onChatClick
        )
    }
}

@Composable
fun ArchivedChatsContent(
    modifier: Modifier = Modifier,
    viewModel: ArchivedChatsViewModel = hiltViewModel(),
    uiState: ArchivedChatsUiState = viewModel.uiState.collectAsStateWithLifecycle().value,
    onChatClick: (String) -> Unit = {}
) {
    if (uiState.archivedChats.isEmpty()) {
        // Empty state
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    text = "No archived conversations",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Archived conversations will appear here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(
                items = uiState.archivedChats,
                key = { it.id }
            ) { chat ->
                val formattedAddress = PhoneNumberFormatter.format(chat.normalizedAddress)
                ListItem(
                    headlineContent = {
                        Text(
                            text = chat.displayName ?: formattedAddress,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = chat.latestMessageText?.let { messageText ->
                        {
                            Text(
                                text = messageText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    leadingContent = {
                        Avatar(
                            name = chat.displayName ?: formattedAddress,
                            size = 48.dp
                        )
                    },
                    trailingContent = {
                        IconButton(
                            onClick = { viewModel.unarchiveChat(chat.id) }
                        ) {
                            Icon(
                                Icons.Default.Unarchive,
                                contentDescription = "Unarchive"
                            )
                        }
                    },
                    modifier = Modifier.clickable { onChatClick(chat.sourceId) }  // Use sourceId (chat GUID) for navigation
                )
                HorizontalDivider()
            }
        }
    }
}
