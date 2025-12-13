package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.effects.MessageEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate responsible for message effect playback (bubble and screen effects).
 * Manages effect queue and playback state.
 */
class ChatEffectsDelegate @Inject constructor(
    private val messageRepository: MessageRepository
) {

    private lateinit var scope: CoroutineScope

    // Screen effect state and queue
    data class ScreenEffectState(
        val effect: MessageEffect.Screen,
        val messageGuid: String,
        val messageText: String?
    )

    private val _activeScreenEffect = MutableStateFlow<ScreenEffectState?>(null)
    val activeScreenEffect: StateFlow<ScreenEffectState?> = _activeScreenEffect.asStateFlow()

    private val screenEffectQueue = mutableListOf<ScreenEffectState>()
    private var isPlayingScreenEffect = false

    /**
     * Initialize the delegate.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
    }

    /**
     * Called when a bubble effect animation completes.
     */
    fun onBubbleEffectCompleted(messageGuid: String) {
        scope.launch {
            messageRepository.markEffectPlayed(messageGuid)
        }
    }

    /**
     * Trigger a screen effect for a message.
     * Effects are queued to prevent overlapping animations.
     */
    fun triggerScreenEffect(message: MessageUiModel) {
        val effect = MessageEffect.fromStyleId(message.expressiveSendStyleId) as? MessageEffect.Screen ?: return
        val state = ScreenEffectState(effect, message.guid, message.text)
        screenEffectQueue.add(state)
        if (!isPlayingScreenEffect) playNextScreenEffect()
    }

    /**
     * Called when a screen effect animation completes.
     */
    fun onScreenEffectCompleted() {
        val state = _activeScreenEffect.value
        if (state != null) {
            scope.launch {
                messageRepository.markEffectPlayed(state.messageGuid)
            }
        }
        _activeScreenEffect.value = null
        isPlayingScreenEffect = false
        playNextScreenEffect()
    }

    /**
     * Play the next screen effect in the queue.
     */
    private fun playNextScreenEffect() {
        val next = screenEffectQueue.removeFirstOrNull()
        if (next != null) {
            isPlayingScreenEffect = true
            _activeScreenEffect.value = next
        } else {
            isPlayingScreenEffect = false
        }
    }
}
