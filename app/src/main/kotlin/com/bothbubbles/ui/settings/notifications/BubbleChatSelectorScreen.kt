package com.bothbubbles.ui.settings.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.outlined.Circle
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
fun BubbleChatSelectorScreen(
    onNavigateBack: () -> Unit,
    viewModel: BubbleChatSelectorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select conversations") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Select/Deselect all menu
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Selection options"
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Select all") },
                            onClick = {
                                viewModel.selectAll()
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Deselect all") },
                            onClick = {
                                viewModel.deselectAll()
                                showMenu = false
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        BubbleChatSelectorContent(
            modifier = Modifier.padding(padding),
            uiState = uiState,
            onToggleConversation = viewModel::toggleConversation
        )
    }
}

@Composable
private fun BubbleChatSelectorContent(
    modifier: Modifier = Modifier,
    uiState: BubbleChatSelectorUiState,
    onToggleConversation: (String) -> Unit
) {
    if (uiState.isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (uiState.conversations.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    text = "No conversations",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Start a conversation to enable bubbles for it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Info banner
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Selected conversations will show as floating bubbles when new messages arrive",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        // Selection count
        Text(
            text = "${uiState.selectedChatGuids.size} of ${uiState.conversations.size} selected",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(
                items = uiState.conversations,
                key = { it.chatGuid }
            ) { conversation ->
                ConversationSelectItem(
                    conversation = conversation,
                    onToggle = { onToggleConversation(conversation.chatGuid) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ConversationSelectItem(
    conversation: SelectableConversation,
    onToggle: () -> Unit
) {
    val formattedName = if (!conversation.isGroup) {
        PhoneNumberFormatter.format(conversation.displayName)
    } else {
        conversation.displayName
    }

    ListItem(
        headlineContent = {
            Text(
                text = formattedName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = if (conversation.isGroup) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Group,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Group",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else null,
        leadingContent = {
            Avatar(
                name = formattedName,
                size = 48.dp
            )
        },
        trailingContent = {
            IconButton(onClick = onToggle) {
                if (conversation.isSelected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Outlined.Circle,
                        contentDescription = "Not selected",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onToggle)
    )
}
