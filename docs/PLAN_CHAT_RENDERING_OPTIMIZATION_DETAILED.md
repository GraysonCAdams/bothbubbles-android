# Technical Plan: Chat Rendering Optimization & Smoothness

**Status:** Approved for Implementation
**Target:** Signal-level scrolling performance (60/120fps) and reliable, glitch-free animations.
**Related Documents:** `ARCHITECTURE_CHAT_RENDERING.md`

---

## Executive Summary

This document outlines a comprehensive technical strategy to resolve performance bottlenecks and animation reliability issues in the BlueBubbles chat interface.

Our analysis confirms that the current technology stack (Jetpack Compose) is capable of delivering the desired performance. The observed "jank" and missing animations are not inherent to the framework but are caused by specific architectural patterns:

1.  **Main Thread Blocking:** Heavy O(N) list operations are executing on the UI thread.
2.  **Composition Side-Effects:** State mutations are occurring during the composition phase, causing race conditions.
3.  **Excessive Recomposition:** The input field and message list are recomposing too frequently due to unstable state objects.

We will execute this plan in **4 distinct phases**, prioritizing the most visible user-facing issues first.

---

## Phase 1: Animation Reliability (The "Heisenbug" Fix)

**Goal:** Ensure new messages _always_ animate in smoothly, without disappearing or snapping into place instantly.

### 1.1 The Problem: Side-Effects in Composition

Currently, the code marks a message as "animated" immediately when the `MessageBubble` function is called by the Compose runtime.

```kotlin
// ❌ CURRENT BROKEN IMPLEMENTATION
@Composable
fun MessageBubble(...) {
    if (shouldAnimateEntrance) {
        // CRITICAL ERROR: This runs during "Composition", which can happen multiple times
        // before a single frame is drawn.
        animatedMessageGuids.add(message.guid)
    }
}
```

**Technical Rationale:**
Jetpack Compose separates the "Composition" phase (running the code) from the "Rendering" phase (drawing the pixels). It often runs the Composition phase speculatively or multiple times to measure layout.

1.  **Pass 1 (Measure):** `MessageBubble` runs. `shouldAnimate` is true. We add GUID to `animatedMessageGuids`.
2.  **Pass 2 (Layout/Draw):** `MessageBubble` runs again. It checks `animatedMessageGuids`. It finds the GUID. `shouldAnimate` becomes false.
3.  **Result:** The animation is cancelled before the user sees it.

### 1.2 The Solution: Lifecycle-Aware State Mutation

We must move the state mutation to a `LaunchedEffect`, which guarantees execution only _after_ the component has been successfully committed to the UI tree.

```kotlin
// ✅ PROPOSED FIX
@Composable
fun MessageBubble(...) {
    // 1. Check state without mutating it
    val isAlreadyAnimated = remember(message.guid) {
        message.guid in animatedMessageGuids
    }

    val shouldAnimate = initialLoadComplete && !isAlreadyAnimated

    // 2. Mutate state only after successful composition
    if (shouldAnimate) {
        LaunchedEffect(message.guid) {
            // Optional: Wait 1 frame to ensure the animation system has picked up the start value
            // delay(16)
            animatedMessageGuids.add(message.guid)
        }
    }

    // 3. Apply modifier
    Modifier.newMessageEntrance(shouldAnimate = shouldAnimate)
}
```

### 1.3 Verification

- **Test:** Send 10 messages rapidly to the device.
- **Success Criteria:** Every single message slides in. No message "pops" into existence instantly.

---

## Phase 2: Eliminating Scroll & Update Jank (Main Thread Offloading)

**Goal:** Achieve 120fps scrolling and eliminate the "stutter" when a new message arrives.

### 2.1 The Problem: O(N) Operations on the UI Thread

When a new message arrives, two expensive operations happen on the Main Thread:

1.  **Position Shifting:** `MessagePagingController` iterates through the entire map of loaded messages to shift their indices by +1.
2.  **List Transformation:** `ChatViewModel` converts the sparse map into a `List<MessageUiModel>`, which involves sorting thousands of keys.

**Technical Rationale:**
The Android UI thread has 16ms (at 60fps) or 8ms (at 120fps) to draw a frame. Iterating over 5,000 items and sorting them takes significantly longer than 8ms, causing the app to freeze (drop frames) until the loop finishes.

### 2.2 The Solution: Background Threading with Coroutines

