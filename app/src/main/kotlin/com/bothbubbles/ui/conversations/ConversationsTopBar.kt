package com.bothbubbles.ui.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.sp
import com.bothbubbles.R
import com.bothbubbles.services.categorization.MessageCategory
import com.bothbubbles.ui.theme.KumbhSansFamily
import com.bothbubbles.util.HapticUtils

/**
 * Main top bar for the Conversations screen (non-selection mode).
 *
 * Contains the app title, filter dropdown, search button, and settings button.
 */
@Composable
internal fun ConversationsTopBar(
    useSimpleAppTitle: Boolean,
    conversationFilter: ConversationFilter,
    categoryFilter: MessageCategory?,
    categorizationEnabled: Boolean,
    enabledCategories: Set<MessageCategory>,
    hasSettingsWarning: Boolean,
    totalUnreadCount: Int,
    showUnreadCountInHeader: Boolean,
    onFilterSelected: (ConversationFilter) -> Unit,
    onCategorySelected: (MessageCategory?) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onTitleClick: () -> Unit,
    onUnreadBadgeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var showFilterDropdown by remember { mutableStateOf(false) }
    val hasActiveFilter = conversationFilter != ConversationFilter.ALL || categoryFilter != null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App title with unread badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (useSimpleAppTitle) {
                    buildAnnotatedString { append("Messages") }
                } else {
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("Both")
                        }
                        append("Bubbles")
                    }
                },
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = KumbhSansFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 22.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable {
                    HapticUtils.onTap(haptic)
                    onTitleClick()
                }
            )

            // Unread count badge
            if (showUnreadCountInHeader && totalUnreadCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable {
                            HapticUtils.onTap(haptic)
                            onUnreadBadgeClick()
                        }
                        .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (totalUnreadCount > 10000) "9999+" else totalUnreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Filter dropdown
        Box {
            IconButton(onClick = {
                HapticUtils.onTap(haptic)
                showFilterDropdown = true
            }) {
                Icon(
                    imageVector = if (hasActiveFilter) {
                        Icons.Default.FilterList
                    } else {
                        Icons.Outlined.FilterList
                    },
                    contentDescription = "Filter conversations",
                    tint = if (hasActiveFilter) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
            // Active filter badge indicator
            if (hasActiveFilter) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .offset(x = 32.dp, y = 10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }

            FilterDropdownMenu(
                expanded = showFilterDropdown,
                onDismiss = { showFilterDropdown = false },
                conversationFilter = conversationFilter,
                categoryFilter = categoryFilter,
                categorizationEnabled = categorizationEnabled,
                enabledCategories = enabledCategories,
                onFilterSelected = { filter ->
                    onFilterSelected(filter)
                    showFilterDropdown = false
                },
                onCategorySelected = { category ->
                    onCategorySelected(category)
                    showFilterDropdown = false
                }
            )
        }

        // Search button
        IconButton(onClick = {
            HapticUtils.onTap(haptic)
            onSearchClick()
        }) {
            Icon(
                Icons.Default.Search,
                contentDescription = stringResource(R.string.search_conversations),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        // Settings button with warning badge
        Box {
            IconButton(onClick = {
                HapticUtils.onTap(haptic)
                onSettingsClick()
            }) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            // Warning badge indicator
            if (hasSettingsWarning) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .offset(x = 32.dp, y = 10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
            }
        }
    }
}

/**
 * Filter dropdown menu showing conversation filters and category filters.
 */
@Composable
private fun FilterDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    conversationFilter: ConversationFilter,
    categoryFilter: MessageCategory?,
    categorizationEnabled: Boolean,
    enabledCategories: Set<MessageCategory>,
    onFilterSelected: (ConversationFilter) -> Unit,
    onCategorySelected: (MessageCategory?) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        // Status filters
        ConversationFilter.entries.forEach { filter ->
            val isSelected = conversationFilter == filter && categoryFilter == null
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = filter.icon,
                            contentDescription = null,
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = filter.label,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = if (isSelected) {
                                FontWeight.Medium
                            } else {
                                FontWeight.Normal
                            }
                        )
                    }
                },
                onClick = { onFilterSelected(filter) }
            )
        }

        // Category filters (only shown when categorization is enabled and at least one category is enabled)
        if (categorizationEnabled && enabledCategories.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Text(
                text = "Categories",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            MessageCategory.entries.filter { it in enabledCategories }.forEach { category ->
                val isSelected = categoryFilter == category
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = category.icon,
                                contentDescription = null,
                                tint = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = category.displayName,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (isSelected) {
                                    FontWeight.Medium
                                } else {
                                    FontWeight.Normal
                                }
                            )
                        }
                    },
                    onClick = { onCategorySelected(category) }
                )
            }
        }
    }
}
