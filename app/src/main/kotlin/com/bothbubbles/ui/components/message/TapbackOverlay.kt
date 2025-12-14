package com.bothbubbles.ui.components.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Safe zone margin for boundary clamping
private val SafeZoneMargin = 16.dp

/**
 * Data class representing the bounds and metadata of a selected message.
 */
data class MessageBoundsInfo(
    val bounds: Rect,
    val isFromMe: Boolean,
    val guid: String
)

/**
 * Full-screen overlay for tapback/reaction menu with Material Design 3 styling.
 *
 * Features:
 * - Scrim overlay (32% opacity) to focus attention on the message
 * - Split UI: ReactionPill (top) + MessageActionMenu (bottom)
 * - Smart positioning based on message location on screen
 * - Dismissible by tapping the scrim
 *
 * Positioning Logic:
 * - If message is near top: reactions below message, actions below reactions
 * - If message is near bottom: actions above message, reactions above actions
 * - Default: reactions above message, actions below message
 *
 * Horizontal Alignment:
 * - Sent messages (isFromMe): align to end
 * - Received messages: align to start
 */
@Composable
fun TapbackOverlay(
    visible: Boolean,
    messageBounds: MessageBoundsInfo?,
    myReactions: Set<Tapback>,
    canReply: Boolean = false,
    canCopy: Boolean = true,
    canForward: Boolean = true,
    onDismiss: () -> Unit,
    onReactionSelected: (Tapback) -> Unit,
    onReply: () -> Unit = {},
    onCopy: () -> Unit = {},
    onForward: () -> Unit = {},
    onEmojiPickerClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    messageContent: @Composable BoxScope.() -> Unit = {}
) {
    // Decouple visibility from presence - track internal animation state
    var isPresent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Animation state
    val overlayScale = remember { Animatable(0f) }
    val overlayAlpha = remember { Animatable(0f) }
    val scrimAlpha = remember { Animatable(0f) }

    // Close with animation - awaits completion before dismissing
    val closeWithAnimation: suspend () -> Unit = {
        // Animate out in parallel
        scope.launch { scrimAlpha.animateTo(0f, tween(200)) }
        scope.launch { overlayAlpha.animateTo(0f, tween(150)) }
        overlayScale.animateTo(
            0f,
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
        // Now safe to dismiss
        isPresent = false
        onDismiss()
    }

    // Handle visibility changes with animations
    LaunchedEffect(visible, messageBounds) {
        if (visible && messageBounds != null) {
            isPresent = true
            // Entry animation - spring scale with fade
            launch { scrimAlpha.animateTo(0.32f, tween(200)) }
            launch { overlayAlpha.animateTo(1f, tween(150, delayMillis = 50)) }
            overlayScale.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        } else if (!visible && isPresent) {
            // Exit animation triggered externally
            closeWithAnimation()
        }
    }

    // Don't render if not present
    if (!isPresent || messageBounds == null) return

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }
    val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
    val safeMarginPx = with(density) { SafeZoneMargin.toPx() }

    // Calculate positioning
    val positioning = remember(messageBounds, screenHeight) {
        calculatePositioning(
            messageBounds = messageBounds.bounds,
            screenHeight = screenHeight,
            density = density.density
        )
    }

    // Reaction pill dimensions for clamping
    val reactionPillWidth = with(density) { 280.dp.toPx() }
    val actionMenuWidth = with(density) { 180.dp.toPx() }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Scrim background - clickable to dismiss with animation
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha.value)
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { scope.launch { closeWithAnimation() } }
                )
        )

        // Message content (elevated/highlighted)
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = messageBounds.bounds.left.roundToInt(),
                        y = messageBounds.bounds.top.roundToInt()
                    )
                }
        ) {
            messageContent()
        }

        // Reaction Pill with boundary clamping
        val reactionPillAlignment = if (messageBounds.isFromMe) {
            Alignment.TopEnd
        } else {
            Alignment.TopStart
        }

        // Calculate clamped X position for reaction pill
        val reactionPillX = run {
            val idealX = if (messageBounds.isFromMe) {
                messageBounds.bounds.right - reactionPillWidth
            } else {
                messageBounds.bounds.left
            }
            // Clamp to safe zones
            idealX.coerceIn(safeMarginPx, screenWidth - reactionPillWidth - safeMarginPx)
        }

        Box(
            modifier = Modifier
                .align(reactionPillAlignment)
                .offset {
                    IntOffset(
                        x = reactionPillX.roundToInt(),
                        y = positioning.reactionY.roundToInt()
                    )
                }
                .graphicsLayer {
                    scaleX = overlayScale.value
                    scaleY = overlayScale.value
                    alpha = overlayAlpha.value
                }
                .padding(horizontal = 8.dp)
        ) {
            ReactionPill(
                visible = isPresent,
                myReactions = myReactions,
                onReactionSelected = { tapback ->
                    scope.launch {
                        onReactionSelected(tapback)
                        closeWithAnimation()
                    }
                },
                onEmojiPickerClick = onEmojiPickerClick
            )
        }

        // Action Menu with boundary clamping
        val actionMenuAlignment = if (messageBounds.isFromMe) {
            Alignment.TopEnd
        } else {
            Alignment.TopStart
        }

        // Calculate clamped X position for action menu
        val actionMenuX = run {
            val idealX = if (messageBounds.isFromMe) {
                messageBounds.bounds.right - actionMenuWidth
            } else {
                messageBounds.bounds.left
            }
            // Clamp to safe zones
            idealX.coerceIn(safeMarginPx, screenWidth - actionMenuWidth - safeMarginPx)
        }

        Box(
            modifier = Modifier
                .align(actionMenuAlignment)
                .offset {
                    IntOffset(
                        x = actionMenuX.roundToInt(),
                        y = positioning.actionY.roundToInt()
                    )
                }
                .graphicsLayer {
                    scaleX = overlayScale.value
                    scaleY = overlayScale.value
                    alpha = overlayAlpha.value
                }
                .padding(horizontal = 8.dp)
                .width(180.dp)
        ) {
            MessageActionMenu(
                visible = isPresent,
                canReply = canReply,
                canCopy = canCopy,
                canForward = canForward,
                onReply = {
                    scope.launch {
                        onReply()
                        closeWithAnimation()
                    }
                },
                onCopy = {
                    scope.launch {
                        onCopy()
                        closeWithAnimation()
                    }
                },
                onForward = {
                    scope.launch {
                        onForward()
                        closeWithAnimation()
                    }
                }
            )
        }
    }
}

