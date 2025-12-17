# Tapback & Message Focus Architecture (Refactored)

This document outlines the **refactored** architecture for the Tapback (reaction) and Message Focus experience in BothBubbles. This design replaces the previous `Popup`-based approach with a robust **Full-Screen Overlay** system to resolve clipping, positioning, and spotlighting issues.

---

## Table of Contents

1. [Core Architecture: The Overlay System](#1-core-architecture-the-overlay-system)
2. [Data Models](#2-data-models)
3. [UI Components](#3-ui-components)
4. [Positioning & Layout Logic](#4-positioning--layout-logic)
5. [Animation System](#5-animation-system)
6. [Interaction Flow](#6-interaction-flow)
7. [Implementation Strategy](#7-implementation-strategy)

---

## 1. Core Architecture: The Overlay System

### The Problem with `Popup`
The previous implementation relied on Compose's `Popup` API. This caused several issues:
- **Clipping:** Popups are often clipped by window bounds or parent layouts if not carefully managed.
- **Context Loss:** Popups exist in a separate window token, complicating theme and composition local propagation.
- **Spotlighting:** Rendering a "hole" in a scrim or perfectly aligning a duplicate bubble is difficult with `Popup` positioning.

### The Solution: `MessageFocusOverlay`
We will implement a **single, full-screen overlay** at the root of the `ChatScreen` (or `Scaffold`).

- **Z-Index Layering:** The overlay sits on top of the message list but within the same Compose window.
- **Coordinate Mapping:** We use `onGloballyPositioned` to capture the exact screen coordinates of the target message.
- **Ghost Rendering:** The overlay renders a visual copy ("Ghost") of the selected message by **re-rendering the message component** with the same data. This ensures high fidelity without the complexity of bitmap capture.

---

## 2. Data Models

### 2.1 `FocusState`
State holder for the active overlay.

```kotlin
@Immutable
data class MessageFocusState(
    val visible: Boolean = false,
    val messageId: String? = null,
    val messageBounds: Rect? = null, // Screen coordinates of the original bubble
    val myReactions: ImmutableSet<Tapback> = persistentSetOf() // Uses kotlinx.collections.immutable
)
```

**Note:** We avoid storing `@Composable` lambdas in state to prevent recomposition issues and ensure serializability. The overlay will look up the message data using `messageId`.

### 2.2 `Tapback` Enum
(Unchanged from previous plan) - Represents the 6 iMessage reaction types.

---

## 3. UI Components

### 3.1 `MessageFocusOverlay` (Root Component)
The container that manages the scrim, the focused message, and the menus.

```kotlin
@Composable
fun MessageFocusOverlay(
    state: MessageFocusState,
    onDismiss: () -> Unit,
    onReactionSelected: (Tapback) -> Unit,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 1. The main screen content (Chat List)
        content()

        // 2. The Overlay (only if visible)
        if (state.visible) {
            // Scrim (Dimmed Background)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { onDismiss() }
            )

            // Focused Content Layer
            FocusLayout(
                bounds = state.messageBounds,
                messageContent = state.messageContent,
                menuContent = {
                    ReactionPicker(...)
                    MessageActionsMenu(...)
                }
            )
        }
    }
}
```

### 3.2 `ReactionPicker` (Refactored)
Separated from the action menu for better positioning flexibility. A "Pill" shape containing the emojis.

- **Visuals:** Horizontal row of large emojis.
- **Interaction:** Scrub gesture support using `pointerInput`.
- **Animation:** Staggered entrance for emojis.

```kotlin
Modifier.pointerInput(Unit) {
    detectHorizontalDragGestures { change, _ ->
        val index = (change.position.x / emojiWidth).toInt()
        onHoverChanged(index)
    }
}
```

### 3.3 `MessageActionsMenu` (Refactored)
Vertical list of actions (Reply, Copy, Forward, etc.).

- **Visuals:** Material 3 Card/Surface.
- **Placement:** Can be positioned independently of the Reaction Picker (e.g., Picker above message, Actions below).

---

## 4. Positioning & Layout Logic

### 4.1 `FocusLayout` Custom Layout
Instead of standard `Column`/`Row`, we use a custom `Layout` or `BoxWithConstraints` to manually position elements based on the `messageBounds`.

**Algorithm:**
1.  **Safe Areas:** Calculate available space using `WindowInsets`.
    ```kotlin
    val imeInsets = WindowInsets.ime.asPaddingValues()
    val keyboardHeight = imeInsets.calculateBottomPadding()
    ```
2.  **Vertical Flip:**
    -   If `messageBounds.top` > `threshold` (Message is low): Show Picker **ABOVE**.
    -   If `messageBounds.bottom` < `threshold` (Message is high): Show Picker **BELOW**.
3.  **Horizontal Shift:**
    -   Ensure Picker never clips off-screen.
    -   `x = centerOfMessage.x - (pickerWidth / 2)`
    -   `x = x.coerceIn(padding, screenWidth - pickerWidth - padding)`
4.  **Action Menu Placement:**
    -   Place on the *opposite* side of the Picker if space permits.
    -   Fallback: Stack vertically with Picker.

### 4.2 Coordinate Capture
The message list items must report their coordinates when long-pressed.

```kotlin
Modifier.onGloballyPositioned { coordinates ->
    val bounds = coordinates.boundsInRoot()
    // Store this bounds to pass to the overlay when clicked
}
```

### 4.3 Scroll Locking & Edge Cases
- **Scroll Lock:** When the overlay is visible, the underlying list must not scroll.
  ```kotlin
  // In ChatScreen
  LaunchedEffect(focusState.visible) {
      if (focusState.visible) listState.disableScrolling() // Or userScrollEnabled = false
  }
  ```
- **Off-screen Handling:** If a message is partially off-screen, the "Ghost" renders fully, potentially overlapping the nav bar or status bar (handled by the Overlay's Z-index).
- **Long Messages:** If a message is taller than the available screen height (minus menus), the menus overlay the message content or the message is scaled down.


---

## 5. Animation System

### 5.1 The "Spotlight" Effect
1.  **Instant:** Overlay appears.
2.  **Frame 0:** "Ghost" message renders at `state.messageBounds` (exact match to original).
3.  **Frame 1+:**
    -   Scrim fades in (`alpha: 0f -> 0.4f`).
    -   Ghost message scales slightly (`scale: 1f -> 1.05f`) or lifts (`elevation`).
    -   Reaction Picker expands/fades in from the message anchor point.
    -   Action Menu slides in.

### 5.2 Exit Animation
Reverse the process. Crucially, the Ghost message must animate *back* to the original bounds before the overlay vanishes, creating a seamless transition.

---

## 6. Interaction Flow

1.  **Long Press:** User long-presses a message bubble.
2.  **Capture:**
    -   Haptic feedback fires.
    -   Bubble calculates its `boundsInRoot`.
    -   `ChatScreen` state updates: `focusState = FocusState(visible=true, bounds=...)`.
3.  **Render:**
    -   `MessageFocusOverlay` takes over input.
    -   Original list might be slightly dimmed or blurred (optional).
4.  **Selection:**
    -   User taps an emoji -> `onReactionSelected` -> Overlay dismisses.
    -   User taps "Reply" -> `onReply` -> Overlay dismisses -> Keyboard opens.
    -   User taps Scrim -> Overlay dismisses.

---

## 7. Implementation Strategy

### Step 1: Create the Overlay Foundation
-   Create `MessageFocusOverlay.kt`.
-   Implement the `FocusLayout` logic to handle raw `Rect` bounds.

### Step 2: Refactor Components
-   Split `TapbackCard` into `ReactionPicker` and `MessageActionsMenu`.
-   Update `ReactionPill` to be standalone.

### Step 3: Integrate with Chat Screen
-   Hoist `focusState` to `ChatViewModel` or `ChatScreen` local state.
-   Wrap the `MessageList` in the `MessageFocusOverlay`.

### Step 4: Polish
-   Tune the spring animations for the "Ghost" bubble.
-   Add haptics for the scrub gesture on the new `ReactionPicker`.

---

## 8. Material Design 3 Implementation Details

To ensure a premium, native Android feel, we will strictly adhere to Material Design 3 (MD3) specifications.

### 8.1 Color Roles & Elevation
We will use **Tonal Elevation** (color overlays) combined with **Shadow Elevation** for depth.

| Component | Color Role | Tonal Elevation | Shadow Elevation |
| :--- | :--- | :--- | :--- |
| **Reaction Picker** | `Surface Container High` | 6.dp | 8.dp |
| **Action Menu** | `Surface Container` | 3.dp | 4.dp |
| **Scrim** | `Scrim` (Black) | N/A | N/A |
| **Ghost Message** | `Surface` (Preserved) | +2.dp (Lift) | 4.dp |

**Implementation Note:**
Use `Surface(tonalElevation = ...)` in Compose. Do not manually mix colors.

### 8.2 Shapes & Geometry
*   **Reaction Picker:** `CircleShape` (Fully rounded pill).
*   **Action Menu:** `RoundedCornerShape(16.dp)` (Medium component).
*   **Ghost Message:** Inherits the shape of the original bubble (usually `RoundedCornerShape` with varying radii).

### 8.3 Motion & Easing
We will use MD3 standard motion tokens.

*   **Entrance (Overlay):** `EmphasizedDecelerate` (Duration: 400ms).
    *   *Why:* Needs to feel responsive and "pop" in.
*   **Exit (Overlay):** `EmphasizedAccelerate` (Duration: 200ms).
    *   *Why:* Needs to clear the screen quickly.
*   **Scrub Selection:** `Spring(damping = 0.7f, stiffness = 300f)`.
    *   *Why:* Needs to track the finger closely but with a slight organic bounce.

```kotlin
// Motion Tokens
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
```

### 8.4 Haptics
Haptic feedback is crucial for the "physicality" of the reaction picker.

*   **Long Press (Open):** `HapticFeedbackType.LongPress`.
*   **Selection Change (Scrub):** `HapticFeedbackType.TextHandleMove` (or `SegmentFrequent` on supported devices).
    *   *Implementation:* Use `View.performHapticFeedback` for more granular control if Compose's `LocalHapticFeedback` is insufficient.
*   **Commit (Tap):** `HapticFeedbackType.Confirm` (if available) or `ContextClick`.

---

## 9. Accessibility & Focus Management

To ensure the feature is accessible to all users:

1.  **Focus Management:**
    *   Use `FocusRequester` to move focus to the `ReactionPicker` immediately upon opening.
    *   Trap focus within the overlay using `Modifier.focusGroup()`.

2.  **Content Descriptions:**
    *   Emojis: "React with Love", "React with Like", etc.
    *   Selected state: "Selected. React with Love."

3.  **TalkBack:**
    *   Announce "Message options opened" when the overlay appears.
    *   Ensure the Scrim has a click label "Dismiss".

4.  **Navigation:**
    *   Intercept the system Back button (`BackHandler`) to dismiss the overlay before navigating back.
