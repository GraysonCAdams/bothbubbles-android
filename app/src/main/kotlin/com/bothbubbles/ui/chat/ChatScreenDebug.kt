package com.bothbubbles.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.components.message.MessageUiModel

/**
 * Debug flag to enable/disable recomposition tracking.
 * Set to false for production builds to eliminate overhead.
 */
private const val ENABLE_RECOMPOSITION_DEBUG = false

/**
 * Tracks and logs what state changes caused a recomposition.
 *
 * This is a debug utility to help identify performance issues from
 * unnecessary recompositions. It logs state changes to Logcat under
 * the "CascadeDebug" tag.
 *
 * Usage: Call this from inside ChatScreen after collecting all state.
 *
 * PERF: draftText is collected internally only when debugging is enabled,
 * to avoid causing ChatScreen recomposition on every keystroke.
 *
 * @param viewModel ChatViewModel to collect draftText from (only when debugging enabled)
 * @param messages Current message list
 * @param isSending Whether a message is currently sending
 * @param smartReplyCount Number of smart reply suggestions
 * @param attachmentCount Number of pending attachments
 * @param isLoading Whether the chat is loading
 * @param canLoadMore Whether more messages can be loaded
 * @param uiStateHash Identity hash of the UI state object
 */
@Composable
fun ChatScreenRecompositionDebug(
    viewModel: ChatViewModel,
    messages: List<MessageUiModel>,
    isSending: Boolean,
    smartReplyCount: Int,
    attachmentCount: Int,
    isLoading: Boolean,
    canLoadMore: Boolean,
    uiStateHash: Int
) {
    if (!ENABLE_RECOMPOSITION_DEBUG) return

    // Only collect draftText when debugging is enabled to avoid performance impact
    val draftText by viewModel.composer.draftText.collectAsStateWithLifecycle()

    val recomposeCount = remember { mutableIntStateOf(0) }
    val prevMessagesSize = remember { mutableIntStateOf(-1) }
    val prevDraftText = remember { mutableStateOf<String?>(null) }
    val prevIsSending = remember { mutableStateOf<Boolean?>(null) }
    val prevFirstMsgGuid = remember { mutableStateOf<String?>(null) }
    val prevSmartReplySize = remember { mutableIntStateOf(-1) }
    val prevAttachmentCount = remember { mutableIntStateOf(-1) }
    val prevIsLoading = remember { mutableStateOf<Boolean?>(null) }
    val prevCanLoadMore = remember { mutableStateOf<Boolean?>(null) }
    val prevUiStateHash = remember { mutableIntStateOf(0) }

    SideEffect {
        recomposeCount.intValue++
        val changes = mutableListOf<String>()

        if (prevMessagesSize.intValue != messages.size) {
            changes.add("messages.size: ${prevMessagesSize.intValue} → ${messages.size}")
            prevMessagesSize.intValue = messages.size
        }
        if (prevDraftText.value != draftText) {
            changes.add("draftText: '${prevDraftText.value?.take(10)}' → '${draftText.take(10)}'")
            prevDraftText.value = draftText
        }
        if (prevIsSending.value != isSending) {
            changes.add("isSending: ${prevIsSending.value} → $isSending")
            prevIsSending.value = isSending
        }
        val currentFirstGuid = messages.firstOrNull()?.guid
        if (prevFirstMsgGuid.value != currentFirstGuid) {
            changes.add("firstMsgGuid: ${prevFirstMsgGuid.value?.takeLast(8)} → ${currentFirstGuid?.takeLast(8)}")
            prevFirstMsgGuid.value = currentFirstGuid
        }
        if (prevSmartReplySize.intValue != smartReplyCount) {
            changes.add("smartReplies: ${prevSmartReplySize.intValue} → $smartReplyCount")
            prevSmartReplySize.intValue = smartReplyCount
        }
        if (prevAttachmentCount.intValue != attachmentCount) {
            changes.add("attachmentCount: ${prevAttachmentCount.intValue} → $attachmentCount")
            prevAttachmentCount.intValue = attachmentCount
        }
        if (prevIsLoading.value != isLoading) {
            changes.add("isLoading: ${prevIsLoading.value} → $isLoading")
            prevIsLoading.value = isLoading
        }
        if (prevCanLoadMore.value != canLoadMore) {
            changes.add("canLoadMore: ${prevCanLoadMore.value} → $canLoadMore")
            prevCanLoadMore.value = canLoadMore
        }
        // Track if uiState object changed even if tracked fields didn't
        if (prevUiStateHash.intValue != uiStateHash && prevUiStateHash.intValue != 0) {
            changes.add("uiState.hash: ${prevUiStateHash.intValue} → $uiStateHash")
        }
        prevUiStateHash.intValue = uiStateHash

        Timber.tag("CascadeDebug").d(
            "[RECOMPOSE #${recomposeCount.intValue}] ${if (changes.isEmpty()) "NO TRACKED CHANGES" else changes.joinToString(", ")}"
        )
    }
}
