# Technical Plan: Chat Rendering Optimization & Smoothness

**Status:** Draft
**Target:** Signal-level scrolling performance and reliable animations
**Related:** `ARCHITECTURE_CHAT_RENDERING.md`

---

## Executive Summary

Current profiling indicates that the chat experience suffers from two distinct classes of performance issues:

1.  **Visual Jank (Frame Drops):** Caused by O(N) list operations and sorting occurring on the Main Thread during message arrival and scrolling.
2.  **Broken Animations:** Caused by side-effects in Composable bodies executing before the render phase, leading to a race condition where animations are cancelled before they appear.
3.  **Typing Sluggishness:** Caused by excessive recomposition of the entire composer tree on every keystroke due to unstable lambda references and improper state observation.

This plan outlines a 4-phase approach to refactoring the architecture to achieve 60/120fps consistency.

---

## Phase 1: Fixing Animation Reliability (The "Heisenbug")

### The Problem

Currently, we mark a message as "animated" immediately when the `MessageBubble` composable function is called.

```kotlin
// CURRENT BROKEN IMPLEMENTATION
if (shouldAnimateEntrance) {
    animatedMessageGuids.add(message.guid) // SIDE EFFECT IN COMPOSITION!
}
```

Compose may run this function multiple times (recomposition) before a frame is actually drawn. If it runs twice, the second run sees `alreadyAnimated = true` and disables the animation.

### The Solution

Move the state mutation to a `LaunchedEffect`. This ensures the code only runs when the node is actually committed to the UI tree.

### Code Example

```kotlin
// ChatScreen.kt

@Composable
fun MessageBubble(...) {
    // ...
    val alreadyAnimated = remember(message.guid) {
        message.guid in animatedMessageGuids
    }

    // Only animate if it's a new load and hasn't been seen
    val shouldAnimate = initialLoadComplete && !alreadyAnimated

    // Mark as seen ONLY after composition commits
    if (shouldAnimate) {
        LaunchedEffect(message.guid) {
            // Optional: Small delay to ensure layout is settled
            // delay(16)
            animatedMessageGuids.add(message.guid)
        }
    }

    Modifier.newMessageEntrance(shouldAnimate = shouldAnimate)
    // ...
}
```

---

## Phase 2: Eliminating Scroll & Update Jank (Main Thread Blocks)

### 2.1 Offloading Position Shifting

When a new message arrives, `MessagePagingController` shifts all indices. Currently, this iterates over the entire map on the Main Thread.

**Fix:** Move the operation to `Dispatchers.Default`.

```kotlin
// MessagePagingController.kt

suspend fun onSizeChanged(newSize: Int) = withContext(Dispatchers.Default) { // <--- MOVE TO BG
    val diff = newSize - currentSize
    if (diff > 0) {
        shiftPositions(diff)
    }
    // ...
}

private suspend fun shiftPositions(amount: Int) {
    stateMutex.withLock {
        // This O(N) operation now runs off the UI thread
        val newSparseData = sparseData.mapKeys { (key, _) -> key + amount }
        sparseData.clear()
        sparseData.putAll(newSparseData)
    }
}
```

### 2.2 Offloading List Transformation

`ChatViewModel` converts the sparse structure to a `List` for `LazyColumn`. This involves sorting keys, which is O(N log N).

**Fix:** Use `flowOn(Dispatchers.Default)` in the ViewModel pipeline.

```kotlin
// ChatViewModel.kt

val uiState = combine(
    pagingController.messages
        .map { it.toList() } // Expensive sorting/mapping
        .flowOn(Dispatchers.Default), // <--- CRITICAL: Execute map on BG thread
    _otherState
) { messages, other ->
    ChatUiState(messages = messages, ...)
}
.stateIn(...)
```

---

## Phase 3: Composer & Typing Performance

### 3.1 Fix `adjustedComposerState` Re-allocation

The `remember` block in `ChatScreen` depends on `composerState`, which changes on every keystroke. This forces the `copy()` operation and downstream recomposition 60+ times a second while typing.

**Fix:** Deconstruct the state so `remember` only invalidates when structural props change, not text.

```kotlin
// ChatScreen.kt

// BAD:
// val adjustedState = remember(composerState) { ... }

// GOOD:
val isRecording = composerState.isRecording
val replyTo = composerState.replyToMessage

// Only recreate this object if the MODE changes, not the text
val staticComposerConfig = remember(isRecording, replyTo) {
    ComposerConfig(isRecording, replyTo)
}

// Pass text separately to the TextField, not wrapped in a big state object
ChatComposer(
    config = staticComposerConfig,
    text = composerState.text, // Primitive, changes frequently
    onTextChanged = viewModel::onTextChanged
)
```

### 3.2 Fix `combine` Order in ViewModel

The `combine` block executes its lambda before `distinctUntilChanged` filters it.

**Fix:** Filter the rapidly changing inputs _before_ combining them with expensive state.

```kotlin
// ChatViewModel.kt

// 1. Fast-changing text flow
val textFlow = _draftText.distinctUntilChanged()

// 2. Slow-changing configuration flow
val configFlow = combine(
    composerRelevantState,
    _activePanel,
    _attachmentQuality
) { ... }.distinctUntilChanged()

// 3. Combine only when necessary
val composerState = combine(textFlow, configFlow) { text, config ->
    config.copy(text = text)
}
```

---

## Phase 4: Advanced LazyList Optimizations

### 4.1 Stable Content Types

Ensure `contentType` returns a primitive integer or enum, not a complex object, to allow Compose to efficiently recycle ViewHolders.

```kotlin
// ChatScreen.kt

itemsIndexed(
    items = messages,
    key = { _, m -> m.guid },
    contentType = { _, m ->
        // Return stable Int: 0 = Incoming, 1 = Outgoing, 2 = DateSeparator
        if (m.isFromMe) 1 else 0
    }
) { ... }
```

### 4.2 Avoid `derivedStateOf` inside `items`

Do not use `derivedStateOf` inside the `item` block if it depends on the global list state. Calculate item-specific properties in the ViewModel or use `remember(item)` for local logic.

---

## Verification Strategy

1.  **Enable Layout Inspector:** Verify that typing "a" only recomposes the `BasicTextField` and not the entire `ChatScreen`.
2.  **Profile GPU Rendering:** Enable "Profile HWUI rendering" in Developer Options. Ensure bars stay below the green line during scroll.
3.  **Slow Motion Recording:** Record the screen at 240fps to verify that new messages slide in smoothly without popping or flickering.
