package com.bothbubbles.ui.conversations.components

import android.content.Intent
import android.provider.ContactsContract
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import com.bothbubbles.services.categorization.MessageCategory
import com.bothbubbles.ui.conversations.ConversationFilter
import com.bothbubbles.ui.conversations.ConversationUiModel
import com.bothbubbles.ui.conversations.ConversationsTopBar
import com.bothbubbles.ui.conversations.SelectionModeHeader
import kotlinx.coroutines.launch

/**
 * Top bar wrapper that switches between normal and selection mode headers.
 *
 * Handles complex selection mode logic including:
 * - Contact adding for single non-group conversations
 * - Pin toggling based on contact availability
 * - Select all/deselect all for non-pinned conversations
 * - Read/unread status toggling
 */
@Composable
fun ConversationTopBarWrapper(
    isSelectionMode: Boolean,
    selectedConversations: Set<String>,
    conversations: List<ConversationUiModel>,
    conversationFilter: ConversationFilter,
    categoryFilter: MessageCategory?,
    categorizationEnabled: Boolean,
    enabledCategories: Set<MessageCategory>,
    useSimpleAppTitle: Boolean,
    showUnreadCountInHeader: Boolean,
    hasSettingsWarning: Boolean,
    totalUnreadCount: Int,
    listState: LazyListState,
    onSelectionClose: () -> Unit,
    onSelectAllToggle: (Set<String>) -> Unit,
    onPin: (String) -> Unit,
    onSnooze: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onBlock: () -> Unit,
    onFilterSelected: (ConversationFilter) -> Unit,
    onCategorySelected: (MessageCategory?) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onTitleClick: () -> Unit,
    onUnreadBadgeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDarkTheme = isSystemInDarkTheme()
    val darkerBackground = if (isDarkTheme) Color(0xFF1A1A1A) else Color(0xFFEDEDED)
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Surface(
        color = darkerBackground,
        tonalElevation = 0.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.statusBarsPadding()
        ) {
            if (isSelectionMode) {
                // Determine if "Add contact" should be shown
                val selectedConversation = if (selectedConversations.size == 1) {
                    conversations.find { it.guid == selectedConversations.first() }
                } else null
                val showAddContact = selectedConversation != null &&
                    !selectedConversation.isGroup &&
                    !selectedConversation.hasContact

                // Check if any selected conversation can be pinned
                val selectedConvos = conversations.filter { it.guid in selectedConversations }
                val canPinAny = selectedConvos.any { it.hasContact || it.isPinned }

                // Calculate total selectable count (only non-pinned conversations)
                val selectableConversations = conversations.filter { !it.isPinned }
                val totalSelectableCount = selectableConversations.size

                // Determine if majority of selected conversations are unread
                val unreadCount = selectedConvos.count { it.unreadCount > 0 }
                val majorityUnread = unreadCount > selectedConvos.size / 2

                SelectionModeHeader(
                    selectedCount = selectedConversations.size,
                    totalSelectableCount = totalSelectableCount,
                    majorityUnread = majorityUnread,
                    onClose = onSelectionClose,
                    onSelectAll = {
                        val allSelectableGuids = selectableConversations.map { it.guid }.toSet()
                        val newSelection = if (selectedConversations.size >= totalSelectableCount) {
                            emptySet() // Deselect all
                        } else {
                            allSelectableGuids // Select all
                        }
                        onSelectAllToggle(newSelection)
                    },
                    onPin = {
                        selectedConversations.forEach { onPin(it) }
                        onSelectionClose()
                    },
                    onSnooze = onSnooze,
                    onArchive = onArchive,
                    onDelete = onDelete,
                    onMarkAsRead = onMarkAsRead,
                    onMarkAsUnread = onMarkAsUnread,
                    onBlock = onBlock,
                    onAddContact = if (showAddContact && selectedConversation != null) {
                        {
                            val intent = Intent(
                                Intent.ACTION_INSERT,
                                ContactsContract.Contacts.CONTENT_URI
                            ).apply {
                                putExtra(
                                    ContactsContract.Intents.Insert.PHONE,
                                    selectedConversation.address
                                )
                            }
                            context.startActivity(intent)
                            onSelectionClose()
                        }
                    } else null,
                    isPinEnabled = canPinAny
                )
            } else {
                ConversationsTopBar(
                    useSimpleAppTitle = useSimpleAppTitle,
                    conversationFilter = conversationFilter,
                    categoryFilter = categoryFilter,
                    categorizationEnabled = categorizationEnabled,
                    enabledCategories = enabledCategories,
                    hasSettingsWarning = hasSettingsWarning,
                    totalUnreadCount = totalUnreadCount,
                    showUnreadCountInHeader = showUnreadCountInHeader,
                    onFilterSelected = onFilterSelected,
                    onCategorySelected = onCategorySelected,
                    onSearchClick = onSearchClick,
                    onSettingsClick = onSettingsClick,
                    onTitleClick = {
                        coroutineScope.launch {
                            listState.animateScrollToItem(0)
                        }
                        onTitleClick()
                    },
                    onUnreadBadgeClick = {
                        coroutineScope.launch {
                            listState.animateScrollToItem(0)
                        }
                        onUnreadBadgeClick()
                    }
                )
            }
        }
    }
}
