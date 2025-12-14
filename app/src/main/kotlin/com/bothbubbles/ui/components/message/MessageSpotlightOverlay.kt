package com.bothbubbles.ui.components.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Message Spotlight Overlay - Full-screen overlay for tapback/reaction menu.
 *
 * This implementation uses a same-window layered approach instead of Popup,
 * which solves the touch handling issues inherent to Popup's separate window layer.
 *
 * Architecture:
 * ```
 * ┌─────────────────────────────────────────┐
 * │  Layer 4: TapbackCard (positioned)      │  ← Reaction picker + actions
 * ├─────────────────────────────────────────┤
 * │  Layer 3: Message Spotlight (clone)     │  ← Re-rendered message at bounds
 * ├─────────────────────────────────────────┤
 * │  Layer 2: Scrim (full-screen, 32%)      │  ← Dims background
 * ├─────────────────────────────────────────┤
 * │  Layer 1: LazyColumn (messages)         │  ← Original content (parent)
 * └─────────────────────────────────────────┘
 * ```
 *
 * Benefits over Popup approach:
 * - Touch events handled naturally by Compose gesture system
 * - Scroll detection works correctly via snapshotFlow
 * - Single composition = synchronized animations
 * - Full z-index control
 *
 * @param visible Whether the overlay should be shown
 * @param anchorBounds The bounds of the selected message (in window coordinates)
 * @param isFromMe Whether the message is from the current user (affects card alignment)
 * @param composerHeight Height of the composer (for safe zone calculation)
 * @param myReactions Set of reactions the user has already applied
 * @param canReply Whether reply action is available
 * @param canCopy Whether copy action is available
 * @param canForward Whether forward action is available
 * @param showReactions Whether to show reactions (false for local SMS)
 * @param onDismiss Called when overlay should be dismissed
 * @param onReactionSelected Called when user selects a reaction
 * @param onReply Called when user taps reply
 * @param onCopy Called when user taps copy
 * @param onForward Called when user taps forward
 * @param messageContent Composable lambda to render the spotlight message
 */
