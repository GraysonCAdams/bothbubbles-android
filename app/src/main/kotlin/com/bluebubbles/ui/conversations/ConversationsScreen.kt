package com.bluebubbles.ui.conversations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluebubbles.R
import com.bluebubbles.ui.components.Avatar
import com.bluebubbles.ui.components.GroupAvatar

// Google-style gradient colors for profile ring
private val profileRingGradient = listOf(
    Color(0xFF34A853), // Green
    Color(0xFFFBBC04), // Yellow
    Color(0xFFEA4335), // Red
    Color(0xFF4285F4)  // Blue
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationsScreen(
    onConversationClick: (String) -> Unit,
    onNewMessageClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var isSearchActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                Column {
                    // Main header row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // App title
                        Text(
                            text = "BlueBubbles",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Normal,
                                fontSize = 22.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        // Search button
                        IconButton(onClick = {
                            isSearchActive = true
                        }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.search_conversations),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Profile avatar with gradient ring
                        ProfileAvatarWithRing(
                            onClick = onSettingsClick
                        )
                    }

                    // Search bar (animated)
                    AnimatedVisibility(
                        visible = isSearchActive,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        SearchBar(
                            query = uiState.searchQuery,
                            onQueryChange = { viewModel.updateSearchQuery(it) },
                            onClose = {
                                isSearchActive = false
                                viewModel.updateSearchQuery("")
                                focusManager.clearFocus()
                            },
                            focusRequester = focusRequester,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )

                        LaunchedEffect(isSearchActive) {
                            if (isSearchActive) {
                                focusRequester.requestFocus()
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewMessageClick,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Message,
                        contentDescription = null
                    )
                    Text(
                        text = "Start chat",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.conversations.isEmpty() -> {
                EmptyConversationsState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    isSearching = uiState.searchQuery.isNotBlank()
                )
            }
            else -> {
                val pinnedConversations = uiState.conversations.filter { it.isPinned }
                val regularConversations = uiState.conversations.filter { !it.isPinned }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    // Pinned section (iOS-style horizontal row)
                    if (pinnedConversations.isNotEmpty()) {
                        item {
                            PinnedConversationsRow(
                                conversations = pinnedConversations,
                                onConversationClick = onConversationClick,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        // Divider between pinned and regular
                        item {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }

                    // Regular conversations
                    items(
                        items = regularConversations,
                        key = { it.guid }
                    ) { conversation ->
                        GoogleStyleConversationTile(
                            conversation = conversation,
                            onClick = { onConversationClick(conversation.guid) },
                            onLongClick = { /* TODO: Context menu */ },
                            modifier = Modifier.animateItemPlacement()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileAvatarWithRing(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .border(
                    width = 2.dp,
                    brush = Brush.sweepGradient(profileRingGradient),
                    shape = CircleShape
                )
                .padding(3.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Person,
                contentDescription = "Profile",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(12.dp))

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            text = "Search conversations",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp
                        )
                    }
                    innerTextField()
                }
            )

            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Outlined.Cancel,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GoogleStyleConversationTile(
    conversation: ConversationUiModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with optional badge
            Box(modifier = Modifier.size(56.dp)) {
                if (conversation.isGroup) {
                    GroupAvatar(
                        names = listOf(conversation.displayName), // TODO: Get participant names
                        size = 56.dp
                    )
                } else {
                    Avatar(
                        name = conversation.displayName,
                        avatarPath = conversation.avatarPath,
                        size = 56.dp
                    )
                }

                // Chat badge indicator for recent messages
                if (conversation.isTyping) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .size(20.dp)
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            TypingDots()
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                // Name row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = conversation.displayName,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (conversation.isMuted) {
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = "Muted",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Message preview
                Text(
                    text = formatMessagePreview(conversation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        conversation.hasDraft -> MaterialTheme.colorScheme.error
                        conversation.unreadCount > 0 -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Trailing content - timestamp and unread badge
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Draft or timestamp
                if (conversation.hasDraft) {
                    Text(
                        text = "Draft",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = conversation.lastMessageTime,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Unread badge
                if (conversation.unreadCount > 0) {
                    UnreadBadge(count = conversation.unreadCount)
                }
            }
        }
    }
}

@Composable
private fun TypingDots() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(2.dp)
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer)
            )
        }
    }
}

@Composable
private fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = CircleShape,
        modifier = modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
private fun PinnedConversationsRow(
    conversations: List<ConversationUiModel>,
    onConversationClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(
            items = conversations,
            key = { it.guid }
        ) { conversation ->
            PinnedConversationItem(
                conversation = conversation,
                onClick = { onConversationClick(conversation.guid) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedConversationItem(
    conversation: ConversationUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { /* TODO: Context menu */ }
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar with unread badge
        Box {
            if (conversation.isGroup) {
                GroupAvatar(
                    names = conversation.participantNames.ifEmpty { listOf(conversation.displayName) },
                    size = 56.dp
                )
            } else {
                Avatar(
                    name = conversation.displayName,
                    avatarPath = conversation.avatarPath,
                    size = 56.dp
                )
            }

            // Unread badge
            if (conversation.unreadCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.error,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-2).dp)
                        .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = if (conversation.unreadCount > 9) "9+" else conversation.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            color = MaterialTheme.colorScheme.onError
                        )
                    }
                }
            }

            // Typing indicator
            if (conversation.isTyping) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .size(20.dp)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        TypingDots()
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Name (truncated)
        Text(
            text = conversation.displayName.split(" ").firstOrNull() ?: conversation.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyConversationsState(
    modifier: Modifier = Modifier,
    isSearching: Boolean = false
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isSearching) Icons.Default.SearchOff else Icons.AutoMirrored.Filled.Message,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = if (isSearching) "No conversations found" else stringResource(R.string.no_conversations),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!isSearching) {
                Text(
                    text = "Start a conversation to get chatting",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun formatMessagePreview(conversation: ConversationUiModel): String {
    return when {
        conversation.hasDraft -> conversation.draftText ?: ""
        conversation.isFromMe -> {
            val prefix = "You: "
            val content = when (conversation.lastMessageType) {
                MessageType.IMAGE -> "Image"
                MessageType.VIDEO -> "Video"
                MessageType.AUDIO -> "Audio"
                MessageType.LINK -> conversation.lastMessageText.take(50)
                MessageType.ATTACHMENT -> "Attachment"
                else -> conversation.lastMessageText
            }
            prefix + content
        }
        else -> conversation.lastMessageText
    }
}