#### Step A: Offload Position Shifting

Modify `MessagePagingController` to perform data manipulation on `Dispatchers.Default`.

```kotlin
// MessagePagingController.kt

suspend fun onSizeChanged(newSize: Int) = withContext(Dispatchers.Default) {
    // 1. Calculate diff on background thread
    val diff = newSize - currentSize
    if (diff > 0) {
        shiftPositions(diff)
    }
}

private suspend fun shiftPositions(amount: Int) {
    stateMutex.withLock {
        // 2. Rebuild map on background thread
        // This is O(N), but it no longer blocks the UI
        val newSparseData = sparseData.mapKeys { (key, _) -> key + amount }
        sparseData.clear()
        sparseData.putAll(newSparseData)
    }
}
```

#### Step B: Offload List Transformation

Modify `ChatViewModel` to process the UI list on a background thread.

```kotlin
// ChatViewModel.kt

val uiState = combine(
    pagingController.messages
        .map { sparseList ->
            // ❌ OLD: Ran on Main Thread implicitly
            // ✅ NEW: Explicitly runs on Default dispatcher
            sparseList.toList()
        }
        .flowOn(Dispatchers.Default), // <--- CRITICAL OPTIMIZATION
    _otherState
) { messages, other ->
    ChatUiState(messages = messages, ...)
}
.stateIn(...)
```

### 2.3 Verification

- **Test:** Scroll rapidly while receiving incoming messages.
- **Success Criteria:** The scroll momentum is not interrupted when a new message bubble appears. The "GPU Rendering Profile" bars stay below the green line.

---

## Phase 3: Typing Performance (Composer Optimization)

**Goal:** Typing should feel instant, with zero input lag.

### 3.1 The Problem: Cascade Recomposition

Currently, the `composerState` object is a single data class containing both "fast" state (text) and "slow" state (configuration, reply mode, attachments).

- Every keystroke creates a new `ComposerState` object.
- `ChatScreen` observes this object.
- Because the object reference changes, `ChatScreen` recomposes the _entire_ composer area, including buttons, attachment pickers, and layout logic.

### 3.2 The Solution: State Segmentation & Stability

#### Step A: Deconstruct State in UI

Don't pass the whole state object to sub-components. Pass only what they need.

```kotlin
// ChatScreen.kt

// 1. Extract stable configuration
// This block ONLY runs when recording mode or reply target changes
val composerConfig = remember(composerState.isRecording, composerState.replyToMessage) {
    ComposerConfig(
        isRecording = composerState.isRecording,
        replyToMessage = composerState.replyToMessage
    )
}

// 2. Pass text as a primitive
// Only the TextField will recompose when text changes
ChatComposer(
    config = composerConfig,
    text = composerState.text,
    onTextChanged = viewModel::onTextChanged
)
```

#### Step B: Fix ViewModel Pipeline

Ensure the `combine` logic doesn't re-calculate expensive configuration on every keystroke.

```kotlin
// ChatViewModel.kt

// 1. Fast Flow (Text)
val textFlow = _draftText.distinctUntilChanged()

// 2. Slow Flow (Config)
val configFlow = combine(
    composerRelevantState,
    _activePanel,
    _attachmentQuality
) { ... }.distinctUntilChanged() // <--- Block updates here if config hasn't changed

// 3. Merge
val composerState = combine(textFlow, configFlow) { text, config ->
    config.copy(text = text)
}
```

---

## Phase 4: Advanced LazyList Optimizations

**Goal:** Minimize the cost of scrolling by helping Compose recycle views efficiently.

### 4.1 Stable Content Types

Compose uses `contentType` to group items that have similar layouts. If not provided, it assumes all items are different, preventing recycling.

```kotlin
// ChatScreen.kt

itemsIndexed(
    items = messages,
    key = { _, m -> m.guid }, // Unique ID
    contentType = { _, m ->
        // Return a stable integer or enum
        // 0: Incoming Message
        // 1: Outgoing Message
        // 2: Date Separator
        // 3: System Message
        if (m.isFromMe) 1 else 0
    }
) { ... }
```

### 4.2 Avoid `derivedStateOf` in Items

Do not use `derivedStateOf` inside the `item` block if it depends on the global list state (e.g., "is this the last message?"). This forces the item to listen to the whole list.

- **Fix:** Calculate these properties in the ViewModel and bake them into `MessageUiModel` (e.g., `showAvatar: Boolean`).

