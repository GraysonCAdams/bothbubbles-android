# Shared Conventions for ChatScreen Refactor

**All agents MUST read this document before starting work.**

This document defines the patterns, rules, and conventions that all agents must follow to ensure consistency across the parallel refactor.

---

## Core Principle

> "Push Down State, Pull Data Locally."

State should be collected as close to where it's consumed as possible. `ChatScreen` becomes a coordinator that passes stable references (delegates), not unwrapped values.

---

## Required Patterns

### 1. State Collection

**ALWAYS use `collectAsStateWithLifecycle()`**, not `collectAsState()`:

```kotlin
// CORRECT
val searchState by searchDelegate.state.collectAsStateWithLifecycle()

// WRONG - doesn't respect lifecycle
val searchState by searchDelegate.state.collectAsState()
```

### 2. Performance Comments

**ALWAYS add a `// PERF FIX:` comment** when adding internal state collection:

```kotlin
// PERF FIX: Collect searchState internally to avoid ChatScreen recomposition
val searchState by searchDelegate.state.collectAsStateWithLifecycle()
```

### 3. Stale Capture Prevention

**ALWAYS use `rememberUpdatedState`** for values read inside `LaunchedEffect(Unit)`:

```kotlin
// CORRECT
val currentMessages by rememberUpdatedState(messages)
val currentCanLoadMore by rememberUpdatedState(canLoadMore)

LaunchedEffect(Unit) {
    flow.collect {
        // Uses current value, not stale capture
        currentMessages.firstOrNull { ... }
    }
}

// WRONG - captures stale value at launch time
LaunchedEffect(Unit) {
    flow.collect {
        messages.firstOrNull { ... }  // STALE!
    }
}
```

### 4. Lambda Providers for Frequently-Changing Values

**Use lambda providers** to avoid reading values during parent composition:

```kotlin
// CORRECT - lambda defers read to child
composerHeightPxProvider = { state.composerHeightPx }

// WRONG - reads value during parent composition
composerHeightPx = state.composerHeightPx
```

### 5. Derived State

**Use `derivedStateOf`** for computed values that depend on collected state:

```kotlin
// CORRECT
val replyingToMessage by remember {
    derivedStateOf {
        sendState.replyingToGuid?.let { guid ->
            messages.firstOrNull { it.guid == guid }
        }
    }
}

// WRONG - recalculates on every recomposition
val replyingToMessage = sendState.replyingToGuid?.let { guid ->
    messages.firstOrNull { it.guid == guid }
}
```

### 6. Delegate Parameter Naming

**Follow existing naming convention**: `{feature}Delegate`

```kotlin
fun ChatMessageList(
    messageListDelegate: ChatMessageListDelegate,
    searchDelegate: ChatSearchDelegate,
    syncDelegate: ChatSyncDelegate,
    // ...
)
```

### 7. Backward Compatibility During Transition

**Keep old parameters temporarily** with `@Deprecated` annotation:

```kotlin
fun ChatInputUI(
    // NEW: Delegate for internal collection
    sendDelegate: ChatSendDelegate,

    // OLD: Will be removed after ChatScreen is updated
    @Deprecated("Use sendDelegate instead - collected internally")
    sendState: SendState? = null,
)
```

### 8. Debug Logging

**Guard ALL logging** behind `BuildConfig.DEBUG`:

```kotlin
// CORRECT
if (BuildConfig.DEBUG) {
    Log.d(TAG, "Debug message")
}

// WRONG - runs in release builds
Log.d(TAG, "Debug message")
```

---

## State Consolidation in ChatScreenState

The following sets are consolidated into `ChatScreenState` (not created in composition):

```kotlin
// IN ChatScreenState.kt - single source of truth
val processedEffectMessages = mutableSetOf<String>()
val animatedMessageGuids = mutableSetOf<String>()
var revealedInvisibleInkMessages by mutableStateOf(setOf<String>())

// Helper methods
fun markEffectProcessed(guid: String) = processedEffectMessages.add(guid)
fun isEffectProcessed(guid: String) = guid in processedEffectMessages
fun markMessageAnimated(guid: String) = animatedMessageGuids.add(guid)
fun isMessageAnimated(guid: String) = guid in animatedMessageGuids
fun revealInvisibleInk(guid: String) { revealedInvisibleInkMessages += guid }
fun concealInvisibleInk(guid: String) { revealedInvisibleInkMessages -= guid }
```

**NEVER create these sets in composition body:**
```kotlin
// WRONG - creates new set on every composition
val processedEffectMessages = remember { mutableSetOf<String>() }

// CORRECT - use ChatScreenState
state.markEffectProcessed(guid)
```

---

## File Ownership Rules

To prevent merge conflicts, each parallel task owns specific files:

| Task File | Owned Files | Can Read (Not Modify) |
|-----------|-------------|----------------------|
| `02_parallel_wave1_chatscreenstate.md` | `ChatScreenState.kt` | `ChatScreen.kt` |
| `02_parallel_wave1_chatinputui.md` | `ChatInputUI.kt` | `ChatScreen.kt`, delegates |
| `02_parallel_wave1_chatmessagelist.md` | `ChatMessageList.kt`, `ChatScrollHelper.kt` | `ChatScreen.kt`, delegates |
| `02_parallel_wave1_dialogs.md` | `ChatDialogsHost.kt`, `ChatTopBar.kt`, `AnimatedThreadOverlay.kt` | `ChatScreen.kt`, delegates |
| `03_sequential_chatscreen.md` | `ChatScreen.kt` | All child composables |

---

## Import Patterns

### Required Imports for State Collection

```kotlin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
```

### Delegate Imports

```kotlin
import com.bothbubbles.ui.chat.delegates.ChatSendDelegate
import com.bothbubbles.ui.chat.delegates.ChatSearchDelegate
import com.bothbubbles.ui.chat.delegates.ChatSyncDelegate
import com.bothbubbles.ui.chat.delegates.ChatOperationsDelegate
import com.bothbubbles.ui.chat.delegates.ChatAttachmentDelegate
import com.bothbubbles.ui.chat.delegates.ChatComposerDelegate
import com.bothbubbles.ui.chat.delegates.ChatMessageListDelegate
import com.bothbubbles.ui.chat.delegates.ChatEtaSharingDelegate
import com.bothbubbles.ui.chat.delegates.ChatEffectsDelegate
import com.bothbubbles.ui.chat.delegates.ChatConnectionDelegate
import com.bothbubbles.ui.chat.delegates.ChatThreadDelegate
```

---

## Verification Checklist

Before marking your task complete, verify:

- [ ] All new state collections use `collectAsStateWithLifecycle()`
- [ ] All internal collections have `// PERF FIX:` comments
- [ ] No `LaunchedEffect(Unit)` reads external values without `rememberUpdatedState`
- [ ] No `Log.d` or timing calls without `BuildConfig.DEBUG` guard
- [ ] No `remember { mutableSetOf() }` for effect/animation tracking
- [ ] Build succeeds: `./gradlew assembleDebug`
- [ ] No new lint warnings in modified files

---

## Reference: Current ChatScreen State Collections

These are the collections being pushed down (from `ChatScreen.kt`):

```
Lines 69-82:   Main collections (12 flows)
Lines 163-170: Secondary collections (initialLoadComplete, isLoadingFromServer, autoDownloadEnabled, downloadingAttachments)
Lines 173-180: Duplicate sets to consolidate
Lines 191-194: forwardableChats, pendingAttachments
Lines 252-254: activePanelState, gifPickerState, gifSearchQuery
```
