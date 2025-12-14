package com.bothbubbles.ui.chat.components

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.chat.delegates.ChatSearchDelegate.MatchType
import com.bothbubbles.ui.chat.delegates.ChatSearchDelegate.SearchResult
import com.bothbubbles.ui.components.common.buildSearchHighlightedText

/**
 * Bottom sheet displaying all search results from the database.
 * Shows when user taps "View All" in the inline search bar.
 *
 * Features:
 * - Lists all matching messages with snippets
 * - Shows match type icons (text, subject, attachment)
 * - Tapping a result jumps to that message in the conversation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultsSheet(
    visible: Boolean,
    results: List<SearchResult>,
    isSearching: Boolean,
    query: String,
    onResultClick: (SearchResult) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                // Header
                Text(
                    text = "Search Results",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Progress indicator
                if (isSearching) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                    Text(
                        text = "Searching older messages...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Results list or empty state
                when {
                    results.isEmpty() && !isSearching -> {
                        EmptySearchState(query = query)
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 400.dp)
                        ) {
                            items(results, key = { it.messageGuid }) { result ->
                                SearchResultItem(
                                    result = result,
                                    query = query,
                                    onClick = { onResultClick(result) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    result: SearchResult,
    query: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Match type icon
            Icon(
                imageVector = when (result.matchType) {
                    MatchType.TEXT -> Icons.Default.Message
                    MatchType.SUBJECT -> Icons.Default.Subject
                    MatchType.ATTACHMENT_NAME -> Icons.Default.AttachFile
                },
                contentDescription = when (result.matchType) {
                    MatchType.TEXT -> "Text match"
                    MatchType.SUBJECT -> "Subject match"
                    MatchType.ATTACHMENT_NAME -> "Attachment match"
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Sender and time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = result.senderName ?: "Unknown",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    if (result.timestamp > 0) {
                        Text(
                            text = formatRelativeTimeShort(result.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Snippet with highlight
                Text(
                    text = buildSearchHighlightedText(
                        text = result.snippet,
                        searchQuery = query,
                        textColor = MaterialTheme.colorScheme.onSurface
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Indicator if message is not loaded in memory
                if (!result.isLoadedInMemory) {
                    Text(
                        text = "Tap to jump to message",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptySearchState(query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No matches found",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Try different keywords",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Format a timestamp as a short relative time string.
 */
@Composable
private fun formatRelativeTimeShort(timestamp: Long): String {
    val now = System.currentTimeMillis()
    return DateUtils.getRelativeTimeSpanString(
        timestamp,
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
}
