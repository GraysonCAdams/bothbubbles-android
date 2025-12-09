package com.bothbubbles.ui.effects.bubble

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.bothbubbles.ui.effects.MessageEffect

/**
 * Wraps a message bubble with the appropriate effect animation.
 * Routes to the correct effect implementation based on the effect type.
 *
 * @param effect The bubble effect to apply, or null for no effect
 * @param isNewMessage Whether this is a newly received message (triggers animation)
 * @param isFromMe Whether the message is from the current user (affects animation direction)
 * @param onEffectComplete Callback when the effect animation completes
 * @param isInvisibleInkRevealed Whether invisible ink has been revealed
 * @param onInvisibleInkRevealChanged Callback when invisible ink reveal state changes
 * @param hasMedia Whether the message has media attachments (affects invisible ink behavior)
 * @param onMediaClickBlocked Callback when media click is blocked (invisible ink not revealed)
 * @param content The message bubble content to wrap
 */
@Composable
fun BubbleEffectWrapper(
    effect: MessageEffect.Bubble?,
    isNewMessage: Boolean,
    isFromMe: Boolean,
    modifier: Modifier = Modifier,
    onEffectComplete: () -> Unit = {},
    isInvisibleInkRevealed: Boolean = false,
    onInvisibleInkRevealChanged: (Boolean) -> Unit = {},
    hasMedia: Boolean = false,
    onMediaClickBlocked: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    when (effect) {
        MessageEffect.Bubble.Slam -> {
            SlamEffect(
                isNewMessage = isNewMessage,
                isFromMe = isFromMe,
                onEffectComplete = onEffectComplete,
                modifier = modifier,
                content = content
            )
        }

        MessageEffect.Bubble.Loud -> {
            LoudEffect(
                isNewMessage = isNewMessage,
                onEffectComplete = onEffectComplete,
                modifier = modifier,
                content = content
            )
        }

        MessageEffect.Bubble.Gentle -> {
            GentleEffect(
                isNewMessage = isNewMessage,
                onEffectComplete = onEffectComplete,
                modifier = modifier,
                content = content
            )
        }

        MessageEffect.Bubble.InvisibleInk -> {
            InvisibleInkEffect(
                isRevealed = isInvisibleInkRevealed,
                onRevealStateChanged = onInvisibleInkRevealChanged,
                modifier = modifier,
                hasMedia = hasMedia,
                onMediaClickBlocked = onMediaClickBlocked,
                content = content
            )
        }

        null -> {
            // No effect - render content directly
            content()
        }
    }
}
