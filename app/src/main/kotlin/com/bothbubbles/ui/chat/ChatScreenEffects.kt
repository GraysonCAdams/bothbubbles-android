package com.bothbubbles.ui.chat

import android.net.Uri
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay

/**
 * Consolidated side-effect handler for ChatScreen.
 *
 * Houses LaunchedEffect blocks that handle:
 * - Navigation and deep-link processing
 * - External input handling (captured photos, shared content)
 * - Scroll position restoration
 * - Error display via snackbar
 *
 * Created as part of Stage 3 refactoring to reduce ChatScreen.kt size.
 */
@Composable
fun ChatScreenEffects(
    viewModel: ChatViewModel,
    state: ChatScreenState,
    chatGuid: String,

    // Message list for scroll operations
    messages: List<com.bothbubbles.ui.components.message.MessageUiModel>,

    // Scroll position parameters
    effectiveScrollPosition: Pair<Int, Int>,
    onScrollPositionRestored: () -> Unit,

    // External inputs
    capturedPhotoUri: Uri?,
    editedAttachmentUri: Uri?,
    editedAttachmentCaption: String?,
    originalAttachmentUri: Uri?,
    sharedText: String?,
    sharedUris: List<Uri>,
    targetMessageGuid: String?,
    activateSearch: Boolean,

    // Callbacks
    onBackClick: () -> Unit,
    onCapturedPhotoHandled: () -> Unit,
    onEditedAttachmentHandled: () -> Unit,
    onSharedContentHandled: () -> Unit,
    onSearchActivated: () -> Unit,

    // Error state
    error: String?,
    onClearError: () -> Unit,

    // Operations state for chat deletion
    chatDeleted: Boolean
) {
    // Restore scroll position after messages load (if we have state to restore)
    LaunchedEffect(messages.isNotEmpty(), effectiveScrollPosition) {
        if (!state.scrollRestored && messages.isNotEmpty() &&
            (effectiveScrollPosition.first > 0 || effectiveScrollPosition.second > 0)) {
            // Scroll to restored position after messages have loaded
            state.listState.scrollToItem(effectiveScrollPosition.first, effectiveScrollPosition.second)
            state.scrollRestored = true
            onScrollPositionRestored()
        }
    }

    // Handle notification deep-link: scroll to target message and highlight it
    // Uses paging-aware jumpToMessage instead of indexOfFirst for sparse loading support
    LaunchedEffect(targetMessageGuid) {
        if (targetMessageGuid != null && !state.targetMessageHandled) {
            // Use paging-aware jump which loads data if needed
            val position = viewModel.jumpToMessage(targetMessageGuid)
            if (position != null) {
                // Small delay to let data load
                delay(100)
                // Scroll with offset so message isn't at the very top edge
                // In reversed layout, negative offset moves item down (away from visual top)
                state.listState.animateScrollToItem(position, scrollOffset = -100)
                // Trigger highlight animation after scroll
                viewModel.highlightMessage(targetMessageGuid)
                state.targetMessageHandled = true
            }
        }
    }

    // Handle scroll-to-message events from thread overlay
    // Uses paging-aware jumpToMessage instead of indexOfFirst for sparse loading support
    LaunchedEffect(Unit) {
        viewModel.thread.scrollToGuid.collect { guid ->
            // Use paging-aware jump which loads data if needed
            val position = viewModel.jumpToMessage(guid)
            if (position != null) {
                // Small delay to let data load
                delay(50)
                state.listState.animateScrollToItem(position)
            }
        }
    }

    // Scroll-to-safety: When showing tapback menu, ensure message is visible and centered
    LaunchedEffect(state.selectedMessageForTapback?.guid) {
        val message = state.selectedMessageForTapback ?: return@LaunchedEffect
        val messageIndex = messages.indexOfFirst { it.guid == message.guid }
        if (messageIndex < 0) return@LaunchedEffect

        // Get current viewport info
        val layoutInfo = state.listState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val viewportHeight = layoutInfo.viewportSize.height

        // Check if message is currently visible
        val isVisible = visibleItems.any { it.index == messageIndex }

        // Calculate safe zone: center third of viewport
        // With reverseLayout=true, we want to position message in center
        if (!isVisible) {
            // Message not visible - scroll to center it
            state.isScrollToSafetyInProgress = true
            val centerOffset = -(viewportHeight / 3)
            state.listState.animateScrollToItem(messageIndex, scrollOffset = centerOffset)
            state.isScrollToSafetyInProgress = false
        } else {
            // Message is visible - check if it's in safe zone
            val visibleItem = visibleItems.find { it.index == messageIndex }
            if (visibleItem != null) {
                val itemTop = visibleItem.offset
                val itemBottom = visibleItem.offset + visibleItem.size
                val safeTop = viewportHeight / 4
                val safeBottom = viewportHeight * 3 / 4

                // If message extends outside safe zone, scroll to center
                if (itemTop < safeTop || itemBottom > safeBottom) {
                    state.isScrollToSafetyInProgress = true
                    val centerOffset = -(viewportHeight / 3)
                    state.listState.animateScrollToItem(messageIndex, scrollOffset = centerOffset)
                    state.isScrollToSafetyInProgress = false
                }
            }
        }
    }

    // Dismiss tapback menu when user scrolls (but not during programmatic scroll-to-safety)
    LaunchedEffect(state.selectedMessageForTapback) {
        if (state.selectedMessageForTapback != null) {
            // Wait for scroll-to-safety to complete before enabling dismiss-on-scroll
            snapshotFlow { state.listState.isScrollInProgress to state.isScrollToSafetyInProgress }
                .collect { (isScrolling, isScrollToSafety) ->
                    if (isScrolling && !isScrollToSafety) {
                        state.clearTapbackSelection()
                    }
                }
        }
    }

    // Show snackbar when error occurs
    LaunchedEffect(error) {
        error?.let { errorMessage ->
            state.snackbarHostState.showSnackbar(
                message = errorMessage,
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
            onClearError()
        }
    }

    // Handle chat deletion - navigate back
    LaunchedEffect(chatDeleted) {
        if (chatDeleted) {
            onBackClick()
        }
    }

    // Handle captured photo from in-app camera
    LaunchedEffect(capturedPhotoUri) {
        capturedPhotoUri?.let { uri ->
            viewModel.composer.addAttachment(uri)
            onCapturedPhotoHandled()
        }
    }

    // Handle edited attachment
    LaunchedEffect(editedAttachmentUri) {
        if (editedAttachmentUri != null) {
            viewModel.composer.onAttachmentEdited(
                originalUri = originalAttachmentUri ?: editedAttachmentUri,
                editedUri = editedAttachmentUri,
                caption = editedAttachmentCaption
            )
            onEditedAttachmentHandled()
        }
    }

    // Handle shared content from share picker
    LaunchedEffect(sharedText, sharedUris) {
        // Add shared URIs as attachments
        if (sharedUris.isNotEmpty()) {
            sharedUris.forEach { uri ->
                viewModel.composer.addAttachment(uri)
            }
        }
        // Set shared text as draft
        if (sharedText != null) {
            viewModel.updateDraft(sharedText)
        }
        // Mark shared content as handled
        if (sharedText != null || sharedUris.isNotEmpty()) {
            onSharedContentHandled()
        }
    }

    // Handle search activation from ChatDetails screen
    LaunchedEffect(activateSearch) {
        if (activateSearch) {
            viewModel.search.activateSearch()
            onSearchActivated()
        }
    }
}
