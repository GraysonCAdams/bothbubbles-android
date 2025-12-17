package com.bothbubbles.ui.components.message.focus

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bothbubbles.ui.components.message.Tapback
import com.bothbubbles.ui.theme.MotionTokens
import kotlinx.collections.immutable.ImmutableSet
import kotlin.math.roundToInt

/**
 * Full-screen overlay for tapback/reaction menu with Material Design 3 styling.
 *
 * This overlay uses a same-window layered approach (not Popup) which provides:
 * - No clipping issues at screen edges
 * - Proper touch event handling
 * - Single composition for synchronized animations
 * - Full z-index control
 *
 * Architecture:
 * ```
 * ┌─────────────────────────────────────────┐
 * │  Layer 3: Menu (positioned)             │  ← Reaction picker + actions
 * ├─────────────────────────────────────────┤
 * │  Layer 2: Message Ghost (elevated)      │  ← Re-rendered message
 * ├─────────────────────────────────────────┤
 * │  Layer 1: Scrim (full-screen)           │  ← Dims background
 * └─────────────────────────────────────────┘
 * ```
 *
 * @param state The focus state containing message info and visibility
 * @param onDismiss Called when overlay should be dismissed
 * @param onReactionSelected Called when user selects a reaction
 * @param onReply Called when user taps reply
 * @param onCopy Called when user taps copy
 * @param onForward Called when user taps forward
 * @param messageContent Composable to render the focused message
 */
@Composable
fun MessageFocusOverlay(
    state: MessageFocusState,
    onDismiss: () -> Unit,
    onReactionSelected: (Tapback) -> Unit,
    onReply: () -> Unit = {},
    onCopy: () -> Unit = {},
    onForward: () -> Unit = {},
    modifier: Modifier = Modifier,
    messageContent: @Composable BoxScope.() -> Unit
) {
    // Back button dismisses overlay
    BackHandler(enabled = state.isValid) {
        onDismiss()
    }

    // Don't render anything if not valid
    if (!state.isValid) return

    val bounds = state.messageBounds ?: return
    val density = LocalDensity.current
    val view = LocalView.current

    // Get screen dimensions
    val screenWidth = view.width.toFloat()
    val screenHeight = view.height.toFloat()

    // Get safe zones from window insets
    val safeZone = remember(view) {
        val windowInsets = ViewCompat.getRootWindowInsets(view)
        val systemBars = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = windowInsets?.getInsets(WindowInsetsCompat.Type.ime())

        SafeZone(
            top = (systemBars?.top ?: 0).toFloat(),
            bottom = maxOf(
                (systemBars?.bottom ?: 0).toFloat(),
                (ime?.bottom ?: 0).toFloat()
            ),
            left = (systemBars?.left ?: 0).toFloat(),
            right = (systemBars?.right ?: 0).toFloat()
        )
    }

    // Calculate menu position
    val menuPosition = remember(bounds, safeZone, screenWidth, screenHeight, state.isFromMe) {
        calculateMenuPosition(
            messageBounds = bounds,
            isFromMe = state.isFromMe,
            safeZone = safeZone,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density.density
        )
    }

    // Animated scrim alpha
    val scrimAlpha by animateFloatAsState(
        targetValue = if (state.visible) 0.5f else 0f,
        animationSpec = tween(MotionTokens.Duration.MEDIUM_1),
        label = "scrimAlpha"
    )

    // Animated message elevation (lift effect)
    val messageElevation by animateFloatAsState(
        targetValue = if (state.visible) 16f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "messageElevation"
    )

    // Animated message scale (subtle zoom)
    val messageScale by animateFloatAsState(
        targetValue = if (state.visible) 1.03f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "messageScale"
    )

    // Focus requester for accessibility
    val focusRequester = remember { FocusRequester() }

    // Request focus when overlay appears
    LaunchedEffect(state.visible) {
        if (state.visible) {
            focusRequester.requestFocus()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(1000f)
    ) {
        // Layer 1: Scrim - dims background, dismisses on tap
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                .semantics {
                    contentDescription = "Dismiss message options"
                    onClick(label = "Dismiss") {
                        onDismiss()
                        true
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { onDismiss() }
                }
        )

        // Layer 2: Message Ghost - re-rendered message with elevation
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = bounds.left.roundToInt(),
                        y = bounds.top.roundToInt()
                    )
                }
                .graphicsLayer {
                    scaleX = messageScale
                    scaleY = messageScale
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    shadowElevation = messageElevation
                    shape = RoundedCornerShape(20.dp)
                    clip = false
                }
        ) {
            messageContent()
        }

        // Layer 3: Menu - positioned above or below message
        AnimatedVisibility(
            visible = state.visible,
            enter = fadeIn(tween(MotionTokens.Duration.SHORT_4, delayMillis = 50)) +
                    scaleIn(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        initialScale = 0.8f,
                        transformOrigin = TransformOrigin(
                            pivotFractionX = if (state.isFromMe) 1f else 0f,
                            pivotFractionY = if (menuPosition.isAbove) 1f else 0f
                        )
                    ),
            exit = fadeOut(tween(MotionTokens.Duration.SHORT_3)) +
                    scaleOut(
                        animationSpec = spring(stiffness = Spring.StiffnessHigh),
                        targetScale = 0.9f
                    ),
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = menuPosition.x.roundToInt(),
                        y = menuPosition.y.roundToInt()
                    )
                }
                .widthIn(max = 280.dp)
                .focusRequester(focusRequester)
        ) {
            FocusMenuCard(
                showReactions = state.canReact,
                myReactions = state.myReactions,
                canReply = state.canReply,
                canCopy = state.canCopy,
                canForward = state.canForward,
                onReactionSelected = { tapback ->
                    onReactionSelected(tapback)
                    onDismiss()
                },
                onReply = {
                    onReply()
                    onDismiss()
                },
                onCopy = {
                    onCopy()
                    onDismiss()
                },
                onForward = {
                    onForward()
                    onDismiss()
                }
            )
        }
    }
}

