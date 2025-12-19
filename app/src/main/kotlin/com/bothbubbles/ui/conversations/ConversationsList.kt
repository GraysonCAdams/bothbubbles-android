package com.bothbubbles.ui.conversations

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.common.staggeredEntrance
import com.bothbubbles.ui.components.conversation.SwipeActionType
import com.bothbubbles.ui.components.conversation.SwipeConfig
import com.bothbubbles.ui.components.conversation.SwipeableConversationTile
import com.bothbubbles.ui.components.dialogs.ContactInfo
import com.bothbubbles.util.HapticUtils

/**
 * Main conversations list with pinned section and regular conversations.
 *
 * Renders pinned conversations in a horizontal row at the top, followed by
 * a scrollable list of regular conversations with swipe actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationsList(
    pinnedConversations: List<ConversationUiModel>,
    regularConversations: List<ConversationUiModel>,
    listState: LazyListState,
    swipeConfig: SwipeConfig,
    selectedConversations: Set<String>,
    isSelectionMode: Boolean,
    isLoadingMore: Boolean,
    onConversationClick: (chatGuid: String, mergedGuids: List<String>) -> Unit,
    onConversationLongClick: (String) -> Unit,
    onAvatarClick: (ContactInfo) -> Unit,
    onSwipeAction: (chatGuid: String, action: SwipeActionType) -> Unit,
    onPinReorder: (List<String>) -> Unit,
    onUnpin: (String) -> Unit,
    onDragOverlayStart: (ConversationUiModel, Offset) -> Unit,
    onDragOverlayMove: (Offset) -> Unit,
    onDragOverlayEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 88.dp) // FAB clearance only
    ) {
        // Pinned section (iOS-style horizontal row)
        // Always show pinned at top - in selection mode they are view-only (not selectable)
        if (pinnedConversations.isNotEmpty()) {
            item(key = "pinned_section") {
                PinnedConversationsRow(
                    conversations = pinnedConversations,
                    onConversationClick = { conversation ->
                        // In selection mode, pinned items just navigate (not selectable)
                        onConversationClick(conversation.guid, conversation.mergedChatGuids)
                    },
                    onConversationLongClick = { guid ->
                        // Disable long-click selection in selection mode for pinned items
                        if (!isSelectionMode) {
                            onConversationLongClick(guid)
                        }
                    },
                    onUnpin = { guid ->
                        // Disable unpin action in selection mode
                        if (!isSelectionMode) {
                            onUnpin(guid)
                        }
                    },
                    onReorder = { reorderedGuids ->
                        // Disable reordering in selection mode
                        if (!isSelectionMode) {
                            onPinReorder(reorderedGuids)
                        }
                    },
                    onAvatarClick = { conversation ->
                        // Disable avatar popup in selection mode
                        if (!isSelectionMode) {
                            onAvatarClick(conversation.toContactInfo())
                        }
                    },
                    selectedConversations = emptySet(), // Never show selection state for pinned items
                    isSelectionMode = isSelectionMode,
                    onDragOverlayStart = onDragOverlayStart,
                    onDragOverlayMove = onDragOverlayMove,
                    onDragOverlayEnd = onDragOverlayEnd,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // Divider between pinned and regular
            item(key = "pinned_divider") {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
        }

        // Regular conversations with staggered entrance animation
        // Always use SwipeableConversationTile - disable gestures in selection mode for tree stability
        itemsIndexed(
            items = regularConversations,
            key = { _, conv -> conv.guid }
        ) { index, conversation ->
            // Memoized swipe config - disable PIN for unsaved contacts that aren't already pinned
            val conversationSwipeConfig = remember(
                conversation.hasContact,
                conversation.isPinned,
                swipeConfig
            ) {
                if (!conversation.hasContact && !conversation.isPinned) {
                    swipeConfig.copy(
                        leftAction = if (swipeConfig.leftAction == SwipeActionType.PIN) {
                            SwipeActionType.NONE
                        } else {
                            swipeConfig.leftAction
                        },
                        rightAction = if (swipeConfig.rightAction == SwipeActionType.PIN) {
                            SwipeActionType.NONE
                        } else {
                            swipeConfig.rightAction
                        }
                    )
                } else {
                    swipeConfig
                }
            }

            SwipeableConversationTile(
                isPinned = conversation.isPinned,
                isMuted = conversation.isMuted,
                isRead = conversation.unreadCount == 0,
                isSnoozed = conversation.isSnoozed,
                gesturesEnabled = !isSelectionMode, // Disable swipes in selection mode
                onSwipeAction = { action ->
                    onSwipeAction(conversation.guid, action)
                },
                swipeConfig = conversationSwipeConfig,
                modifier = Modifier
                    .staggeredEntrance(index)
                    .animateItem()
            ) { hasRoundedCorners ->
                GoogleStyleConversationTile(
                    conversation = conversation,
                    isSelected = conversation.guid in selectedConversations,
                    isSelectionMode = isSelectionMode,
                    hasRoundedCorners = hasRoundedCorners,
                    onClick = {
                        if (isSelectionMode) {
                            // Toggle selection in selection mode
                            onConversationLongClick(conversation.guid)
                        } else {
                            onConversationClick(conversation.guid, conversation.mergedChatGuids)
                        }
                    },
                    onLongClick = {
                        HapticUtils.onLongPress(haptic)
                        onConversationLongClick(conversation.guid)
                    },
                    onAvatarClick = {
                        HapticUtils.onTap(haptic)
                        onAvatarClick(conversation.toContactInfo())
                    }
                )
            }
        }

        // Loading indicator when fetching more conversations
        if (isLoadingMore) {
            item(key = "loading_indicator") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

/**
 * Convert a ConversationUiModel to ContactInfo for the quick actions popup.
 */
private fun ConversationUiModel.toContactInfo(): ContactInfo = ContactInfo(
    chatGuid = guid,
    displayName = displayName,
    rawDisplayName = rawDisplayName,
    avatarPath = avatarPath,
    address = address,
    isGroup = isGroup,
    participantNames = participantNames,
    hasContact = hasContact,
    hasInferredName = hasInferredName
)
