# Wave 2 (Sequential): ChatScreen Cutover

**Prerequisites**:
- Read `00_shared_conventions.md` first
- ALL Wave 1 tasks must be complete and merged

**Owned Files**: `ChatScreen.kt`
**Dependencies**: All Wave 1 changes must be complete

---

## Objective

Update `ChatScreen.kt` to:
1. Pass delegates instead of collected state values
2. Remove state collections that are now handled internally by children
3. Remove duplicate effect/animation tracking sets
4. Use `ChatScreenState` for consolidated state

---

## Pre-Flight Check

Before starting, verify Wave 1 is complete:

- [ ] `ChatScreenState.kt` has consolidated sets and helper methods
- [ ] `ChatInputUI.kt` accepts delegate parameters and collects internally
- [ ] `ChatMessageList.kt` accepts delegate parameters and collects internally
- [ ] `ChatDialogsHost.kt` accepts delegate parameters and collects internally
- [ ] `ChatTopBar.kt` accepts delegate parameters and collects internally
- [ ] `AnimatedThreadOverlay.kt` accepts delegate parameters and collects internally
- [ ] Build succeeds with all Wave 1 changes

---

## Tasks

### 1. Remove State Collections (Lines 69-82, 163-170, 191-194, 252-254)

Remove these collections from `ChatScreen.kt`:

```kotlin
// REMOVE these lines:
val sendState by viewModel.send.state.collectAsStateWithLifecycle()
val searchState by viewModel.search.state.collectAsStateWithLifecycle()
val operationsState by viewModel.operations.state.collectAsStateWithLifecycle()
val syncState by viewModel.sync.state.collectAsStateWithLifecycle()
val effectsState by viewModel.effects.state.collectAsStateWithLifecycle()
val threadState by viewModel.thread.state.collectAsStateWithLifecycle()
val smartReplySuggestions by viewModel.composer.smartReplySuggestions.collectAsStateWithLifecycle()
val connectionState by viewModel.connection.state.collectAsStateWithLifecycle()
val etaSharingState by viewModel.etaSharing.etaSharingState.collectAsStateWithLifecycle()

val initialLoadComplete by viewModel.messageList.initialLoadComplete.collectAsStateWithLifecycle()
val isLoadingFromServer by viewModel.messageList.isLoadingFromServer.collectAsStateWithLifecycle()
val autoDownloadEnabled by viewModel.attachment.autoDownloadEnabled.collectAsStateWithLifecycle()
val downloadingAttachments by viewModel.attachment.downloadProgress.collectAsStateWithLifecycle()

val forwardableChats by viewModel.getForwardableChats().collectAsStateWithLifecycle(initialValue = emptyList())

val activePanelState by viewModel.composer.activePanelState.collectAsStateWithLifecycle()
val gifPickerState by viewModel.composer.gifPickerState.collectAsStateWithLifecycle()
val gifSearchQuery by viewModel.composer.gifSearchQuery.collectAsStateWithLifecycle()
```

**Keep these** (still needed at ChatScreen level):
```kotlin
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
val messages by viewModel.messageList.messagesState.collectAsStateWithLifecycle()
val chatInfoState by viewModel.chatInfo.state.collectAsStateWithLifecycle()
val pendingAttachments by viewModel.composer.pendingAttachments.collectAsStateWithLifecycle()
```

### 2. Remove Duplicate Sets (Lines 173-180)

Remove these lines:

```kotlin
// REMOVE these lines:
val processedEffectMessages = remember { mutableSetOf<String>() }
var revealedInvisibleInkMessages by remember { mutableStateOf(setOf<String>()) }
val animatedMessageGuids = remember { mutableSetOf<String>() }
```

Update references to use `state.*` instead:
- `processedEffectMessages` → `state.processedEffectMessages` or `state.markEffectProcessed()`
- `revealedInvisibleInkMessages` → `state.revealedInvisibleInkMessages`
- `animatedMessageGuids` → `state.animatedMessageGuids`

### 3. Update ChatInputUI Call Site (Lines 351-384)

```kotlin
// BEFORE
ChatInputUI(
    composerDelegate = viewModel.composer,
    audioState = audioState,
    sendState = sendState,
    smartReplySuggestions = smartReplySuggestions,
    replyingToMessage = replyingToMessage,
    // ...
)

// AFTER
ChatInputUI(
    // Delegates for internal collection
    sendDelegate = viewModel.send,
    attachmentDelegate = viewModel.attachment,
    composerDelegate = viewModel.composer,
    messageListDelegate = viewModel.messageList,  // For replyingToMessage calculation
    audioState = audioState,
    // Remove: sendState, smartReplySuggestions, replyingToMessage, activePanelState, etc.
    // ...
)
```

