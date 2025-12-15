package com.bothbubbles.ui.chat.state

import androidx.compose.runtime.Stable
import com.bothbubbles.ui.effects.MessageEffect

/**
 * State owned by ChatEffectsDelegate.
 * Contains all message effect playback state.
 */
@Stable
data class EffectsState(
    val activeScreenEffect: ScreenEffectData? = null,
    val autoPlayEffects: Boolean = true,
    val replayOnScroll: Boolean = false,
    val reduceMotion: Boolean = false
)

/**
 * Data for an active screen effect.
 */
@Stable
data class ScreenEffectData(
    val effect: MessageEffect.Screen,
    val messageGuid: String,
    val messageText: String?
)
