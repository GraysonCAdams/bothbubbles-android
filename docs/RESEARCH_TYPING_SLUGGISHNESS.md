# Research: Typing Sluggishness in Chat Composer

**Date:** December 14, 2025
**Status:** Research Complete
**Component:** `ChatComposer` (Jetpack Compose)

## Problem Description

Users may experience sluggishness or input lag when typing in the chat composer. This document analyzes the potential causes based on the current codebase architecture.

## Architecture Overview

The chat composer is built using Jetpack Compose, following a unidirectional data flow pattern:

- **State:** `ComposerState` (Immutable data class)
- **ViewModel:** `ComposerViewModel` (Holds `StateFlow<ComposerState>`)
- **View:** `ChatComposer` -> `MainInputRow` -> `TextInputContent` -> `ComposerTextField`

## Root Cause Analysis

### 1. Excessive Recomposition Chain

The primary cause of sluggishness is likely the recomposition chain triggered by every keystroke.

**The Cycle:**

1. User types a character.
2. `ComposerViewModel` updates `ComposerState.text`.
3. `ComposerState` is a single data class, so the _entire_ state object is replaced.
4. `ChatComposer` observes the new state.
5. `ChatComposer` recomposes.
6. `MainInputRow` takes the full `state`, so it recomposes.
7. `TextInputContent` takes the full `state`, so it recomposes.
8. `ComposerTextField` recomposes (expected).

**The Issue:**
Inside `TextInputContent`, the `leadingContent` and `trailingContent` lambdas for `ComposerTextField` are defined inline:

```kotlin
// app/src/main/kotlin/com/bothbubbles/ui/chat/composer/ChatComposer.kt

TextInputContent(...) {
    ComposerTextField(
        // ...
        leadingContent = {
            ComposerActionButtons(...) // Recreated lambda
        },
        trailingContent = {
            ComposerMediaButtons(...) // Recreated lambda
        }
    )
}
```

Because `TextInputContent` recomposes on every keystroke, these lambdas are recreated. `ComposerTextField` receives new lambda instances, causing it to recompose its children, specifically `ComposerActionButtons` and `ComposerMediaButtons`.

**Impact:**

- `ComposerActionButtons` (Plus icon) and `ComposerMediaButtons` (Camera, Gallery, Emoji icons) are redrawn on every single character typed, even though their visual state (expanded/collapsed, show/hide camera) rarely changes during typing.
- This adds unnecessary UI work on the main thread during the critical input loop.

### 2. `animateContentSize` Overhead

`ComposerTextField` uses `animateContentSize` on its surface:

```kotlin
// app/src/main/kotlin/com/bothbubbles/ui/chat/composer/components/ComposerTextField.kt

Surface(
    modifier = modifier
        .animateContentSize(...)
)
```

While `animateContentSize` is generally efficient, it attaches a layout listener. If the text layout changes (even slightly, or if the system thinks it might have changed due to recomposition), it might trigger animation logic or layout calculations. Combined with the frequent recomposition, this could contribute to jank.

### 3. State Granularity

Passing the entire `ComposerState` down to `MainInputRow` and `TextInputContent` breaks the "skip" optimization of Compose. Even if `TextInputContent` only _uses_ `text`, `sendMode`, and `smsInputBlocked`, it receives the whole object. Since the object changes (because `text` changed), `TextInputContent` cannot be skipped.

## Proposed Solutions

### 1. Stabilize Lambdas (High Impact)

Wrap the `leadingContent` and `trailingContent` lambdas in `remember` or extract them to be stable.

**Refactoring `TextInputContent`:**

```kotlin
@Composable
private fun TextInputContent(...) {
    // Extract values to prevent reading full state inside lambdas if possible
    val isPickerExpanded = state.isPickerExpanded
    val showCamera = state.text.isBlank()

    // Memoize the leading content lambda
    val leadingContent = remember(isPickerExpanded) {
        {
            ComposerActionButtons(
                isExpanded = isPickerExpanded,
                onClick = { onEvent(ComposerEvent.ToggleMediaPicker) }
            )
        }
    }

    // Memoize the trailing content lambda
    val trailingContent = remember(showCamera) {
        {
            ComposerMediaButtons(
                showCamera = showCamera,
                // ... pass stable callbacks
            )
        }
    }

    ComposerTextField(
        // ...
        leadingContent = leadingContent,
        trailingContent = trailingContent
    )
}
```

### 2. Deconstruct State (Medium Impact)

Instead of passing the full `ComposerState` to `TextInputContent`, pass only the primitive values it needs.

```kotlin
@Composable
private fun TextInputContent(
    text: String,
    sendMode: ChatSendMode,
    isSmsBlocked: Boolean,
    isPickerExpanded: Boolean,
    // ...
)
```

This allows `TextInputContent` to be skipped if its specific inputs haven't changed. However, since `text` changes on typing, `TextInputContent` will still recompose. So **Solution 1 is more important** because it prevents the _children_ (buttons) from recomposing.

### 3. Optimize `ComposerTextField`

Ensure `ComposerTextField` is skippable. Since it takes `text` (which changes), it will recompose. The goal is to make its _parts_ skippable.

- `BasicTextField` is unavoidable.
- `leadingContent` and `trailingContent` execution should be cheap or skipped if inputs match.

### 4. Verify `ComposerMotionTokens`

Ensure that animations defined in `ComposerMotionTokens` are not running continuously or causing layout thrashing. The `spring` specs look standard, but excessive usage can be costly.

## Next Steps

1.  **Refactor `TextInputContent`**: Implement `remember` for `leadingContent` and `trailingContent`.
2.  **Profile**: Use Android Studio Profiler (or Layout Inspector) to verify that `ComposerMediaButtons` no longer recomposes on every keystroke.
3.  **Review `animateContentSize`**: Test if removing it improves typing latency (at the cost of smooth expansion). If so, consider optimizing the animation spec or using a custom layout modifier.
