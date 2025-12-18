package com.bothbubbles.ui.chat.state

import androidx.compose.runtime.Stable
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.TutorialState

/**
 * State owned by ChatConnectionDelegate.
 * Contains connection status, send modes, and availability information.
 */
@Stable
data class ChatConnectionState(
    val currentSendMode: ChatSendMode = ChatSendMode.SMS,
    val contactIMessageAvailable: Boolean? = null,
    val isCheckingIMessageAvailability: Boolean = false,
    val canToggleSendMode: Boolean = false,
    val showSendModeRevealAnimation: Boolean = false,
    val sendModeManuallySet: Boolean = false,
    val tutorialState: TutorialState = TutorialState.NOT_SHOWN,
    val counterpartSynced: Boolean = true,
    /** True when server disconnected and SMS fallback isn't available */
    val serverFallbackBlocked: Boolean = false
)
