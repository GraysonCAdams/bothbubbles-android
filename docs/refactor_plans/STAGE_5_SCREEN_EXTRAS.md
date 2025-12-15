# Refactor Plan: ChatScreen Extras (Background & Gestures)

**Status:** ✅ COMPLETE

**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatScreen.kt`
**Goal:** Extract background rendering and complex gesture logic to clean up the main screen composable.

## Design Philosophy: Composition & Modifiers

- **Visuals:** Background logic (gradients, wallpapers) should be a distinct Composable.
- **Behavior:** Complex touch handling should be extracted into custom Modifiers or helper classes.

## Instructions

### 1. Extract ChatBackground ✅

Created: `app/src/main/kotlin/com/bothbubbles/ui/chat/components/ChatBackground.kt`

- Composable that wraps chat content with theme-aware background
- **Props:** `chatGuid` (to look up specific wallpaper), `isDarkTheme`
- Also provides simplified overload without chat-specific settings
- Reserved for future wallpaper/gradient support per chat

### 2. Extract ChatGestures ✅

Created: `app/src/main/kotlin/com/bothbubbles/ui/chat/components/ChatGestures.kt`

- **Finding:** No complex `pointerInput` or `detectTapGestures` blocks exist in `ChatScreen.kt`
- Message-level gestures already properly extracted to:
  - `MessageSwipeContainer.kt` - Swipe-to-reply container
  - `MessageSwipeGestures.kt` - Reply indicator and date reveal UI
- Send button gestures already in:
  - `composer/gestures/SendModeGestureHandler.kt` - Mode toggle via vertical swipe
- Created `Modifier.chatGestures()` as placeholder for future chat-level gestures

### 3. Update ChatScreen ✅

1. ✅ Wrapped content in `ChatBackground { ... }` instead of `Box` with `.background()`
2. ✅ Chat gestures remain at component level (already properly architected)

## Verification

- **Visual:** Background renders using `ChatBackground` composable
- **Behavior:** Swipe-to-reply handled by `MessageSwipeContainer`, send mode toggle by `SendModeGestureHandler`

## Files Changed

- `ChatScreen.kt` - Import and use `ChatBackground`
- `components/ChatBackground.kt` - New file
- `components/ChatGestures.kt` - New file (placeholder for future use)