@Composable
fun MessageSpotlightOverlay(
    visible: Boolean,
    anchorBounds: Rect?,
    isFromMe: Boolean,
    composerHeight: Float = 0f,
    myReactions: Set<Tapback> = emptySet(),
    canReply: Boolean = false,
    canCopy: Boolean = true,
    canForward: Boolean = true,
    showReactions: Boolean = true,
    onDismiss: () -> Unit,
    onReactionSelected: (Tapback) -> Unit = {},
    onReply: () -> Unit = {},
    onCopy: () -> Unit = {},
    onForward: () -> Unit = {},
    messageContent: @Composable BoxScope.() -> Unit
) {
    // Decouple visibility from presence for animation
    var isPresent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Animation state
    val scrimAlpha = remember { Animatable(0f) }
    val cardScale = remember { Animatable(0f) }
    val cardAlpha = remember { Animatable(0f) }
    val spotlightScale = remember { Animatable(1f) }

    // Close with animation
    val closeWithAnimation: suspend () -> Unit = {
        // Animate out in parallel
        scope.launch { scrimAlpha.animateTo(0f, tween(200)) }
        scope.launch { cardAlpha.animateTo(0f, tween(150)) }
        scope.launch { spotlightScale.animateTo(1f, tween(150)) }
        cardScale.animateTo(
            0f,
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
        isPresent = false
        onDismiss()
    }

    // Handle visibility changes
    LaunchedEffect(visible, anchorBounds) {
        if (visible && anchorBounds != null) {
            isPresent = true
            // Entry animation - staggered for polish
            launch { scrimAlpha.animateTo(0.32f, tween(200)) }
            launch { spotlightScale.animateTo(1.02f, spring(stiffness = Spring.StiffnessMedium)) }
            launch { cardAlpha.animateTo(1f, tween(150, delayMillis = 50)) }
            cardScale.animateTo(
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

    if (!isPresent || anchorBounds == null) return

    val density = LocalDensity.current
    val view = LocalView.current

    // Get window insets for safe zone calculation
    val windowInsets = remember(view) {
        ViewCompat.getRootWindowInsets(view)
    }

    // Calculate safe zones (usable area)
    val safeZone = remember(windowInsets, composerHeight) {
        val systemBarsInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
        val imeInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.ime())

        SafeZone(
            top = (systemBarsInsets?.top ?: 0).toFloat(),
            bottom = maxOf(
                (systemBarsInsets?.bottom ?: 0).toFloat(),
                (imeInsets?.bottom ?: 0).toFloat(),
                composerHeight
            ),
            left = (systemBarsInsets?.left ?: 0).toFloat(),
            right = (systemBarsInsets?.right ?: 0).toFloat()
        )
    }

    // Get screen dimensions
    val screenWidth = view.width.toFloat()
    val screenHeight = view.height.toFloat()

    // Calculate card position relative to anchor
    val cardPosition = remember(anchorBounds, safeZone, screenWidth, screenHeight) {
        calculateCardPosition(
            anchorBounds = anchorBounds,
            isFromMe = isFromMe,
            safeZone = safeZone,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density.density
        )
    }

    // Full-screen overlay container
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1000f) // Ensure we're above all other content
    ) {
        // Layer 2: Scrim - dims background, dismisses on tap
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha.value))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { scope.launch { closeWithAnimation() } }
                    )
                }
        )

        // Layer 3: Message Spotlight - re-rendered message at saved bounds
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = anchorBounds.left.roundToInt(),
                        y = anchorBounds.top.roundToInt()
                    )
                }
                .graphicsLayer {
                    scaleX = spotlightScale.value
                    scaleY = spotlightScale.value
                    // Scale from center
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                }
        ) {
            messageContent()
        }

        // Layer 4: TapbackCard - positioned above or below spotlight
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = cardPosition.x.roundToInt(),
                        y = cardPosition.y.roundToInt()
                    )
                }
                .graphicsLayer {
                    scaleX = cardScale.value
                    scaleY = cardScale.value
                    alpha = cardAlpha.value
                    // Scale from anchor edge
                    transformOrigin = if (isFromMe) {
                        androidx.compose.ui.graphics.TransformOrigin(1f, if (cardPosition.isAbove) 1f else 0f)
                    } else {
                        androidx.compose.ui.graphics.TransformOrigin(0f, if (cardPosition.isAbove) 1f else 0f)
                    }
                }
                .widthIn(max = 280.dp)
        ) {
            if (showReactions) {
                TapbackCard(
                    myReactions = myReactions,
                    canReply = canReply,
                    canCopy = canCopy,
                    canForward = canForward,
                    onReactionSelected = { tapback ->
                        scope.launch {
                            onReactionSelected(tapback)
                            closeWithAnimation()
                        }
                    },
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
            } else {
                ActionOnlyCard(
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
}

/**
 * Represents the usable area of the screen after accounting for system bars,
 * keyboard, and composer.
 */
private data class SafeZone(
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float
)

/**
 * Calculated position for the TapbackCard.
 */
private data class CardPosition(
    val x: Float,
    val y: Float,
    val isAbove: Boolean // Whether card is positioned above the message
)

/**
 * Calculate optimal position for TapbackCard relative to anchor message.
 *
 * Positioning logic:
 * 1. Calculate available space above and below anchor
 * 2. Prefer showing above the message (like iOS)
 * 3. If not enough space above, show below
 * 4. Horizontal alignment: align to message edge (right for sent, left for received)
 * 5. Clamp within safe zone boundaries
 */
private fun calculateCardPosition(
    anchorBounds: Rect,
    isFromMe: Boolean,
    safeZone: SafeZone,
    screenWidth: Float,
    screenHeight: Float,
    density: Float
): CardPosition {
    val spacing = 12 * density // 12.dp spacing
    val margin = 16 * density  // 16.dp margin from edges
    val estimatedCardHeight = 200 * density // Estimated card height
    val estimatedCardWidth = 280 * density // Max card width

    // Calculate usable bounds
    val usableTop = safeZone.top + margin
    val usableBottom = screenHeight - safeZone.bottom - margin
    val usableLeft = safeZone.left + margin
    val usableRight = screenWidth - safeZone.right - margin

    // Calculate available space above and below
    val spaceAbove = anchorBounds.top - usableTop - spacing
    val spaceBelow = usableBottom - anchorBounds.bottom - spacing

    // Decide vertical position: prefer above, fallback to below
    val (y, isAbove) = if (spaceAbove >= estimatedCardHeight) {
        // Show above message
        (anchorBounds.top - spacing - estimatedCardHeight) to true
    } else if (spaceBelow >= estimatedCardHeight) {
        // Show below message
        (anchorBounds.bottom + spacing) to false
    } else {
        // Not enough space either way - show at top of safe zone
        (usableTop + spacing) to true
    }

    // Calculate horizontal position: align to message edge
    val x = if (isFromMe) {
        // Sent messages: align right edge of card to right edge of message
        (anchorBounds.right - estimatedCardWidth).coerceIn(usableLeft, usableRight - estimatedCardWidth)
    } else {
        // Received messages: align left edge of card to left edge of message
        anchorBounds.left.coerceIn(usableLeft, usableRight - estimatedCardWidth)
    }

    return CardPosition(
        x = x,
        y = y.coerceIn(usableTop, usableBottom - estimatedCardHeight),
        isAbove = isAbove
    )
}