---

## Why Jetpack Compose? (Technology Validation)

You may ask: _"Is Compose the right choice? Should we use RecyclerView?"_

**The Verdict: YES, Compose is the right choice.**

1.  **Performance Ceiling:** Compose is capable of 120fps. The issues identified above (O(N) on main thread) would cause the exact same lag in `RecyclerView` or React Native.
2.  **Development Speed:** Implementing the "Swipe to Reply" or "Staggered Entrance" animations in the old View system would take 5x the code and be more brittle.
3.  **Future Proofing:** Google is actively deprecating the View system. All modern Android libraries are built for Compose first.

The "jank" is not a framework limit; it is a logic bottleneck. By moving the math to the background thread (Phase 2), we unlock the framework's true potential.

---

## Implementation Summary

**Status:** ✅ Fully Implemented (All 4 Phases)
**Date:** December 2024

### Phase 1: Animation Reliability Fix ✅

**File:** `ChatScreen.kt` (lines 1464-1486)

**Problem:** `animatedMessageGuids.add()` was called during composition, causing animations to be cancelled before they were visible.

**Solution:** Used `remember` to check state without mutating it, then moved mutation to `LaunchedEffect`:

```kotlin
// 1. Check state without mutating it (stable across recompositions)
val isAlreadyAnimated = remember(message.guid) {
    message.guid in animatedMessageGuids
}

// 2. Mutate state only AFTER successful composition via LaunchedEffect
if (shouldAnimateEntrance) {
    LaunchedEffect(message.guid) {
        delay(16) // Ensure animation system has picked up start value
        animatedMessageGuids.add(message.guid)
    }
}
```

### Phase 2: Main Thread Offloading ✅

**Files:** `ChatViewModel.kt` (line 1813), `MessagePagingController.kt` (lines 358-415)

**Problem:** O(N) list transformations and position shifting blocked the UI thread.

**Solution 1:** Added `flowOn(Dispatchers.Default)` to the message list transformation:

```kotlin
pagingController.messages
    .map { sparseList -> sparseList.toList() }
    .flowOn(Dispatchers.Default)  // Run toList() on background thread
    .conflate()
    .collect { ... }
```

**Solution 2:** Restructured `shiftPositions()` to use three-step pattern:

1. Quick snapshot under mutex (fast)
2. Build new data structures on `Dispatchers.Default` (O(N), off main thread)
3. Atomic swap under mutex (fast)

### Phase 3: Typing Performance (Composer Optimization) ✅

**File:** `ChatScreen.kt` (lines 826-853)

**Problem:** `remember(vararg keys)` created new `ComposerState` every time any key changed (recording duration updates 10x/sec).

**Solution:** Replaced with `derivedStateOf` which only triggers recomposition when the result changes structurally:

```kotlin
val adjustedComposerState by remember {
    derivedStateOf {
        if (isRecording || isPreviewingVoiceMemo) {
            composerState.copy(inputMode = ..., recordingState = ...)
        } else {
            composerState.copy(inputMode = ComposerInputMode.TEXT)
        }
    }
}
```

### Phase 4: Advanced LazyList Optimizations ✅

**File:** `ChatScreen.kt` (lines 1383-1401, 2023-2048)

**Problem:** All message items used the same content type (0 or 1), limiting view recycling efficiency.

**Solution:** Implemented granular content types for efficient view recycling:

```kotlin
contentType = { _, message ->
    when {
        message.isReaction -> ContentType.REACTION
        message.isPlacedSticker -> ContentType.STICKER
        message.attachments.isNotEmpty() ->
            if (message.isFromMe) ContentType.OUTGOING_WITH_ATTACHMENT
            else ContentType.INCOMING_WITH_ATTACHMENT
        message.isFromMe -> ContentType.OUTGOING_TEXT
        else -> ContentType.INCOMING_TEXT
    }
}
```

Added `ContentType` object with 9 stable integer constants for efficient comparison.

### Files Modified

| File                         | Changes                                                    |
| ---------------------------- | ---------------------------------------------------------- |
| `ChatScreen.kt`              | Phase 1, 3, 4 - Animation fix, derivedStateOf, contentType |
| `ChatViewModel.kt`           | Phase 2 - flowOn(Dispatchers.Default)                      |
| `MessagePagingController.kt` | Phase 2 - Background thread position shifting              |

