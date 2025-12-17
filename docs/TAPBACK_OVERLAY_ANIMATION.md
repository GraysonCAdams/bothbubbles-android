# Tapback Overlay Animation - Technical Breakdown

## Current Issue

**Problem**: When the tapback menu opens, the original message disappears instantly (alpha → 0), but the spotlight clone doesn't appear to "take over" smoothly. This creates a jarring flash where the message vanishes before the overlay is visually established.

---

## Architecture Overview

### Component Hierarchy

```
ChatScreen
├── ChatMessageList
│   ├── LazyColumn
│   │   └── MessageListItem (many)
│   │       └── MessageBubble
│   │           └── onBoundsChanged callback → reports position
│   └── MessageListOverlays
│       └── MessageSpotlightOverlay
│           ├── Scrim (dims background)
│           ├── Spotlight Message (clone at original position)
│           └── TapbackCard (reactions + actions)
```

### State Flow

```
1. User long-presses message
   ↓
2. MessageBubble.onLongPress()
   ↓
3. onSelectMessageForTapback(message) called
   ↓
4. ChatScreenState.selectedMessageForTapback = message
   ↓
5. TWO THINGS HAPPEN SIMULTANEOUSLY:

   A) MessageListItem sees selectedMessageForTapback?.guid == message.guid
      → Sets tapbackHideAlpha = 0f
      → Original message INSTANTLY hidden

   B) MessageSpotlightOverlay sees visible = true
      → LaunchedEffect triggers entry animations
      → Animations take 200-250ms to complete
```

---

## Current Implementation Details

### 1. Original Message Hiding (MessageListItem.kt:157-164)

```kotlin
// Hide the message when it's selected for tapback overlay (prevents double-vision)
val isSelectedForTapback = selectedMessageForTapback?.guid == message.guid
val tapbackHideAlpha = if (isSelectedForTapback) 0f else 1f

Column(
    modifier = Modifier
        .alpha(stickerFadeAlpha * tapbackHideAlpha)  // INSTANT - no animation
        // ...
)
```

**Problem**: This is a binary switch (1f or 0f) with NO animation. The message vanishes in a single frame.

### 2. Spotlight Clone Appearance (MessageSpotlightOverlay.kt)

The overlay renders a clone of the message at the exact same screen position:

```kotlin
// Layer 3: Message Spotlight
Box(
    modifier = Modifier
        .offset {
            IntOffset(
                x = anchorBounds.left.roundToInt(),
                y = (anchorBounds.top + spotlightYOffset.value).roundToInt()
            )
        }
        .graphicsLayer {
            scaleX = spotlightScale.value      // Animates 1.0 → 1.05
            scaleY = spotlightScale.value
            shadowElevation = spotlightElevation.value  // Animates 0 → 24
        }
) {
    messageContent()  // Re-renders the MessageBubble
}
```

### 3. Entry Animations (MessageSpotlightOverlay.kt:132-165)

```kotlin
LaunchedEffect(visible, anchorBounds) {
    if (visible && anchorBounds != null) {
        isPresent = true

        // 1. Scrim fades in (250ms)
        launch { scrimAlpha.animateTo(0.55f, tween(250)) }

        // 2. Message lifts with spring animation
        launch { spotlightScale.animateTo(1.05f, spring(...)) }
        launch { spotlightElevation.animateTo(24f, spring(...)) }
        launch { spotlightYOffset.animateTo(targetOffset, spring(...)) }

        // 3. Card appears with delay (80ms delay, then 200ms fade)
        launch { cardAlpha.animateTo(1f, tween(200, delayMillis = 80)) }
        cardScale.animateTo(1f, spring(...))
    }
}
```

---

## The Timing Problem

```
Frame 0:   Original visible, Overlay not present
Frame 1:   selectedMessageForTapback set
           → Original INSTANTLY alpha=0 (GONE)
           → Overlay isPresent=true, but:
             - scrimAlpha = 0 (not visible yet)
             - spotlightScale = 1.0 (no emphasis yet)
             - spotlightElevation = 0 (no shadow yet)
Frame 2+:  Animations begin running
           → Over 200-250ms, overlay fades in
```

**Result**: There's a 1-2 frame gap where the original is hidden but the overlay isn't visually established. The clone IS rendered, but without scrim/elevation it doesn't "pop" and may blend into the background.

---

## Bounds Tracking

The message position is captured via `onGloballyPositioned`:

```kotlin
// In MessageSimpleBubble.kt
Surface(
    modifier = Modifier
        .onGloballyPositioned { coordinates ->
            onBoundsChanged?.invoke(coordinates.boundsInWindow())
        }
)
```

This callback only fires when the message is selected:

```kotlin
// In MessageListItem.kt
MessageBubble(
    onBoundsChanged = if (selectedMessageForTapback?.guid == message.guid) { bounds ->
        onSelectedBoundsChange(bounds)
    } else null
)
```

---

## Proposed Fixes

### Option A: Delay Original Hiding

Don't hide the original until the overlay is visually established:

```kotlin
// In MessageListItem - animate the hiding
val tapbackHideAlpha by animateFloatAsState(
    targetValue = if (isSelectedForTapback) 0f else 1f,
    animationSpec = tween(durationMillis = 150, delayMillis = 50)
)
```

This creates a brief overlap where both are visible, but the scrim dims the original while the spotlight takes over.

### Option B: Start Spotlight at Full Emphasis

Instead of animating FROM 1.0 scale, start the spotlight already "lifted":

```kotlin
// Initialize with emphasis, then settle
val spotlightScale = remember { Animatable(1.08f) }  // Start big
val spotlightElevation = remember { Animatable(24f) }  // Start elevated

// Then animate to final state
launch { spotlightScale.animateTo(1.05f, spring(...)) }
```

### Option C: Cross-Fade with Snapshot

Capture a bitmap of the original message, use it as placeholder while the real clone loads. Complex but smoothest.

### Option D: Keep Original Visible Under Scrim

Don't hide the original at all. The scrim dims it, and the spotlight clone renders on top with elevation. The 55% scrim + 24dp shadow should visually separate them.

```kotlin
// Remove this from MessageListItem:
val tapbackHideAlpha = if (isSelectedForTapback) 0f else 1f
```

---

## File References

| File | Purpose |
|------|---------|
| `ui/chat/components/MessageListItem.kt:157-164` | Original message hiding logic |
| `ui/components/message/MessageSpotlightOverlay.kt` | Full overlay implementation |
| `ui/components/message/MessageSimpleBubble.kt:411-412` | Bounds tracking callback |
| `ui/chat/components/MessageListOverlays.kt` | Overlay integration in chat |
| `ui/chat/ChatScreenState.kt:53` | `selectedMessageForTapback` state |

---

## Animation Parameters (Current)

| Property | Start | End | Spec | Duration |
|----------|-------|-----|------|----------|
| scrimAlpha | 0 | 0.55 | tween | 250ms |
| spotlightScale | 1.0 | 1.05 | spring (medium bouncy) | ~200ms |
| spotlightElevation | 0 | 24 | spring | ~200ms |
| spotlightYOffset | 0 | ±30px | spring | ~200ms |
| cardAlpha | 0 | 1 | tween | 200ms (80ms delay) |
| cardScale | 0 | 1 | spring (medium bouncy) | ~250ms |
| **originalAlpha** | 1 | 0 | **INSTANT** | **0ms** |

The `originalAlpha` being instant while everything else is animated is the core issue.
