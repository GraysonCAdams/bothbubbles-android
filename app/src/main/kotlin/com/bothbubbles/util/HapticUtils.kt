package com.bothbubbles.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized haptic feedback utilities for consistent UX across the app.
 *
 * ## Haptic Guidelines
 *
 * | User Action          | Function              | Haptic Type      |
 * |----------------------|-----------------------|------------------|
 * | Button tap           | `onTap()`             | TextHandleMove   |
 * | Long-press start     | `onLongPress()`       | LongPress        |
 * | Selection confirmed  | `onConfirm()`         | LongPress        |
 * | Threshold crossed    | `onThresholdCrossed()`| LongPress        |
 * | Drag transition      | `onDragTransition()`  | TextHandleMove   |
 *
 * ## Usage
 *
 * ```kotlin
 * val haptic = LocalHapticFeedback.current
 *
 * // Button tap
 * Button(onClick = {
 *     HapticUtils.onTap(haptic)
 *     doAction()
 * })
 *
 * // Long-press context menu
 * Modifier.combinedClickable(
 *     onLongClick = {
 *         HapticUtils.onLongPress(haptic)
 *         showMenu()
 *     }
 * )
 * ```
 *
 * @see docs/HAPTIC_GUIDELINES.md for complete documentation
 */
object HapticUtils {

    /**
     * Global enabled flag for haptic feedback.
     * Set this from settings to disable all haptics.
     *
     * Thread-safe using AtomicBoolean.
     */
    private val _enabled = AtomicBoolean(true)

    /**
     * Whether haptic feedback is enabled globally.
     * Set this from settings to disable all haptics.
     * Call from Application.onCreate() or when settings change.
     */
    var enabled: Boolean
        get() = _enabled.get()
        set(value) = _enabled.set(value)

    /**
     * Standard tap feedback for buttons, menu items, links, and other tappable elements.
     * Provides subtle confirmation that the tap was registered.
     *
     * Use for:
     * - Button clicks
     * - Menu item selection
     * - Link taps
     * - Icon button presses
     */
    fun onTap(haptic: HapticFeedback) {
        if (!enabled) return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /**
     * Confirmation feedback for significant state changes or completed selections.
     * Provides stronger feedback than tap to indicate a meaningful action occurred.
     *
     * Use for:
     * - Emoji/reaction selection confirmed
     * - Swipe action completed
     * - Mode switch confirmed
     * - Pull-to-refresh threshold reached
     * - Toggle switch changed
     */
    fun onConfirm(haptic: HapticFeedback) {
        if (!enabled) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * Long-press initiated feedback for context menus and hold actions.
     * Indicates a long-press gesture was recognized.
     *
     * Use for:
     * - Context menu opening
     * - Voice recording start
     * - Drag-to-reorder initiated
     * - Effect picker opening
     */
    fun onLongPress(haptic: HapticFeedback) {
        if (!enabled) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * Threshold crossing feedback during drag gestures.
     * Indicates the user has dragged past a meaningful boundary.
     *
     * Use for:
     * - Swipe action threshold crossed
     * - Pull-to-refresh ready to release
     * - Send mode toggle threshold
     * - Reply swipe ready
     */
    fun onThresholdCrossed(haptic: HapticFeedback) {
        if (!enabled) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * Subtle transition feedback during continuous drag operations.
     * Provides feedback as the user moves between selectable items.
     *
     * Use for:
     * - Emoji scrubbing (moving between emoji options)
     * - Attachment reordering (hovering over drop positions)
     * - Reaction pill dragging
     *
     * Note: Consider using [ThrottledHaptic] for high-frequency drag operations
     * to avoid overwhelming feedback.
     */
    fun onDragTransition(haptic: HapticFeedback) {
        if (!enabled) return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /**
     * Disabled state feedback with intentional stronger haptic.
     * Used when tapping a disabled element to acknowledge the tap
     * while indicating the action cannot be performed.
     *
     * Typically paired with a shake animation and explanation (snackbar/toast).
     */
    fun onDisabledTap(haptic: HapticFeedback) {
        if (!enabled) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

/**
 * Throttled haptic feedback for high-frequency operations like drag/scrub gestures.
 * Prevents overwhelming haptic feedback during continuous interactions.
 *
 * ## Usage
 *
 * ```kotlin
 * val throttledHaptic = rememberThrottledHaptic()
 *
 * Modifier.pointerInput(Unit) {
 *     detectDragGestures { change, _ ->
 *         if (itemChanged) {
 *             throttledHaptic.onDragTransition()
 *         }
 *     }
 * }
 * ```
 *
 * @param minIntervalMs Minimum time between haptic feedback events (default: 50ms)
 */
class ThrottledHaptic(
    private val haptic: HapticFeedback,
    private val minIntervalMs: Long = DEFAULT_THROTTLE_MS
) {
    private var lastHapticTime = 0L

    /**
     * Throttled drag transition feedback.
     * Will only trigger if [minIntervalMs] has passed since the last haptic.
     *
     * @return true if haptic was triggered, false if throttled
     */
    fun onDragTransition(): Boolean {
        val now = System.currentTimeMillis()
        return if (now - lastHapticTime >= minIntervalMs) {
            lastHapticTime = now
            HapticUtils.onDragTransition(haptic)
            true
        } else {
            false
        }
    }

    /**
     * Throttled tap feedback for rapid tap scenarios.
     *
     * @return true if haptic was triggered, false if throttled
     */
    fun onTap(): Boolean {
        val now = System.currentTimeMillis()
        return if (now - lastHapticTime >= minIntervalMs) {
            lastHapticTime = now
            HapticUtils.onTap(haptic)
            true
        } else {
            false
        }
    }

    /**
     * Reset the throttle timer.
     * Call when a gesture ends to ensure the next gesture starts fresh.
     */
    fun reset() {
        lastHapticTime = 0L
    }

    companion object {
        /** Default throttle interval in milliseconds */
        const val DEFAULT_THROTTLE_MS = 50L
    }
}

/**
 * Remember a throttled haptic instance for the current composition.
 *
 * @param haptic The HapticFeedback instance (typically from LocalHapticFeedback.current)
 * @param minIntervalMs Minimum time between haptic events
 */
@Composable
fun rememberThrottledHaptic(
    haptic: HapticFeedback,
    minIntervalMs: Long = ThrottledHaptic.DEFAULT_THROTTLE_MS
): ThrottledHaptic {
    return remember(haptic, minIntervalMs) {
        ThrottledHaptic(haptic, minIntervalMs)
    }
}
