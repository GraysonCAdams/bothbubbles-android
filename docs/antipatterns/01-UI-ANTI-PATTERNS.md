# UI Layer Anti-Patterns

**Layer:** `app/src/main/kotlin/com/bothbubbles/ui/`
**Files Scanned:** 367 Kotlin files

---

## Critical Issues

### 1. Mutable Collections in State (Violates MANDATORY Rule)

**Location:** `ui/chat/state/SearchState.kt` (Lines 14, 17)

**Issue:**
```kotlin
data class SearchState(
    // ...
    val matchIndices: List<Int> = emptyList(),  // Line 14: Mutable List
    val databaseResults: List<ChatSearchDelegate.SearchResult> = emptyList(),  // Line 17: Mutable List
)
```

**Rule Violated:** CLAUDE.md states: "ALWAYS use `ImmutableList` / `ImmutableMap` from `kotlinx.collections.immutable` in UI state"

**Why Problematic:**
- Mutable `List<T>` types are considered unstable by Compose
- Causes unnecessary recompositions throughout the component tree
- Breaks performance optimizations that rely on parameter stability
- Can trigger cascade recompositions when state updates

**Fix:**
```kotlin
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class SearchState(
    val matchIndices: ImmutableList<Int> = persistentListOf(),
    val databaseResults: ImmutableList<ChatSearchDelegate.SearchResult> = persistentListOf(),
)
```

---

## High Severity Issues

### 2. Lambda Capturing State (Unstable Callbacks)

**Location:** `ui/chat/ChatScreen.kt` (Lines 505-536)

**Issue:**
```kotlin
ChatInputUI(
    // 15+ lambda callbacks that capture state
    onComposerEvent = { event -> viewModel.onComposerEvent(event) },
    onMediaSelected = { uris -> viewModel.composer.addAttachments(uris) },
    onFileClick = { filePickerLauncher.launch(arrayOf("*/*")) },
    onLocationClick = { ... },
    onContactClick = { ... },
    onEtaClick = { viewModel.etaSharing.startSharingEta(...) },
    onGifSearchQueryChange = { viewModel.composer.updateGifSearchQuery(it) },
    onGifSearch = { viewModel.composer.searchGifs(it) },
    onGifSelected = { gif -> viewModel.composer.selectGif(gif) },
    // ... 6+ more lambdas
)
```

**Rule Violated:** "ALWAYS use method references (`viewModel::method`) instead of lambdas capturing state"

**Why Problematic:**
- Each lambda capturing state creates a new instance on every recomposition
- ChatInputUI recomposes whenever ChatScreen recomposes
- Defeats smart skip optimization
- Creates unnecessary object allocations

**Fix:**
```kotlin
// Use method references where possible
ChatInputUI(
    onComposerEvent = viewModel::onComposerEvent,
    onMediaSelected = viewModel.composer::addAttachments,
    // Or pass the delegate directly and let child handle routing
    composerDelegate = viewModel.composer,
)
```

---

### 3. I/O Operations in Composition

**Location:** `ui/conversations/ConversationsScreen.kt` (Lines 263-273)

**Issue:**
```kotlin
LaunchedEffect(quickActionsContact) {
    quickActionsContact?.let { contact ->
        if (contact.hasContact && !contact.isGroup) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                isQuickActionContactStarred = viewModel.isContactStarred(contact.address)
            }
        } else {
            isQuickActionContactStarred = false
        }
    }
}
```

**Rule Violated:** "NEVER put logging, I/O, or complex calculations in the composition path"

**Why Problematic:**
- Database query executed during composition cycle
- Adds I/O latency to recomposition when `quickActionsContact` changes
- Business logic belongs in ViewModel, not composition

**Fix:**
```kotlin
// In ViewModel or delegate:
val isQuickActionContactStarred: StateFlow<Boolean> =
    quickActionsContact.filterNotNull()
        .flatMapLatest { contact ->
            if (contact.hasContact && !contact.isGroup) {
                repository.observeIsContactStarred(contact.address)
            } else {
                flowOf(false)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

// In Composable:
val isQuickActionContactStarred by viewModel.isQuickActionContactStarred
    .collectAsStateWithLifecycle()
```

---

### 4. ChatInputUI Lambda Event Handler

**Location:** `ui/chat/ChatInputUI.kt` (Lines 174-219)

**Issue:**
```kotlin
ChatComposer(
    state = adjustedComposerState,
    onEvent = { event ->  // Lambda capturing multiple outer states
        when (event) {
            is ComposerEvent.OpenCamera -> onCameraClick()
            is ComposerEvent.SendLongPress -> {
                if (!isLocalSmsChat && !isBubbleMode) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onShowEffectPicker()
                }
            }
            // ... more branches
        }
    },
)
```

**Why Problematic:**
- Lambda captures `isLocalSmsChat`, `isBubbleMode`, `hapticFeedback` from outer scope
- New lambda instance created on every recomposition
- Causes ChatComposer to recompose unnecessarily

**Fix:**
```kotlin
// Move event handling to delegate with stable method reference
ChatComposer(
    state = adjustedComposerState,
    onEvent = composerDelegate::handleComposerEvent,
)
```

---

## Medium Severity Issues

### 5. Parent Collecting State for Children (Prop Drilling)

**Location:** `ui/chat/ChatScreen.kt` (Lines 314, 319, 341)

**Issue:**
```kotlin
// ChatScreen collects state...
val etaSharingState by viewModel.etaSharing.etaSharingState.collectAsStateWithLifecycle()
val activePanelState by viewModel.composer.activePanel.collectAsStateWithLifecycle()
val effectsStateForOverlay by viewModel.effects.state.collectAsStateWithLifecycle()

// ...then passes derived values to children
ChatInputUI(
    isEtaSharingAvailable = etaSharingState.isEnabled &&
                           etaSharingState.isNavigationActive &&
                           !etaSharingState.isCurrentlySharing,
)
```

