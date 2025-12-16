# Wave 3G (Parallel): Callbacks Stabilization

**Prerequisites**:
- Read `00_shared_conventions.md` first
- Wave 2 (sequential ChatScreen cutover) must be complete

**Owned Files**: `ChatScreen.kt`, `ChatSearchDelegate.kt` (if callback refactor needed)

---

## Objective

1. Wrap `MessageListCallbacks` in `remember` for stability
2. Refactor callbacks that capture the `messages` list to use delegate lookups

---

## Tasks

### 1. Wrap MessageListCallbacks in remember

Find the `MessageListCallbacks` creation (around line 440-484):

```kotlin
// BEFORE - recreated on every recomposition
val callbacks = MessageListCallbacks(
    onMediaClick = onMediaClick,
    onToggleReaction = { msg, reaction -> viewModel.toggleReaction(msg, reaction) },
    onRetry = { message -> state.selectedMessageForRetry = message },
    // ... many more callbacks
)

// AFTER - stable reference
val callbacks = remember(viewModel) {
    MessageListCallbacks(
        onMediaClick = onMediaClick,
        onToggleReaction = viewModel::toggleReaction,  // Method reference
        onRetry = { message -> /* needs state access */ },
        // ...
    )
}
```

### 2. Handle Callbacks That Need State Access

Some callbacks need access to `state` (ChatScreenState). Use `rememberUpdatedState`:

```kotlin
val currentState by rememberUpdatedState(state)

val callbacks = remember(viewModel) {
    MessageListCallbacks(
        // Callbacks that need state access use the updated reference
        onRetry = { message -> currentState.selectedMessageForRetry = message },
        onSelectForTapback = { message, bounds ->
            currentState.selectedMessageForTapback = message
            currentState.selectedMessageBounds = bounds
        },
        // ...
    )
}
```

### 3. Refactor Callbacks Capturing messages List

Find callbacks that capture `messages`:

```kotlin
// BEFORE - captures messages list, making callback unstable
onSearchQueryChange = { query ->
    viewModel.search.updateSearchQuery(query, messages)  // BAD: captures messages
}
```

**Option A: Move lookup into delegate**

```kotlin
// In ChatSearchDelegate.kt
fun updateSearchQuery(query: String) {
    // Get messages from messageListDelegate internally
    val messages = messageListDelegate.messagesState.value
    // ... search logic
}

// In ChatScreen.kt
onSearchQueryChange = viewModel.search::updateSearchQuery  // Clean method reference
```

**Option B: Pass delegate instead of list**

```kotlin
// If delegate can't be modified, pass accessor
onSearchQueryChange = { query ->
    viewModel.search.updateSearchQuery(query) {
        viewModel.messageList.messagesState.value
    }
}
```

### 4. Use Method References Where Possible

Convert lambda callbacks to method references:

```kotlin
// BEFORE
onToggleReaction = { msg, reaction -> viewModel.toggleReaction(msg, reaction) }
onDeleteMessage = { guid -> viewModel.operations.deleteMessage(guid) }
onStarMessage = { guid -> viewModel.operations.starMessage(guid) }

// AFTER
onToggleReaction = viewModel::toggleReaction
onDeleteMessage = viewModel.operations::deleteMessage
onStarMessage = viewModel.operations::starMessage
```

### 5. Verify Callback Stability

After changes, the callbacks object should be stable:

```kotlin
// This should NOT trigger recomposition of ChatMessageList
// when unrelated state changes
val callbacks = remember(viewModel) { ... }

ChatMessageList(
    callbacks = callbacks,  // Stable reference
    // ...
)
```

---

## Verification

- [ ] `MessageListCallbacks` wrapped in `remember(viewModel)`
- [ ] No callbacks directly capture `messages` list
- [ ] Method references used where possible
- [ ] Callbacks that need `state` use `rememberUpdatedState`
- [ ] Build succeeds: `./gradlew assembleDebug`
- [ ] Callback functionality still works (reactions, retry, tapback, etc.)

---

## Notes

- `remember(viewModel)` ensures callbacks are recreated only when ViewModel changes (which is never during a chat session)
- Method references (`viewModel::method`) are inherently stable
- `rememberUpdatedState` gives callbacks access to current state without recreating the callback
