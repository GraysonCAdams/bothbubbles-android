package com.bothbubbles.ui.conversations.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.bothbubbles.services.categorization.MessageCategory
import com.bothbubbles.ui.components.common.ConversationListSkeleton
import com.bothbubbles.ui.components.conversation.SwipeActionType
import com.bothbubbles.ui.components.dialogs.ContactInfo
import com.bothbubbles.ui.conversations.ConversationFilter
import com.bothbubbles.ui.conversations.ConversationScreenState
import com.bothbubbles.ui.conversations.ConversationUiModel
import com.bothbubbles.ui.conversations.ConversationsList
import com.bothbubbles.ui.conversations.EmptyCategoryState
import com.bothbubbles.ui.conversations.EmptyConversationsState
import com.bothbubbles.ui.conversations.EmptyFilterState
import com.bothbubbles.ui.conversations.PinnedDragOverlay
import com.bothbubbles.ui.conversations.delegates.SelectionState
import com.bothbubbles.ui.components.conversation.SwipeConfig
import timber.log.Timber

/**
 * Main content area for conversations list.
 *
 * Handles:
 * - Loading, empty, and content states
 * - Conversation filtering by status and category
 * - Pull-to-search indicator
 * - Pinned conversation drag overlay
 * - Empty state messaging
 */
