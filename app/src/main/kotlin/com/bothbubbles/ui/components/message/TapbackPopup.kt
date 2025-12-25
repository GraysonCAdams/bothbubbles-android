package com.bothbubbles.ui.components.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.bothbubbles.ui.theme.MotionTokens
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Anchor-based tapback popup that follows the "Unified Card" approach.
 *
 * Key architectural changes:
 * 1. Uses Popup with PopupPositionProvider for framework-managed positioning
 * 2. Single unified card (TapbackCard) instead of separate pill + menu
 * 3. LiveZone-aware positioning (accounts for system bars, keyboard, composer)
 * 4. Animated entry/exit with scrim
 *
 * @param visible Whether the popup should be shown
 * @param anchorBounds The bounds of the message bubble to anchor to (in window coordinates)
 * @param isFromMe Whether the message is from the current user (affects horizontal alignment)
 * @param composerHeight Height of the composer at the bottom (for LiveZone calculation)
 * @param myReactions Set of reactions the user has already applied
 * @param canReply Whether reply action is available
 * @param canCopy Whether copy action is available
 * @param canForward Whether forward action is available
 * @param showReactions Whether to show reactions (false for local SMS)
 * @param onDismiss Called when popup should be dismissed
 * @param onReactionSelected Called when user selects a reaction
 * @param onReply Called when user taps reply
 * @param onCopy Called when user taps copy
 * @param onForward Called when user taps forward
 */
