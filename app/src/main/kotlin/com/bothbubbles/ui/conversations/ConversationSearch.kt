package com.bothbubbles.ui.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SearchOverlay(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedFilter: SearchFilter?,
    onFilterSelected: (SearchFilter) -> Unit,
    onFilterCleared: () -> Unit,
    onClose: () -> Unit,
    conversations: List<ConversationUiModel>,
    messageSearchResults: List<MessageSearchResult>,
    onConversationClick: (chatGuid: String, mergedGuids: List<String>) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp) // Account for status bar
        ) {
            // Search bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back button
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Filter tag (shown when a filter is selected)
                    if (selectedFilter != null) {
                        SearchFilterTag(
                            filter = selectedFilter,
                            onRemove = onFilterCleared
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

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
                            Box {
                                if (query.isEmpty() && selectedFilter == null) {
                                    Text(
                                        text = "Search messages",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 16.sp
                                    )
                                } else if (query.isEmpty() && selectedFilter != null) {
                                    Text(
                                        text = "Search in ${selectedFilter.label.lowercase()}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 16.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    if (query.isNotEmpty() || selectedFilter != null) {
                        IconButton(onClick = {
                            onQueryChange("")
                            onFilterCleared()
                        }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Filter chips grid (shown when no query and no filter selected)
            if (query.isEmpty() && selectedFilter == null) {
                SearchFilterGrid(
                    selectedFilter = selectedFilter,
                    onFilterSelected = onFilterSelected,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                // Search results (filtered by both query and filter type)
                val filteredConversations = conversations.filter { conv ->
                    val matchesQuery = query.isEmpty() ||
                        conv.displayName.contains(query, ignoreCase = true) ||
                        conv.address.contains(query, ignoreCase = true) ||
                        // Also search link preview titles for conversations with links
                        (conv.lastMessageLinkTitle?.contains(query, ignoreCase = true) == true)

                    val matchesFilter = when (selectedFilter) {
                        SearchFilter.UNREAD -> conv.unreadCount > 0
                        SearchFilter.KNOWN -> !conv.displayName.contains("@") &&
                            !conv.displayName.matches(Regex("^[+\\d\\s()-]+$"))
                        SearchFilter.UNKNOWN -> conv.displayName.contains("@") ||
                            conv.displayName.matches(Regex("^[+\\d\\s()-]+$"))
                        SearchFilter.STARRED -> conv.isPinned // Using pinned as proxy for starred
                        SearchFilter.IMAGES -> conv.lastMessageType == MessageType.IMAGE
                        SearchFilter.VIDEOS -> conv.lastMessageType == MessageType.VIDEO
                        SearchFilter.PLACES -> conv.lastMessageLinkDomain?.let { domain ->
                            // Check if the domain matches any known places/maps service
                            val placesPatterns = listOf(
                                "maps.google", "google.com/maps", "maps.app.goo.gl",
                                "goo.gl/maps", "maps.apple.com", "findmy.apple.com",
                                "find-my.apple.com"
                            )
                            placesPatterns.any { domain.lowercase().contains(it) }
                        } == true
                        SearchFilter.LINKS -> conv.lastMessageType == MessageType.LINK
                        null -> true
                    }

                    matchesQuery && matchesFilter
                }

                // Filter message results to exclude conversations already shown
                val conversationGuids = filteredConversations.map { it.guid }.toSet()
                val filteredMessageResults = if (selectedFilter == null) {
                    messageSearchResults.filter { it.chatGuid !in conversationGuids }
                } else {
                    emptyList() // Don't show message results when a filter is selected
                }

                val hasResults = filteredConversations.isNotEmpty() || filteredMessageResults.isNotEmpty()

                if (!hasResults) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = selectedFilter?.icon ?: Icons.Default.SearchOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Text(
                                text = if (selectedFilter != null) {
                                    "No ${selectedFilter.label.lowercase()} found"
                                } else {
                                    "No results found"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn {
                        // Conversation matches (name/number matches)
                        if (filteredConversations.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Conversations",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            items(
                                items = filteredConversations,
                                key = { it.guid }
                            ) { conversation ->
                                GoogleStyleConversationTile(
                                    conversation = conversation,
                                    onClick = { onConversationClick(conversation.guid, conversation.mergedChatGuids) },
                                    onLongClick = { }
                                )
                            }
                        }

                        // Message content matches - reuse GoogleStyleConversationTile
                        if (filteredMessageResults.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Messages",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            items(
                                items = filteredMessageResults,
                                key = { it.messageGuid }
                            ) { result ->
                                // Look up conversation to get merged guids
                                val conversation = conversations.find { it.guid == result.chatGuid }
                                val mergedGuids = conversation?.mergedChatGuids ?: listOf(result.chatGuid)
                                GoogleStyleConversationTile(
                                    conversation = result.toConversationUiModel(),
                                    onClick = { onConversationClick(result.chatGuid, mergedGuids) },
                                    onLongClick = { } // No long-press actions for search results
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchFilterTag(
    filter: SearchFilter,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                filter.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = filter.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove filter",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
internal fun SearchFilterGrid(
    selectedFilter: SearchFilter?,
    onFilterSelected: (SearchFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val filters = SearchFilter.entries

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Create 2-column grid
        filters.chunked(2).forEach { rowFilters ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowFilters.forEach { filter ->
                    SearchFilterChip(
                        filter = filter,
                        isSelected = selectedFilter == filter,
                        onClick = { onFilterSelected(filter) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Fill empty space if odd number
                if (rowFilters.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun SearchFilterChip(
    filter: SearchFilter,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier.height(52.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                filter.icon,
                contentDescription = null,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = filter.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}
