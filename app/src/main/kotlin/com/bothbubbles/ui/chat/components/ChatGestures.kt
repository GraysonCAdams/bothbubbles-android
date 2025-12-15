package com.bothbubbles.ui.chat.components

import androidx.compose.ui.Modifier

/**
 * Chat-level gesture handling utilities.
 *
 * Note: Message-level gestures (swipe-to-reply, date reveal) are handled by:
 * - [com.bothbubbles.ui.components.message.MessageSwipeContainer]
 * - [com.bothbubbles.ui.components.message.MessageSwipeGestures]
 *
 * Send button gestures (mode toggle via vertical swipe) are handled by:
 * - [com.bothbubbles.ui.chat.composer.gestures.SendModeGestureHandler]
 *
 * This file is reserved for any future chat-screen-level gesture handling,
 * such as pull-to-refresh or chat-wide swipe navigation.
 */

/**
 * Modifier for handling chat-level gestures.
 *
 * Currently a pass-through as complex gestures are handled at component level.
 * Reserved for future chat-wide gesture behavior (e.g., pull-to-refresh).
 *
 * @param onPullToRefresh Callback when user performs pull-to-refresh gesture (reserved)
 */
fun Modifier.chatGestures(
    onPullToRefresh: (() -> Unit)? = null
): Modifier {
    // Chat-level gestures are currently handled at component level:
    // - Swipe-to-reply: MessageSwipeContainer
    // - Send mode toggle: SendModeGestureHandler
    // - Scroll: LazyColumn built-in handling
    //
    // This modifier is reserved for future chat-wide gesture needs.
    return this
}
