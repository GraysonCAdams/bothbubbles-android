# Wave 1 (Parallel): ChatScreenState Consolidation

**Prerequisites**: Read `00_shared_conventions.md` first.

**Owned Files**: `ChatScreenState.kt`
**Can Read**: `ChatScreen.kt`, `ChatMessageList.kt`

---

## Objective

Consolidate the duplicate effect/animation tracking sets into `ChatScreenState` as the single source of truth.

---

## Tasks

### 1. Add Consolidated State Properties

Add these properties to the `ChatScreenState` class:

```kotlin
@Stable
class ChatScreenState(
    val listState: LazyListState,
    val snackbarHostState: SnackbarHostState,
    val coroutineScope: CoroutineScope
) {
    // ... existing properties ...

    // ===== NEW: Consolidated effect/animation tracking =====

    /** Track screen effects that have been triggered this session */
    val processedEffectMessages = mutableSetOf<String>()

    /** Track messages that have been animated (prevents re-animation on recompose) */
    val animatedMessageGuids = mutableSetOf<String>()

    /** Track revealed invisible ink messages (resets when leaving chat) */
    var revealedInvisibleInkMessages by mutableStateOf(setOf<String>())
        private set
}
```

### 2. Add Helper Methods

Add these helper methods to `ChatScreenState`:

```kotlin
// ===== Effect tracking =====
fun markEffectProcessed(guid: String): Boolean = processedEffectMessages.add(guid)
fun isEffectProcessed(guid: String): Boolean = guid in processedEffectMessages

// ===== Animation tracking =====
fun markMessageAnimated(guid: String): Boolean = animatedMessageGuids.add(guid)
fun isMessageAnimated(guid: String): Boolean = guid in animatedMessageGuids
fun markInitialMessagesAnimated(guids: Collection<String>) {
    animatedMessageGuids.addAll(guids)
}

// ===== Invisible ink tracking =====
fun revealInvisibleInk(guid: String) {
    revealedInvisibleInkMessages = revealedInvisibleInkMessages + guid
}
fun concealInvisibleInk(guid: String) {
    revealedInvisibleInkMessages = revealedInvisibleInkMessages - guid
}
fun isInvisibleInkRevealed(guid: String): Boolean = guid in revealedInvisibleInkMessages
```

### 3. Add Required Imports

Ensure these imports are present:

```kotlin
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
```

---

## Verification

- [ ] `ChatScreenState` compiles without errors
- [ ] All helper methods are present and correctly implemented
- [ ] `revealedInvisibleInkMessages` uses `mutableStateOf` (not `mutableSetOf`) for Compose reactivity
- [ ] Build succeeds: `./gradlew assembleDebug`

---

## Notes

- Do NOT modify `ChatScreen.kt` or `ChatMessageList.kt` - those changes happen in `03_sequential_chatscreen.md`
- The old `remember { mutableSetOf<String>() }` calls will be removed from `ChatScreen.kt` and `ChatMessageList.kt` during the sequential phase
- This task is purely additive - no breaking changes
