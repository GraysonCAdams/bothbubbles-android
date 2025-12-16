package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.ui.chat.state.EffectsState
import com.bothbubbles.ui.chat.state.ScreenEffectData
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.effects.MessageEffect
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Delegate responsible for message effect playback (bubble and screen effects).
 * Manages effect queue and playback state.
 *
 * Uses AssistedInject to receive runtime parameters (scope) at construction time,
 * eliminating the need for a separate initialize() call.
 */
class ChatEffectsDelegate @AssistedInject constructor(
    private val messageRepository: MessageRepository,
    @Assisted private val scope: CoroutineScope
) {

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): ChatEffectsDelegate
    }

    // ============================================================================
    // CONSOLIDATED EFFECTS STATE
    // Single StateFlow containing all effects-related state for reduced recompositions.
    // ============================================================================
    private val _state = MutableStateFlow(EffectsState())
    val state: StateFlow<EffectsState> = _state.asStateFlow()

    private val screenEffectQueue = mutableListOf<ScreenEffectData>()
    private var isPlayingScreenEffect = false

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
        val effectData = ScreenEffectData(effect, message.guid, message.text)
        screenEffectQueue.add(effectData)
        if (!isPlayingScreenEffect) playNextScreenEffect()
    }

    /**
     * Called when a screen effect animation completes.
     */
    fun onScreenEffectCompleted() {
        val effectData = _state.value.activeScreenEffect
        if (effectData != null) {
            scope.launch {
                messageRepository.markEffectPlayed(effectData.messageGuid)
            }
        }
        _state.update { it.copy(activeScreenEffect = null) }
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
            _state.update { it.copy(activeScreenEffect = next) }
        } else {
            isPlayingScreenEffect = false
        }
    }

    // Settings update methods
    fun setAutoPlayEffects(enabled: Boolean) {
        _state.update { it.copy(autoPlayEffects = enabled) }
    }

    fun setReplayOnScroll(enabled: Boolean) {
        _state.update { it.copy(replayOnScroll = enabled) }
    }

    fun setReduceMotion(enabled: Boolean) {
        _state.update { it.copy(reduceMotion = enabled) }
    }
}
