package com.bothbubbles.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.bothbubbles.services.contacts.ContactData
import com.bothbubbles.ui.components.message.MessageUiModel
import kotlinx.coroutines.CoroutineScope

/**
 * Hoisted state container for ChatScreen.
 *
 * Consolidates all local UI state (dialog visibility, selection states, layout measurements)
 * into a single @Stable class to reduce ChatScreen.kt size and improve state management.
 *
 * Created as part of Stage 3 refactoring.
 */
@Stable
class ChatScreenState(
    val listState: LazyListState,
    val snackbarHostState: SnackbarHostState,
    val coroutineScope: CoroutineScope
) {
    // ===== Dialog Visibility =====
    var showDeleteDialog by mutableStateOf(false)
    var showBlockDialog by mutableStateOf(false)
    var showVideoCallDialog by mutableStateOf(false)
    var showSmsBlockedDialog by mutableStateOf(false)
    var showDiscordSetupDialog by mutableStateOf(false)
    var showDiscordHelpOverlay by mutableStateOf(false)
    var showAttachmentPicker by mutableStateOf(false)
    var showEmojiPicker by mutableStateOf(false)
    var showScheduleDialog by mutableStateOf(false)
    var showVCardOptionsDialog by mutableStateOf(false)
    var showEffectPicker by mutableStateOf(false)
    var showQualitySheet by mutableStateOf(false)
    var showForwardDialog by mutableStateOf(false)
    var showCaptureTypeSheet by mutableStateOf(false)
    /** Show confirmation dialog for deleting selected messages */
    var showDeleteMessagesDialog by mutableStateOf(false)

    // ===== Selection / Data =====
    var pendingContactData by mutableStateOf<ContactData?>(null)
    var selectedMessageForTapback by mutableStateOf<MessageUiModel?>(null)
    var selectedMessageBounds by mutableStateOf<Rect?>(null)
    var selectedMessageForRetry by mutableStateOf<MessageUiModel?>(null)
    var canRetrySmsForMessage by mutableStateOf(false)
    var messageToForward by mutableStateOf<MessageUiModel?>(null)
    /** List of message GUIDs to forward (for multi-select forward) */
    var messagesToForward by mutableStateOf<List<String>>(emptyList())
    var swipingMessageGuid by mutableStateOf<String?>(null)
    /** Message selected for viewing sender contact details (group chat avatar click) */
    var selectedSenderMessage by mutableStateOf<MessageUiModel?>(null)

    // ===== Multi-Message Selection =====
    /** Set of message GUIDs currently selected for bulk operations */
    var selectedMessageGuids by mutableStateOf(setOf<String>())
        private set

    /** True when one or more messages are selected (selection mode is active) */
    val isMessageSelectionMode: Boolean
        get() = selectedMessageGuids.isNotEmpty()

    // ===== Layout =====
    /** Height of the top bar in pixels */
    var topBarHeightPx by mutableFloatStateOf(0f)

    /** Base height of the bottom bar (minimum, excluding keyboard/panels) */
    var bottomBarBaseHeightPx by mutableFloatStateOf(0f)

    var composerHeightPx by mutableFloatStateOf(0f)
    var sendButtonBounds by mutableStateOf(Rect.Zero)

    // ===== Scroll State =====
    var scrollRestored by mutableStateOf(false)
    var targetMessageHandled by mutableStateOf(false)
    var isScrollToSafetyInProgress by mutableStateOf(false)

    // ===== Tapback Focus Scroll Preservation =====
    /** Scroll position before tapback menu opened (for restoration on close) */
    var preFocusScrollIndex by mutableStateOf<Int?>(null)
        private set
    var preFocusScrollOffset by mutableStateOf<Int?>(null)
        private set

    /** Whether we've scrolled to accommodate the menu (need to restore on close) */
    var didScrollForFocus by mutableStateOf(false)
        private set

    // ===== Message Effects & Animation =====
    // Track processed screen effects this session to avoid re-triggering
    val processedEffectMessages = mutableSetOf<String>()

    // Track revealed invisible ink messages (resets when leaving chat)
    var revealedInvisibleInkMessages by mutableStateOf(setOf<String>())
        private set

    // Track messages that have been animated (prevents re-animation on recompose)
    val animatedMessageGuids = mutableSetOf<String>()

    // ===== Effect tracking =====
    fun markEffectProcessed(guid: String): Boolean = processedEffectMessages.add(guid)
    fun isEffectProcessed(guid: String): Boolean = guid in processedEffectMessages

    // ===== Animation tracking =====
    fun markMessageAnimated(guid: String): Boolean = animatedMessageGuids.add(guid)
    fun isMessageAnimated(guid: String): Boolean = guid in animatedMessageGuids
    fun markInitialMessagesAnimated(guids: Collection<String>) {
        animatedMessageGuids.addAll(guids)
    }

    // ===== Invisible ink tracking =====
    fun revealInvisibleInk(guid: String) {
        revealedInvisibleInkMessages = revealedInvisibleInkMessages + guid
    }
    fun concealInvisibleInk(guid: String) {
        revealedInvisibleInkMessages = revealedInvisibleInkMessages - guid
    }
    fun isInvisibleInkRevealed(guid: String): Boolean = guid in revealedInvisibleInkMessages

    // Legacy method for backward compatibility (will be removed in sequential phase)
    fun toggleInvisibleInk(guid: String, revealed: Boolean) {
        if (revealed) revealInvisibleInk(guid) else concealInvisibleInk(guid)
    }

    /** Saves scroll position before opening tapback menu */
    fun saveFocusScrollPosition(index: Int, offset: Int) {
        preFocusScrollIndex = index
        preFocusScrollOffset = offset
    }

    /** Marks that we scrolled to accommodate the menu */
    fun markScrolledForFocus() {
        didScrollForFocus = true
    }

    /** Clears the tapback selection state and scroll preservation */
    fun clearTapbackSelection() {
        selectedMessageForTapback = null
        selectedMessageBounds = null
        preFocusScrollIndex = null
        preFocusScrollOffset = null
        didScrollForFocus = false
    }

    /** Clears the retry selection state */
    fun clearRetrySelection() {
        selectedMessageForRetry = null
        canRetrySmsForMessage = false
    }

    /** Clears the forward message state */
    fun clearForwardState() {
        messageToForward = null
        showForwardDialog = false
    }

    /** Clears pending contact data */
    fun clearPendingContact() {
        pendingContactData = null
        showVCardOptionsDialog = false
    }

    // ===== Multi-Message Selection Methods =====

    /** Enters selection mode with the specified message initially selected */
    fun enterMessageSelectionMode(initialGuid: String) {
        // Clear any tapback/retry selection when entering selection mode
        clearTapbackSelection()
        clearRetrySelection()
        selectedMessageGuids = setOf(initialGuid)
    }

    /** Toggles selection state for a message */
    fun toggleMessageSelection(guid: String) {
        selectedMessageGuids = if (guid in selectedMessageGuids) {
            selectedMessageGuids - guid
        } else {
            selectedMessageGuids + guid
        }
    }

    /** Clears all message selection and exits selection mode */
    fun clearMessageSelection() {
        selectedMessageGuids = emptySet()
    }

    /** Selects all provided message GUIDs */
    fun selectAllMessages(guids: List<String>) {
        selectedMessageGuids = guids.toSet()
    }
}