/**
 * Calculated positions for the reaction pill and action menu.
 */
private data class OverlayPositioning(
    val reactionY: Float,
    val actionY: Float,
    val reactionsAbove: Boolean
)

/**
 * Calculate optimal positions for reaction pill and action menu based on message location.
 */
private fun calculatePositioning(
    messageBounds: Rect,
    screenHeight: Float,
    density: Float
): OverlayPositioning {
    val topThreshold = 150 * density // 150.dp in pixels
    val bottomThreshold = screenHeight - 250 * density // 250.dp from bottom

    val reactionPillHeight = 52 * density // ~52.dp
    val actionMenuHeight = 160 * density // ~160.dp (3 items)
    val spacing = 12 * density // 12.dp spacing

    return when {
        // Message too close to top: show everything below
        messageBounds.top < topThreshold -> {
            OverlayPositioning(
                reactionY = messageBounds.bottom + spacing,
                actionY = messageBounds.bottom + spacing + reactionPillHeight + spacing,
                reactionsAbove = false
            )
        }
        // Message too close to bottom: show everything above
        messageBounds.bottom > bottomThreshold -> {
            OverlayPositioning(
                reactionY = messageBounds.top - spacing - reactionPillHeight - spacing - actionMenuHeight,
                actionY = messageBounds.top - spacing - actionMenuHeight,
                reactionsAbove = true
            )
        }
        // Default: reactions above, actions below
        else -> {
            OverlayPositioning(
                reactionY = messageBounds.top - spacing - reactionPillHeight,
                actionY = messageBounds.bottom + spacing,
                reactionsAbove = true
            )
        }
    }
}

/**
 * Simplified overlay for non-tapback-eligible messages (e.g., local SMS).
 * Shows only action menu without reactions.
 */
@Composable
fun ActionOnlyOverlay(
    visible: Boolean,
    messageBounds: MessageBoundsInfo?,
    canCopy: Boolean = true,
    canForward: Boolean = true,
    onDismiss: () -> Unit,
    onCopy: () -> Unit = {},
    onForward: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Decouple visibility from presence
    var isPresent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Animation state
    val overlayScale = remember { Animatable(0f) }
    val overlayAlpha = remember { Animatable(0f) }
    val scrimAlpha = remember { Animatable(0f) }

    // Close with animation
    val closeWithAnimation: suspend () -> Unit = {
        scope.launch { scrimAlpha.animateTo(0f, tween(200)) }
        scope.launch { overlayAlpha.animateTo(0f, tween(150)) }
        overlayScale.animateTo(
            0f,
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
        isPresent = false
        onDismiss()
    }

    // Handle visibility changes with animations
    LaunchedEffect(visible, messageBounds) {
        if (visible && messageBounds != null) {
            isPresent = true
            launch { scrimAlpha.animateTo(0.32f, tween(200)) }
            launch { overlayAlpha.animateTo(1f, tween(150, delayMillis = 50)) }
            overlayScale.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        } else if (!visible && isPresent) {
            closeWithAnimation()
        }
    }

    if (!isPresent || messageBounds == null) return

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
    val safeMarginPx = with(density) { SafeZoneMargin.toPx() }
    val actionMenuWidth = with(density) { 180.dp.toPx() }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Scrim background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha.value)
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { scope.launch { closeWithAnimation() } }
                )
        )

        // Action Menu only with boundary clamping
        val actionMenuAlignment = if (messageBounds.isFromMe) {
            Alignment.TopEnd
        } else {
            Alignment.TopStart
        }

        val actionMenuX = run {
            val idealX = if (messageBounds.isFromMe) {
                messageBounds.bounds.right - actionMenuWidth
            } else {
                messageBounds.bounds.left
            }
            idealX.coerceIn(safeMarginPx, screenWidth - actionMenuWidth - safeMarginPx)
        }

        Box(
            modifier = Modifier
                .align(actionMenuAlignment)
                .offset {
                    IntOffset(
                        x = actionMenuX.roundToInt(),
                        y = (messageBounds.bounds.bottom + with(density) { 12.dp.toPx() }).roundToInt()
                    )
                }
                .graphicsLayer {
                    scaleX = overlayScale.value
                    scaleY = overlayScale.value
                    alpha = overlayAlpha.value
                }
                .padding(horizontal = 8.dp)
                .width(180.dp)
        ) {
            MessageActionMenu(
                visible = isPresent,
                canReply = false,
                canCopy = canCopy,
                canForward = canForward,
                onCopy = {
                    scope.launch {
                        onCopy()
                        closeWithAnimation()
                    }
                },
                onForward = {
                    scope.launch {
                        onForward()
                        closeWithAnimation()
                    }
                }
            )
        }
    }
}
