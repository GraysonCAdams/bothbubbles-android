# Wave 1 (Parallel): ChatMessageList Independence

**Prerequisites**: Read `00_shared_conventions.md` first.

**Owned Files**: `ChatMessageList.kt`, `ChatScrollHelper.kt`
**Can Read**: `ChatScreen.kt`, delegate classes

---

## Objective

1. Make `ChatMessageList` collect its own state internally
2. Fix stale capture bugs in scroll/load-more logic
3. Prepare for duplicate set removal (will be completed in sequential phase)

---

## Tasks

### 1. Add Delegate Parameters to ChatMessageList

Update the `ChatMessageList` function signature:

```kotlin
@Composable
fun ChatMessageList(
    // NEW: Delegates for internal collection
    messageListDelegate: ChatMessageListDelegate,
    searchDelegate: ChatSearchDelegate,
    syncDelegate: ChatSyncDelegate,
    operationsDelegate: ChatOperationsDelegate,
    attachmentDelegate: ChatAttachmentDelegate,
    etaSharingDelegate: ChatEtaSharingDelegate,
    effectsDelegate: ChatEffectsDelegate,

    // State passed from ChatScreenState (not collected, passed by reference)
    chatScreenState: ChatScreenState,

    // DEPRECATED: Keep temporarily for backward compatibility
    @Deprecated("Collected internally from searchDelegate")
    searchState: SearchState? = null,
    @Deprecated("Collected internally from syncDelegate")
    syncState: SyncState? = null,
    @Deprecated("Collected internally from operationsDelegate")
    operationsState: OperationsState? = null,
    @Deprecated("Collected internally from effectsDelegate")
    effectsState: EffectsState? = null,
    @Deprecated("Collected internally from etaSharingDelegate")
    etaSharingState: EtaSharingState? = null,
    @Deprecated("Collected internally from messageListDelegate")
    isLoadingFromServer: Boolean? = null,
    @Deprecated("Collected internally from messageListDelegate")
    initialLoadComplete: Boolean? = null,
    @Deprecated("Collected internally from attachmentDelegate")
    autoDownloadEnabled: Boolean? = null,
    @Deprecated("Collected internally from attachmentDelegate")
    downloadingAttachments: Map<String, Float>? = null,

    // ... rest of existing parameters ...
)
```

### 2. Add Internal State Collection

At the top of `ChatMessageList`, add:

```kotlin
@Composable
fun ChatMessageList(
    // ... parameters ...
) {
    // PERF FIX: Collect state internally to avoid ChatScreen recomposition
    val searchStateInternal by searchDelegate.state.collectAsStateWithLifecycle()
    val syncStateInternal by syncDelegate.state.collectAsStateWithLifecycle()
    val operationsStateInternal by operationsDelegate.state.collectAsStateWithLifecycle()
    val effectsStateInternal by effectsDelegate.state.collectAsStateWithLifecycle()
    val etaSharingStateInternal by etaSharingDelegate.etaSharingState.collectAsStateWithLifecycle()
    val isLoadingFromServerInternal by messageListDelegate.isLoadingFromServer.collectAsStateWithLifecycle()
    val initialLoadCompleteInternal by messageListDelegate.initialLoadComplete.collectAsStateWithLifecycle()
    val autoDownloadEnabledInternal by attachmentDelegate.autoDownloadEnabled.collectAsStateWithLifecycle()

    // PERF FIX: Download progress collected per-bubble, not here
    // val downloadingAttachmentsInternal - DO NOT COLLECT HERE

    // Use internal values
    val effectiveSearchState = searchStateInternal
    val effectiveSyncState = syncStateInternal
    val effectiveOperationsState = operationsStateInternal
    val effectiveEffectsState = effectsStateInternal
    val effectiveEtaSharingState = etaSharingStateInternal
    val effectiveIsLoadingFromServer = isLoadingFromServerInternal
    val effectiveInitialLoadComplete = initialLoadCompleteInternal
    val effectiveAutoDownloadEnabled = autoDownloadEnabledInternal

    // ... rest of composable ...
}
```

### 3. Fix Stale Captures in LaunchedEffect(Unit) Blocks

Find all `LaunchedEffect(Unit)` blocks that read external values and fix them:

```kotlin
// BEFORE (around line 189 - socketNewMessageFlow)
LaunchedEffect(Unit) {
    socketNewMessageFlow.collect { newMessageGuid ->
        val message = messages.firstOrNull { it.guid == newMessageGuid }  // STALE!
        // ...
    }
}

// AFTER
val currentMessages by rememberUpdatedState(messages)
LaunchedEffect(Unit) {
    socketNewMessageFlow.collect { newMessageGuid ->
        val message = currentMessages.firstOrNull { it.guid == newMessageGuid }
        // ...
    }
}
```

### 4. Fix Stale Captures in ChatScrollHelper.kt

In `LoadMoreOnScroll` composable:

```kotlin
// BEFORE
@Composable
fun LoadMoreOnScroll(
    listState: LazyListState,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit
) {
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (canLoadMore && !isLoadingMore && index < 5) {  // STALE!
                    onLoadMore()
                }
            }
    }
}

// AFTER
@Composable
fun LoadMoreOnScroll(
    listState: LazyListState,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit
) {
    val currentCanLoadMore by rememberUpdatedState(canLoadMore)
    val currentIsLoadingMore by rememberUpdatedState(isLoadingMore)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (currentCanLoadMore && !currentIsLoadingMore && index < 5) {
                    currentOnLoadMore()
                }
            }
    }
}
```

### 5. Prepare for Duplicate Set Removal

**DO NOT remove the duplicate sets yet** - that will happen in the sequential phase. But add comments marking them:

```kotlin
// TODO(Wave2): Remove - now using chatScreenState.processedEffectMessages
val processedEffectMessages = remember { mutableSetOf<String>() }

// TODO(Wave2): Remove - now using chatScreenState.animatedMessageGuids
val animatedMessageGuids = remember { mutableSetOf<String>() }

// TODO(Wave2): Remove - now using chatScreenState.revealedInvisibleInkMessages
var revealedInvisibleInkMessages by remember { mutableStateOf(setOf<String>()) }
```

### 6. Add Required Imports

```kotlin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.bothbubbles.ui.chat.delegates.ChatMessageListDelegate
import com.bothbubbles.ui.chat.delegates.ChatSearchDelegate
import com.bothbubbles.ui.chat.delegates.ChatSyncDelegate
import com.bothbubbles.ui.chat.delegates.ChatOperationsDelegate
import com.bothbubbles.ui.chat.delegates.ChatAttachmentDelegate
import com.bothbubbles.ui.chat.delegates.ChatEtaSharingDelegate
import com.bothbubbles.ui.chat.delegates.ChatEffectsDelegate
```

---

## Verification

- [ ] All new collections use `collectAsStateWithLifecycle()`
- [ ] All internal collections have `// PERF FIX:` comments
- [ ] All `LaunchedEffect(Unit)` blocks use `rememberUpdatedState` for external values
- [ ] `ChatScrollHelper.kt` stale captures are fixed
- [ ] Duplicate sets are marked with `// TODO(Wave2)` comments
- [ ] `downloadingAttachments` is NOT collected at this level (per-bubble only)
- [ ] Build succeeds: `./gradlew assembleDebug`

---

## Notes

- Do NOT modify `ChatScreen.kt` - those changes happen in `03_sequential_chatscreen.md`
- Do NOT remove the duplicate sets yet - just mark them with TODO comments
- Download progress will be collected at the `MessageBubble` level for per-attachment granularity