### 4. Update ChatMessageList Call Site (Lines 413-519)

```kotlin
// BEFORE
ChatMessageList(
    messages = messages,
    chatInfoState = chatInfoState,
    sendState = sendState,
    syncState = syncState,
    searchState = searchState,
    operationsState = operationsState,
    effectsState = effectsState,
    etaSharingState = etaSharingState,
    downloadingAttachments = downloadingAttachments,
    // ...
)

// AFTER
ChatMessageList(
    // Delegates for internal collection
    messageListDelegate = viewModel.messageList,
    searchDelegate = viewModel.search,
    syncDelegate = viewModel.sync,
    operationsDelegate = viewModel.operations,
    attachmentDelegate = viewModel.attachment,
    etaSharingDelegate = viewModel.etaSharing,
    effectsDelegate = viewModel.effects,

    // ChatScreenState for consolidated state
    chatScreenState = state,

    // Still passed (needed at this level)
    messages = messages,
    chatInfoState = chatInfoState,
    listState = state.listState,
    callbacks = callbacks,
    // ...

    // Remove: sendState, syncState, searchState, operationsState, effectsState,
    //         etaSharingState, downloadingAttachments, isLoadingFromServer,
    //         initialLoadComplete, autoDownloadEnabled, processedEffectMessages,
    //         animatedMessageGuids, revealedInvisibleInkMessages
)
```

### 5. Update ChatDialogsHost Call Site (Lines 550-601)

```kotlin
// BEFORE
ChatDialogsHost(
    viewModel = viewModel,
    context = context,
    chatInfoState = chatInfoState,
    connectionState = connectionState,
    forwardableChats = forwardableChats,
    isWhatsAppAvailable = isWhatsAppAvailable,
    // ...
)

// AFTER
ChatDialogsHost(
    viewModel = viewModel,
    context = context,
    connectionDelegate = viewModel.connection,
    chatInfoState = chatInfoState,
    // Remove: connectionState, forwardableChats, isWhatsAppAvailable
    // ...
)
```

### 6. Update ChatTopBar Call Site

```kotlin
// BEFORE
ChatTopBar(
    infoState = chatInfoState,
    operationsState = operationsState,
    // ...
)

// AFTER
ChatTopBar(
    operationsDelegate = viewModel.operations,
    chatInfoDelegate = viewModel.chatInfo,
    // Remove: infoState, operationsState
    // ...
)
```

### 7. Update AnimatedThreadOverlay Call Site

```kotlin
// BEFORE
AnimatedVisibility(visible = threadState.isActive) {
    AnimatedThreadOverlay(
        threadOverlayState = threadState,
        // ...
    )
}

// AFTER
AnimatedThreadOverlay(
    threadDelegate = viewModel.thread,
    // Remove: threadOverlayState
    // Visibility handled internally
)
```

### 8. Update Screen Effect LaunchedEffect

```kotlin
// BEFORE
LaunchedEffect(messages.firstOrNull()?.guid) {
    val newest = messages.firstOrNull() ?: return@LaunchedEffect
    if (newest.guid in processedEffectMessages) return@LaunchedEffect
    // ...
    processedEffectMessages.add(newest.guid)
}

// AFTER
LaunchedEffect(messages.firstOrNull()?.guid) {
    val newest = messages.firstOrNull() ?: return@LaunchedEffect
    if (state.isEffectProcessed(newest.guid)) return@LaunchedEffect
    // ...
    state.markEffectProcessed(newest.guid)
}
```

### 9. Remove replyingToMessage Calculation

Remove this derivedStateOf block (now in ChatInputUI):

```kotlin
// REMOVE
val replyingToMessage by remember {
    derivedStateOf {
        sendState.replyingToGuid?.let { guid ->
            messages.firstOrNull { it.guid == guid }
        }
    }
}
```

### 10. Remove isWhatsAppAvailable Calculation

Remove this (now in ChatDialogsHost):

```kotlin
// REMOVE
val isWhatsAppAvailable = remember { viewModel.operations.isWhatsAppAvailable(context) }
```

---

## Verification

- [ ] All removed collections are no longer in `ChatScreen.kt`
- [ ] All child composables receive delegates instead of state values
- [ ] `ChatScreenState` is used for effect/animation tracking
- [ ] No `remember { mutableSetOf() }` for tracking sets
- [ ] Build succeeds: `./gradlew assembleDebug`
- [ ] App runs correctly - all features still work
- [ ] Layout Inspector shows reduced recomposition on:
  - Typing in composer
  - Downloading attachments
  - Syncing messages
  - Opening dialogs

---

## Notes

- This is the critical cutover phase - test thoroughly
- If issues arise, the deprecated parameters in child components allow rollback
- After verification, proceed to Wave 3 to remove deprecated parameters
