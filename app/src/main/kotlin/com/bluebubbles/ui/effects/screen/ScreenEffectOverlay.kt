package com.bluebubbles.ui.effects.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.bluebubbles.ui.effects.MessageEffect

/**
 * Full-screen overlay for displaying screen effects.
 * Renders above all other content in the chat.
 *
 * @param effect The screen effect to display, or null for no effect
 * @param messageText The message text (used for Echo effect)
 * @param messageBounds The bounds of the message bubble (used for Spotlight/Echo)
 * @param onEffectComplete Callback when the effect animation completes
 */
@Composable
fun ScreenEffectOverlay(
    effect: MessageEffect.Screen?,
    messageText: String? = null,
    messageBounds: Rect? = null,
    onEffectComplete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (effect == null) return

    Box(modifier = modifier.fillMaxSize()) {
        when (effect) {
            MessageEffect.Screen.Balloons -> {
                BalloonsEffect(onComplete = onEffectComplete)
            }

            MessageEffect.Screen.Confetti -> {
                ConfettiEffect(onComplete = onEffectComplete)
            }

            MessageEffect.Screen.Fireworks -> {
                FireworksEffect(onComplete = onEffectComplete)
            }

            MessageEffect.Screen.Celebration -> {
                CelebrationEffect(onComplete = onEffectComplete)
            }

            MessageEffect.Screen.Hearts -> {
                HeartsEffect(onComplete = onEffectComplete)
            }

            MessageEffect.Screen.Lasers -> {
                LasersEffect(onComplete = onEffectComplete)
            }

            MessageEffect.Screen.Spotlight -> {
                SpotlightEffect(
                    messageBounds = messageBounds,
                    onComplete = onEffectComplete
                )
            }

            MessageEffect.Screen.Echo -> {
                EchoEffect(
                    messageText = messageText ?: "",
                    messageBounds = messageBounds,
                    onComplete = onEffectComplete
                )
            }
        }
    }
}