### Testing Recommendations

1. **Phase 1:** Send 10 messages rapidly - every message should animate smoothly
2. **Phase 2:** Scroll rapidly while receiving messages - no frame drops, GPU bars below green line
3. **Phase 3:** Type quickly - zero input lag, instant character appearance
4. **Phase 4:** Scroll through mixed content (text, attachments, stickers) - smooth 120fps

---

## Implementation Summary

**Status:** ✅ Fully Implemented (All 4 Phases)
**Date:** December 2024

### Phase 1: Animation Reliability Fix ✅

**File:** `ChatScreen.kt` (lines 1464-1486)

**Problem:** `animatedMessageGuids.add()` was called during composition, causing animations to be cancelled before they were visible.

**Solution:** Used `remember` to check state without mutating it, then moved mutation to `LaunchedEffect`:

```kotlin
// 1. Check state without mutating it (stable across recompositions)
val isAlreadyAnimated = remember(message.guid) {
    message.guid in animatedMessageGuids
}

// 2. Mutate state only AFTER successful composition via LaunchedEffect
if (shouldAnimateEntrance) {
    LaunchedEffect(message.guid) {
        delay(16) // Ensure animation system has picked up start value
        animatedMessageGuids.add(message.guid)
    }
}
```

### Phase 2: Main Thread Offloading ✅

**Files:** `ChatViewModel.kt` (line 1813), `MessagePagingController.kt` (lines 358-415)

**Problem:** O(N) list transformations and position shifting blocked the UI thread.

**Solution 1:** Added `flowOn(Dispatchers.Default)` to the message list transformation:

```kotlin
pagingController.messages
    .map { sparseList -> sparseList.toList() }
    .flowOn(Dispatchers.Default)  // Run toList() on background thread
    .conflate()
    .collect { ... }
```

**Solution 2:** Restructured `shiftPositions()` to use three-step pattern:

1. Quick snapshot under mutex (fast)
2. Build new data structures on `Dispatchers.Default` (O(N), off main thread)
3. Atomic swap under mutex (fast)

### Phase 3: Typing Performance (Composer Optimization) ✅

**File:** `ChatScreen.kt` (lines 826-853)

**Problem:** `remember(vararg keys)` created new `ComposerState` every time any key changed (recording duration updates 10x/sec).

**Solution:** Replaced with `derivedStateOf` which only triggers recomposition when the result changes structurally:

```kotlin
val adjustedComposerState by remember {
    derivedStateOf {
        if (isRecording || isPreviewingVoiceMemo) {
            composerState.copy(inputMode = ..., recordingState = ...)
        } else {
            composerState.copy(inputMode = ComposerInputMode.TEXT)
        }
    }
}
```

### Phase 4: Advanced LazyList Optimizations ✅

**File:** `ChatScreen.kt` (lines 1383-1401, 2023-2048)

**Problem:** All message items used the same content type (0 or 1), limiting view recycling efficiency.

**Solution:** Implemented granular content types for efficient view recycling:

```kotlin
contentType = { _, message ->
    when {
        message.isReaction -> ContentType.REACTION
        message.isPlacedSticker -> ContentType.STICKER
        message.attachments.isNotEmpty() ->
            if (message.isFromMe) ContentType.OUTGOING_WITH_ATTACHMENT
            else ContentType.INCOMING_WITH_ATTACHMENT
        message.isFromMe -> ContentType.OUTGOING_TEXT
        else -> ContentType.INCOMING_TEXT
    }
}
```

Added `ContentType` object with 9 stable integer constants for efficient comparison.

### Files Modified

| File                         | Changes                                                    |
| ---------------------------- | ---------------------------------------------------------- |
| `ChatScreen.kt`              | Phase 1, 3, 4 - Animation fix, derivedStateOf, contentType |
| `ChatViewModel.kt`           | Phase 2 - flowOn(Dispatchers.Default)                      |
| `MessagePagingController.kt` | Phase 2 - Background thread position shifting              |

### Testing Recommendations

1. **Phase 1:** Send 10 messages rapidly - every message should animate smoothly
2. **Phase 2:** Scroll rapidly while receiving messages - no frame drops, GPU bars below green line
3. **Phase 3:** Type quickly - zero input lag, instant character appearance
4. **Phase 4:** Scroll through mixed content (text, attachments, stickers) - smooth 120fps
