package com.bothbubbles.ui.chat.state

import androidx.compose.runtime.Stable
import com.bothbubbles.ui.components.message.ThreadChain

/**
 * State owned by ChatThreadDelegate.
 * Contains thread overlay state for viewing message threads.
 */
@Stable
data class ThreadState(
    val threadOverlay: ThreadChain? = null
)