@Composable
fun TapbackPopup(
    visible: Boolean,
    anchorBounds: Rect?,
    isFromMe: Boolean,
    composerHeight: Float = 0f,
    myReactions: Set<Tapback> = emptySet(),
    canReply: Boolean = false,
    canCopy: Boolean = true,
    canForward: Boolean = true,
    isPinned: Boolean = false,
    isStarred: Boolean = false,
    showReactions: Boolean = true,
    onDismiss: () -> Unit,
    onReactionSelected: (Tapback) -> Unit = {},
    onReply: () -> Unit = {},
    onCopy: () -> Unit = {},
    onForward: () -> Unit = {},
    onPin: () -> Unit = {},
    onStar: () -> Unit = {}
) {
    // Decouple visibility from presence for animation
    var isPresent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Animation state (scrim handled by parent)
    val cardScale = remember { Animatable(0f) }
    val cardAlpha = remember { Animatable(0f) }

    // Close with animation
    val closeWithAnimation: suspend () -> Unit = {
        scope.launch { cardAlpha.animateTo(0f, tween(MotionTokens.Duration.QUICK)) }
        cardScale.animateTo(0f, MotionTokens.Springs.Gentle)
        isPresent = false
        onDismiss()
    }

    // Handle visibility changes
    LaunchedEffect(visible, anchorBounds) {
        if (visible && anchorBounds != null) {
            isPresent = true
            // Entry animation
            launch { cardAlpha.animateTo(1f, tween(MotionTokens.Duration.QUICK, delayMillis = MotionTokens.Duration.STAGGER)) }
            cardScale.animateTo(1f, MotionTokens.Springs.Bouncy)
        } else if (!visible && isPresent) {
            closeWithAnimation()
        }
    }

    if (!isPresent || anchorBounds == null) return

    val density = LocalDensity.current
    val view = LocalView.current

    // Get window insets for LiveZone calculation
    val windowInsets = remember(view) {
        ViewCompat.getRootWindowInsets(view)
    }

    // Calculate LiveZone (usable area)
    val liveZone = remember(windowInsets, composerHeight) {
        val systemBarsInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
        val imeInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.ime())

        LiveZone(
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

    // Card popup anchored to message (scrim handled by parent)
    Popup(
        popupPositionProvider = TapbackPositionProvider(
            anchorBounds = anchorBounds,
            isFromMe = isFromMe,
            liveZone = liveZone,
            density = density.density
        ),
        properties = PopupProperties(
            focusable = false // Allow touches to pass through to underlying content (scrolling, scrim taps)
        ),
        onDismissRequest = { scope.launch { closeWithAnimation() } }
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = cardScale.value
                    scaleY = cardScale.value
                    alpha = cardAlpha.value
                    // Scale from anchor point
                    transformOrigin = if (isFromMe) {
                        androidx.compose.ui.graphics.TransformOrigin(1f, 0f) // Top-right
                    } else {
                        androidx.compose.ui.graphics.TransformOrigin(0f, 0f) // Top-left
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
                    isPinned = isPinned,
                    isStarred = isStarred,
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
                    },
                    onPin = {
                        scope.launch {
                            onPin()
                            closeWithAnimation()
                        }
                    },
                    onStar = {
                        scope.launch {
                            onStar()
                            closeWithAnimation()
                        }
                    }
                )
            } else {
                ActionOnlyCard(
                    canCopy = canCopy,
                    canForward = canForward,
                    isPinned = isPinned,
                    isStarred = isStarred,
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
                    },
                    onPin = {
                        scope.launch {
                            onPin()
                            closeWithAnimation()
                        }
                    },
                    onStar = {
                        scope.launch {
                            onStar()
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
 *
 * The LiveZone is the "safe area" where UI elements can be placed without
 * being obscured by system UI.
 */
data class LiveZone(
    val top: Float,    // Inset from top (status bar)
    val bottom: Float, // Inset from bottom (nav bar + keyboard + composer)
    val left: Float,   // Inset from left (system gesture area)
    val right: Float   // Inset from right (system gesture area)
)

/**
 * Custom PopupPositionProvider that anchors the tapback card to the message bubble.
 *
 * Positioning logic:
 * 1. Calculate available space above and below anchor
 * 2. Prefer showing above the message (like iOS)
 * 3. If not enough space above, show below
 * 4. Horizontal alignment: align to message edge (right for sent, left for received)
 * 5. Clamp within LiveZone boundaries
 */
private class TapbackPositionProvider(
    private val anchorBounds: Rect,
    private val isFromMe: Boolean,
    private val liveZone: LiveZone,
    private val density: Float
) : PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val spacing = (12 * density).roundToInt() // 12.dp spacing
        val margin = (16 * density).roundToInt()  // 16.dp margin from edges

        // Calculate usable bounds (LiveZone)
        val usableTop = liveZone.top.roundToInt()
        val usableBottom = windowSize.height - liveZone.bottom.roundToInt()
        val usableLeft = liveZone.left.roundToInt() + margin
        val usableRight = windowSize.width - liveZone.right.roundToInt() - margin

        // Use the original anchor bounds (in window coordinates)
        val anchorTop = this.anchorBounds.top.roundToInt()
        val anchorBottom = this.anchorBounds.bottom.roundToInt()
        val anchorLeft = this.anchorBounds.left.roundToInt()
        val anchorRight = this.anchorBounds.right.roundToInt()

        // Calculate available space above and below
        val spaceAbove = anchorTop - usableTop - spacing
        val spaceBelow = usableBottom - anchorBottom - spacing

        // Decide vertical position: prefer above, fallback to below
        val y = if (spaceAbove >= popupContentSize.height) {
            // Show above message
            anchorTop - spacing - popupContentSize.height
        } else if (spaceBelow >= popupContentSize.height) {
            // Show below message
            anchorBottom + spacing
        } else {
            // Not enough space either way - show at top of LiveZone
            usableTop + spacing
        }

        // Calculate horizontal position: align to message edge
        val x = if (isFromMe) {
            // Sent messages: align right edge of card to right edge of message
            (anchorRight - popupContentSize.width).coerceIn(usableLeft, usableRight - popupContentSize.width)
        } else {
            // Received messages: align left edge of card to left edge of message
            anchorLeft.coerceIn(usableLeft, usableRight - popupContentSize.width)
        }

        return IntOffset(x, y.coerceIn(usableTop, usableBottom - popupContentSize.height))
    }
}
