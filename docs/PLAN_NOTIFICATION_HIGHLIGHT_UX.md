# Plan: Material Design 3 Notification Highlight UX

## Status: Proposed

## 1. Problem Statement

The current notification highlight implementation uses a "Golden Glow" effect (pulsing amber shadow) behind the message.

- **Issue:** It feels outdated and "foreign" to the UI. It looks like a background highlight rather than a state of the message itself.
- **Goal:** Re-imagine the highlight using **Material Design 3 (Material You)** principles, focusing on **Tonal Elevation** and **Dynamic Color**.

## 2. Design Concept: "Tonal Respiration"

Instead of an external glow, the message bubble itself will smoothly transition to a **Tertiary Container** color and then relax back to its original state. This mimics a "breathing" or "pulsing" effect that feels organic to the system theme.

### Key Principles

1.  **Dynamic Color:** Use `MaterialTheme.colorScheme.tertiaryContainer`.
    - If the wallpaper is Blue, the highlight is a soft Lavender.
    - If the wallpaper is Green, the highlight is a soft Teal.
    - Always matches the user's system theme.
2.  **Shape Awareness:** The highlight must respect the exact shape of the message bubble (including tails, rounded corners, and varying sizes) without manual shape passing.
3.  **Subtlety:** Modify the _surface_ of the object, don't add a "halo".

## 3. Technical Specification

### Animation Timeline

We use a "Fast In, Hold, Slow Out" curve to simulate attention grabbing followed by relaxation.

| Time (ms) | Opacity | Easing          | Description                                       |
| :-------- | :------ | :-------------- | :------------------------------------------------ |
| 0ms       | 0.0     | FastOutSlowIn   | Start                                             |
| 300ms     | 0.6     | Linear          | **Attention Peak** (Quickly grab eye)             |
| 900ms     | 0.6     | Linear          | **Hold** (Keep attention while scrolling settles) |
| 2000ms    | 0.0     | FastOutLinearIn | **Relaxation** (Slow fade out)                    |

### Rendering Technique: `BlendMode.SrcAtop`

To ensure the highlight matches the bubble shape perfectly:

1.  Draw the message bubble content normally.
2.  Draw a rectangle of the `TertiaryContainer` color over the content.
3.  Use `BlendMode.SrcAtop`. This composites the color _only_ where the destination (the bubble) has non-zero alpha.
    - **Result:** The color tints the bubble but does not spill outside the bounds or transparent areas.

## 4. Implementation Plan

### Step 1: Create the Modifier

Create a new file `app/src/main/java/com/bluebubbles/messaging/ui/modifiers/MaterialAttentionHighlight.kt`.

```kotlin
package com.bluebubbles.messaging.ui.modifiers

import androidx.compose.animation.core.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer

/**
 * A Material Design 3 "Attention" state.
 * Instead of a glow, it tints the content with the TertiaryContainer color
 * using a "breathing" animation curve.
 */
fun Modifier.materialAttentionHighlight(
    shouldHighlight: Boolean,
    onHighlightFinished: (() -> Unit)? = null
): Modifier = composed {
    // MD3: Use TertiaryContainer for "Attention/Highlight" states
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer

    // Manage the one-shot animation trigger
    var isAnimating by remember { mutableStateOf(false) }

    LaunchedEffect(shouldHighlight) {
        if (shouldHighlight) {
            isAnimating = true
        }
    }

    // MD3 Motion: "Emphasized" easing
    val alpha by animateFloatAsState(
        targetValue = if (isAnimating) 0.0f else 0.0f, // We drive this manually via Animatable if needed, or use a transition
        // Simplified for this plan doc: The actual code uses keyframes
        animationSpec = keyframes {
            durationMillis = 2000
            0.0f at 0 with FastOutSlowInEasing
            0.6f at 300 with LinearEasing
            0.6f at 900 with LinearEasing
            0.0f at 2000 with FastOutLinearInEasing
        },
        finishedListener = {
            if (isAnimating) {
                isAnimating = false
                onHighlightFinished?.invoke()
            }
        }
    )

    // Note: The actual implementation needs a proper Transition or Animatable
    // to handle the "0 -> 0.6 -> 0" sequence correctly.
    // See the full code implementation for the robust version.

    this
        .graphicsLayer {
            // Use off-screen compositing to ensure the blend mode works cleanly
            alpha = 0.99f
        }
        .drawWithContent {
            drawContent()

            // We need to access the animated value.
            // In the real implementation, we'll use an Animatable.
            // For this plan, we assume 'currentAlpha' is the value from the animation.
            val currentAlpha = 0.6f // Placeholder for plan

            if (currentAlpha > 0f) {
                drawRect(
                    color = highlightColor.copy(alpha = currentAlpha),
                    blendMode = BlendMode.SrcAtop
                )
            }
        }
}
```

### Step 2: Integrate into `MessageBubble`

Locate `MessageBubble.kt` (or equivalent) and apply the modifier.

```kotlin
MessageBubble(
    message = message,
    modifier = Modifier
        .materialAttentionHighlight(
            shouldHighlight = message.guid == viewModel.highlightedMessageGuid,
            onHighlightFinished = { viewModel.clearHighlight() }
        )
)
```

### Step 3: Scroll & Focus Logic

Ensure the `ChatViewModel` or `ChatScreen` handles the scrolling.

1.  **Deep Link / Notification Tap:** Sets `highlightedMessageGuid`.
2.  **Auto-Scroll:** `LazyListState.animateScrollToItem` brings the message to the viewport.
3.  **Trigger:** Once scrolling settles (or slightly before), `shouldHighlight` becomes true.

## 5. Verification

- [ ] **Theme Check:** Switch between Light/Dark mode and different wallpapers. Verify the highlight color adapts (TertiaryContainer).
- [ ] **Shape Check:** Verify the highlight does not bleed outside the bubble corners or tails.
- [ ] **Animation Check:** Verify the "breath" feels natural (not too jarring).
- [ ] **Performance:** Ensure `graphicsLayer` usage doesn't cause frame drops during the list scroll.
