# Wave 3F (Parallel): Height State Consolidation

**Prerequisites**:
- Read `00_shared_conventions.md` first
- Wave 2 (sequential ChatScreen cutover) must be complete

**Owned Files**: `ChatScreen.kt`, `ChatScreenState.kt`

---

## Objective

Move height tracking state from `ChatScreen.kt` into `ChatScreenState` for consistency with other local state.

---

## Tasks

### 1. Add Height Properties to ChatScreenState

In `ChatScreenState.kt`, add:

```kotlin
@Stable
class ChatScreenState(
    val listState: LazyListState,
    val snackbarHostState: SnackbarHostState,
    val coroutineScope: CoroutineScope
) {
    // ... existing properties ...

    // ===== Layout state =====

    /** Height of the top bar in pixels */
    var topBarHeightPx by mutableStateOf(0f)

    /** Base height of the bottom bar (minimum, excluding keyboard/panels) */
    var bottomBarBaseHeightPx by mutableStateOf(0f)

    // Already exists:
    // var composerHeightPx by mutableStateOf(0f)
    // var sendButtonBounds by mutableStateOf(Rect.Zero)
}
```

### 2. Remove from ChatScreen.kt

Find and remove these lines (around lines 270-276):

```kotlin
// REMOVE these lines from ChatScreen.kt:
var topBarHeightPx by remember { mutableStateOf(0f) }
var bottomBarBaseHeightPx by remember { mutableStateOf(0f) }
```

### 3. Update References in ChatScreen.kt

Replace all usages:

```kotlin
// BEFORE
topBarHeightPx = it.toFloat()
bottomBarBaseHeightPx = it.toFloat()
val padding = topBarHeightPx + bottomBarBaseHeightPx

// AFTER
state.topBarHeightPx = it.toFloat()
state.bottomBarBaseHeightPx = it.toFloat()
val padding = state.topBarHeightPx + state.bottomBarBaseHeightPx
```

### 4. Update onSizeChanged Callbacks

Find the `Modifier.onSizeChanged` callbacks and update:

```kotlin
// BEFORE
ChatTopBar(
    modifier = Modifier.onSizeChanged { topBarHeightPx = it.height.toFloat() }
)

// AFTER
ChatTopBar(
    modifier = Modifier.onSizeChanged { state.topBarHeightPx = it.height.toFloat() }
)
```

### 5. Verify Lambda Provider Pattern

If height is passed to children via lambda provider, ensure it still works:

```kotlin
// Should still work - reads from state
composerHeightPxProvider = { state.composerHeightPx }
```

---

## Verification

- [ ] No local `mutableStateOf` for height tracking in `ChatScreen.kt`
- [ ] `topBarHeightPx` and `bottomBarBaseHeightPx` are in `ChatScreenState`
- [ ] All height references use `state.*`
- [ ] Layout still works correctly (padding, insets)
- [ ] Build succeeds: `./gradlew assembleDebug`

---

## Notes

- This is a simple move - behavior should be identical
- Height state was already partially in `ChatScreenState` (`composerHeightPx`, `sendButtonBounds`)
- This consolidation makes all layout-related state consistent