/**
 * Creates and remembers a [ChatScreenState] instance with proper initialization.
 *
 * @param initialScrollPosition Initial scroll position index (from navigation state or cache)
 * @param initialScrollOffset Initial scroll offset within the item
 * @param cachedScrollPosition Cached scroll position from LRU cache (fallback if no nav state)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberChatScreenState(
    initialScrollPosition: Int = 0,
    initialScrollOffset: Int = 0,
    cachedScrollPosition: Pair<Int, Int>? = null
): ChatScreenState {
    // Determine effective scroll position: navigation state takes priority, then LRU cache
    val effectiveScrollPosition = if (initialScrollPosition > 0 || initialScrollOffset > 0) {
        Pair(initialScrollPosition, initialScrollOffset)
    } else {
        cachedScrollPosition ?: Pair(0, 0)
    }

    // Cache window keeps ~50 messages composed beyond viewport (matching fossify-reference)
    // ahead = prefetch before visible, behind = retain after scrolling past
    val cacheWindow = remember { LazyLayoutCacheWindow(ahead = 1000.dp, behind = 2000.dp) }

    val listState = rememberLazyListState(
        cacheWindow = cacheWindow,
        initialFirstVisibleItemIndex = effectiveScrollPosition.first,
        initialFirstVisibleItemScrollOffset = effectiveScrollPosition.second
    )

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    return remember(listState, snackbarHostState, coroutineScope) {
        ChatScreenState(
            listState = listState,
            snackbarHostState = snackbarHostState,
            coroutineScope = coroutineScope
        ).apply {
            // Mark if scroll position needs restoration
            scrollRestored = effectiveScrollPosition.first == 0 && effectiveScrollPosition.second == 0
        }
    }
}