/**
 * Safe zone boundaries accounting for system bars and keyboard.
 */
private data class SafeZone(
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float
)

/**
 * Calculate optimal position for the menu relative to the message.
 *
 * Positioning strategy:
 * 1. Prefer showing above the message (like iOS iMessage)
 * 2. If not enough space above, show below
 * 3. Horizontal alignment matches message alignment (left for received, right for sent)
 * 4. Clamp within safe zone boundaries
 */
private fun calculateMenuPosition(
    messageBounds: Rect,
    isFromMe: Boolean,
    safeZone: SafeZone,
    screenWidth: Float,
    screenHeight: Float,
    density: Float
): OverlayPosition {
    val spacing = 12 * density
    val margin = 16 * density
    val estimatedMenuHeight = 220 * density // Reactions + 3 actions
    val estimatedMenuWidth = 280 * density

    // Usable area
    val usableTop = safeZone.top + margin
    val usableBottom = screenHeight - safeZone.bottom - margin
    val usableLeft = safeZone.left + margin
    val usableRight = screenWidth - safeZone.right - margin

    // Available space above and below
    val spaceAbove = messageBounds.top - usableTop - spacing
    val spaceBelow = usableBottom - messageBounds.bottom - spacing

    // Vertical position: prefer above, fallback to below
    val (y, isAbove) = when {
        spaceAbove >= estimatedMenuHeight -> {
            (messageBounds.top - spacing - estimatedMenuHeight) to true
        }
        spaceBelow >= estimatedMenuHeight -> {
            (messageBounds.bottom + spacing) to false
        }
        else -> {
            // Tight space - center vertically in available area
            val centerY = (usableTop + usableBottom - estimatedMenuHeight) / 2
            centerY to (messageBounds.center.y > screenHeight / 2)
        }
    }

    // Horizontal position: align to message edge
    val x = if (isFromMe) {
        // Sent: align right edge of menu to right edge of message
        (messageBounds.right - estimatedMenuWidth).coerceIn(usableLeft, usableRight - estimatedMenuWidth)
    } else {
        // Received: align left edge of menu to left edge of message
        messageBounds.left.coerceIn(usableLeft, usableRight - estimatedMenuWidth)
    }

    return OverlayPosition(
        x = x,
        y = y.coerceIn(usableTop, usableBottom - estimatedMenuHeight),
        isAbove = isAbove
    )
}
