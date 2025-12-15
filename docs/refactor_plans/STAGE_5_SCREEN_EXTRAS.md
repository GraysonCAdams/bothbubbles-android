# Refactor Plan: ChatScreen Extras (Background & Gestures)

**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatScreen.kt`
**Goal:** Extract background rendering and complex gesture logic to clean up the main screen composable.

## Design Philosophy: Composition & Modifiers
- **Visuals:** Background logic (gradients, wallpapers) should be a distinct Composable.
- **Behavior:** Complex touch handling should be extracted into custom Modifiers or helper classes.

## Instructions

### 1. Extract ChatBackground
Create: `app/src/main/kotlin/com/bothbubbles/ui/chat/components/ChatBackground.kt`
- Move the logic that renders the chat wallpaper or gradient background.
- **Props:** `chatGuid` (to look up specific wallpaper), `isDarkTheme`.

### 2. Extract ChatGestures
Create: `app/src/main/kotlin/com/bothbubbles/ui/chat/components/ChatGestures.kt`
- If there are complex `pointerInput` or `detectTapGestures` blocks in `ChatScreen`, move them here.
- Create a custom modifier: `fun Modifier.chatGestures(...): Modifier`.

### 3. Update ChatScreen
1.  Wrap the content in `ChatBackground { ... }` or place it at the bottom of the `Box`.
2.  Apply `.chatGestures(...)` to the relevant container instead of inline logic.

## Verification
- **Visual:** Backgrounds still load correctly.
- **Behavior:** Swipe-to-reply and other gestures still work.