**Rule Violated:** "NEVER collect state in a parent just to pass it down. Push state collection to the lowest possible child."

**Why Problematic:**
- ChatScreen recomposes when any collected state changes
- Children recompose even if they don't use the changed state
- Defeats leaf-node state collection pattern

**Fix:**
- Let ChatInputUI collect `etaSharingState` internally from the delegate
- Pass the delegate reference, not the derived state values

---

### 6. Empty LaunchedEffect Dependencies

**Location:** `ui/conversations/ConversationsScreen.kt` (Lines 215-219, 222-232)

**Issue:**
```kotlin
LaunchedEffect(Unit) {
    viewModel.scrollToIndexEvent.collect { index ->
        listState.animateScrollToItem(index)
    }
}

LaunchedEffect(Unit) {
    viewModel.newMessageEvent.collect {
        // ...
    }
}
```

**Why Problematic:**
- `LaunchedEffect(Unit)` restarts on every recomposition if parent recomposes
- Multiple subscriptions can accumulate
- Events may be missed during restart

**Fix:**
```kotlin
// Use stable keys or no Unit dependency
LaunchedEffect(viewModel) {  // Stable key
    viewModel.scrollToIndexEvent.collect { index ->
        listState.animateScrollToItem(index)
    }
}
```

---

### 7. Expensive Remember Blocks

**Location:** `ui/chat/ChatInputUI.kt` (Lines 147-170)

**Issue:**
```kotlin
val adjustedComposerState = remember(
    composerState,
    audioState.isRecording,
    audioState.isPreviewingVoiceMemo,
    audioState.recordingDuration,
    audioState.playbackPosition,
    audioState.isPlayingVoiceMemo  // 6 dependencies
) {
    if (audioState.isRecording || audioState.isPreviewingVoiceMemo) {
        composerState.copy(
            inputMode = if (audioState.isRecording) ComposerInputMode.VOICE_RECORDING
                        else ComposerInputMode.VOICE_PREVIEW,
            recordingState = RecordingState(
                durationMs = audioState.recordingDuration,
                amplitudeHistory = audioState.amplitudeHistory,
                // ... multiple field copies
            )
        )
    } else {
        composerState.copy(inputMode = ComposerInputMode.TEXT)
    }
}
```

**Why Problematic:**
- 6 dependencies means this runs frequently during audio recording
- `RecordingState` construction is moderately expensive
- This derivation should be in delegate as a derived StateFlow

**Fix:**
Move the merging logic to `ChatComposerDelegate`:
```kotlin
val adjustedComposerState: StateFlow<ComposerState> = combine(
    composerStateFlow,
    audioStateFlow
) { composer, audio -> /* merge */ }
    .stateIn(scope, SharingStarted.Lazily, initialState)
```

---

### 8. Derivations in Composition

**Location:** `ui/conversations/ConversationsScreen.kt` (Lines 99-122)

**Issue:**
```kotlin
val conversationFilter = remember(uiState.conversationFilter) {
    ConversationFilter.entries.find {
        it.name.lowercase() == uiState.conversationFilter.lowercase()
    } ?: ConversationFilter.ALL
}

val categoryFilter = remember(uiState.categoryFilter) {
    uiState.categoryFilter?.let { savedCategory ->
        MessageCategory.entries.find { it.name.equals(savedCategory, ignoreCase = true) }
    }
}

val enabledCategories = remember(...) {
    buildSet {
        if (uiState.transactionsEnabled) add(MessageCategory.TRANSACTIONS)
        if (uiState.deliveriesEnabled) add(MessageCategory.DELIVERIES)
        // ...
    }
}
```

**Why Problematic:**
- Enum lookups are O(N) and run on every recomposition with changed dependencies
- Set construction happens in composition
- These derivations belong in ViewModel

**Fix:**
Move to ViewModel as derived StateFlows:
```kotlin
val selectedConversationFilter: StateFlow<ConversationFilter> =
    uiState.map { /* derive */ }
        .stateIn(viewModelScope, SharingStarted.Lazily, default)
```

---

## Summary Table

| Issue | Severity | File | Lines | Quick Fix |
|-------|----------|------|-------|-----------|
| Mutable Collections in State | CRITICAL | SearchState.kt | 14, 17 | Use ImmutableList |
| Lambda Capturing (ChatScreen) | HIGH | ChatScreen.kt | 505-536 | Use method references |
| I/O in LaunchedEffect | HIGH | ConversationsScreen.kt | 263-273 | Move to ViewModel |
| Lambda Capturing (ChatInputUI) | HIGH | ChatInputUI.kt | 174-219 | Pass delegate |
| Parent Collecting State | MEDIUM | ChatScreen.kt | 314, 319 | Push collection down |
| LaunchedEffect(Unit) | MEDIUM | ConversationsScreen.kt | 215-232 | Use stable keys |
| Expensive Remember | MEDIUM | ChatInputUI.kt | 147-170 | Move to delegate |
| Derivations in Composition | MEDIUM | ConversationsScreen.kt | 99-122 | Move to ViewModel |

---

## Positive Findings

The codebase shows good patterns in many areas:
- Proper use of `@Stable` and `@Immutable` annotations
- Delegate pattern for ViewModel decomposition
- StateFlow usage throughout for reactive state
- `collectAsStateWithLifecycle()` for lifecycle-aware collection
- Comments indicating awareness of recomposition optimization