@Composable
fun ConversationMainContent(
    conversations: List<ConversationUiModel>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    showReconnectingIndicator: Boolean,
    searchQuery: String,
    conversationFilter: ConversationFilter,
    categoryFilter: MessageCategory?,
    swipeConfig: SwipeConfig,
    selectionState: SelectionState,
    isSelectionMode: Boolean,
    listState: LazyListState,
    onConversationClick: (chatGuid: String, mergedGuids: List<String>) -> Unit,
    onConversationLongClick: (guid: String) -> Unit,
    onAvatarClick: (contactInfo: ContactInfo) -> Unit,
    onSwipeAction: (chatGuid: String, action: SwipeActionType) -> Unit,
    onPinReorder: (reorderedGuids: List<String>) -> Unit,
    onUnpin: (guid: String) -> Unit,
    onClearConversationFilter: () -> Unit,
    onClearCategoryFilter: () -> Unit,
    padding: PaddingValues,
    bumpResetKey: Int = 0,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val cardShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)

    Surface(
        modifier = modifier
            .fillMaxSize()
            .padding(top = padding.calculateTopPadding()),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surface
    ) {
        // Determine screen state for animated transitions
        val screenState = when {
            isLoading -> ConversationScreenState.LOADING
            conversations.isEmpty() -> ConversationScreenState.EMPTY
            else -> ConversationScreenState.CONTENT
        }

        AnimatedContent(
            targetState = screenState,
            transitionSpec = {
                fadeIn(tween(250)) togetherWith fadeOut(tween(200))
            },
            label = "conversationStateTransition"
        ) { state ->
            when (state) {
                ConversationScreenState.LOADING -> {
                    ConversationListSkeleton(
                        count = 8,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                ConversationScreenState.EMPTY -> {
                    EmptyConversationsState(
                        modifier = Modifier.fillMaxSize(),
                        isSearching = searchQuery.isNotBlank()
                    )
                }
                ConversationScreenState.CONTENT -> {
                    // Log incoming conversations for debugging
                    Timber.d("ConversationMainContent: Received ${conversations.size} conversations, filter=$conversationFilter")

                    // Check for duplicates by contactKey BEFORE filtering
                    val duplicatesByContactKey = conversations.filter { !it.isGroup && it.contactKey.isNotBlank() }
                        .groupBy { it.contactKey }
                        .filter { it.value.size > 1 }
                    if (duplicatesByContactKey.isNotEmpty()) {
                        Timber.e("ConversationMainContent: DUPLICATES IN INPUT: ${duplicatesByContactKey.map { (key, convs) ->
                            "$key -> ${convs.map { "${it.guid} (${it.displayName})" }}"
                        }}")
                    }

                    // Apply conversation filter
                    val filteredConversations = conversations.filter { conv ->
                        // Apply status filter first
                        val matchesStatus = when (conversationFilter) {
                            ConversationFilter.ALL -> !conv.isSpam
                            ConversationFilter.UNREAD -> !conv.isSpam && conv.unreadCount > 0
                            ConversationFilter.SPAM -> conv.isSpam
                            ConversationFilter.UNKNOWN_SENDERS -> !conv.isSpam && !conv.hasContact
                            ConversationFilter.KNOWN_SENDERS -> !conv.isSpam && conv.hasContact
                        }

                        // Apply category filter if set
                        val matchesCategory = categoryFilter?.let { category ->
                            conv.category?.equals(category.name, ignoreCase = true) == true
                        } ?: true

                        matchesStatus && matchesCategory
                    }

                    Timber.d("ConversationMainContent: After filter: ${filteredConversations.size} conversations")

                    // Show empty state if filter returns no results
                    val hasActiveFilter = conversationFilter != ConversationFilter.ALL || categoryFilter != null
                    val showFilterEmptyState = filteredConversations.isEmpty() && hasActiveFilter

                    if (showFilterEmptyState) {
                        if (categoryFilter != null) {
                            EmptyCategoryState(
                                category = categoryFilter,
                                onClearFilter = onClearCategoryFilter,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            EmptyFilterState(
                                filter = conversationFilter,
                                onClearFilter = onClearConversationFilter,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        val pinnedConversations = filteredConversations.filter { it.isPinned }
                            .sortedWith(compareBy<ConversationUiModel> { it.pinIndex }.thenByDescending { it.lastMessageTimestamp })
                        val regularConversations = filteredConversations.filter { !it.isPinned }

                        // State for dragged pin overlay
                        var draggedPinConversation by remember { mutableStateOf<ConversationUiModel?>(null) }
                        var draggedPinStartPosition by remember { mutableStateOf(Offset.Zero) }
                        var draggedPinOffset by remember { mutableStateOf(Offset.Zero) }
                        var isPinDragging by remember { mutableStateOf(false) }
                        val unpinThresholdPx = with(density) { 60.dp.toPx() }
                        var containerRootPosition by remember { mutableStateOf(Offset.Zero) }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { coordinates ->
                                    containerRootPosition = coordinates.positionInRoot()
                                }
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                // Main conversation list
                                ConversationsList(
                                    pinnedConversations = pinnedConversations,
                                    regularConversations = regularConversations,
                                    listState = listState,
                                    swipeConfig = swipeConfig,
                                    selectionState = selectionState,
                                    isSelectionMode = isSelectionMode,
                                    isLoadingMore = isLoadingMore,
                                    showReconnectingIndicator = showReconnectingIndicator,
                                    bumpResetKey = bumpResetKey,
                                    onConversationClick = onConversationClick,
                                    onConversationLongClick = onConversationLongClick,
                                    onAvatarClick = onAvatarClick,
                                    onSwipeAction = onSwipeAction,
                                    onPinReorder = onPinReorder,
                                    onUnpin = onUnpin,
                                    onDragOverlayStart = { conversation, position ->
                                        draggedPinConversation = conversation
                                        draggedPinStartPosition = position
                                        draggedPinOffset = Offset.Zero
                                        isPinDragging = true
                                    },
                                    onDragOverlayMove = { offset ->
                                        draggedPinOffset = offset
                                    },
                                    onDragOverlayEnd = {
                                        isPinDragging = false
                                        draggedPinConversation = null
                                        draggedPinOffset = Offset.Zero
                                    }
                                )
                            }

                            // Drag overlay - renders dragged pin on top of everything
                            PinnedDragOverlay(
                                conversation = draggedPinConversation,
                                isDragging = isPinDragging,
                                startPosition = draggedPinStartPosition,
                                dragOffset = draggedPinOffset,
                                containerRootPosition = containerRootPosition,
                                unpinThresholdPx = unpinThresholdPx
                            )
                        }
                    }
                }
            }
        }
    }
}
