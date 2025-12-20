# UI Layer Anti-Patterns

**Layer:** `app/src/main/kotlin/com/bothbubbles/ui/`
**Files Scanned:** 367 Kotlin files

---

## Critical Issues

### 1. Mutable Collections in State (Violates MANDATORY Rule) - **FIXED**

**Location:** `ui/chat/state/SearchState.kt` (Lines 14, 17)

**Status:** ✅ FIXED - Changed to `ImmutableList<T>` with `persistentListOf()` defaults

**Previous Issue:**
```kotlin
data class SearchState(
    // ...
    val matchIndices: List<Int> = emptyList(),  // Line 14: Mutable List
    val databaseResults: List<ChatSearchDelegate.SearchResult> = emptyList(),  // Line 17: Mutable List
)
```

**Fix Applied:**
```kotlin
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class SearchState(
    val matchIndices: ImmutableList<Int> = persistentListOf(),
    val databaseResults: ImmutableList<ChatSearchDelegate.SearchResult> = persistentListOf(),
)
```

**Also Updated:**
- `ChatSearchDelegate.kt`: All usages now use `toImmutableList()` or `persistentListOf()`

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

### 3. I/O Operations in Composition - **FIXED**

**Location:** `ui/conversations/ConversationsScreen.kt` (Lines 263-273)

**Status:** ✅ FIXED - Removed async I/O from LaunchedEffect, now uses synchronous ViewModel call in `remember`

**Previous Issue:**
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

**Fix Applied:**
```kotlin
// Derive starred status from contact info - ViewModel handles the I/O
val isQuickActionContactStarred = remember(quickActionsContact) {
    quickActionsContact?.let { contact ->
        if (contact.hasContact && !contact.isGroup) {
            viewModel.isContactStarred(contact.address)
        } else {
            false
        }
    } ?: false
}
```

**Note:** The `isContactStarred()` method in ViewModel is synchronous and caches contact data, avoiding I/O in the composition path.

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

### 6. Empty LaunchedEffect Dependencies - **FIXED**

**Location:** `ui/conversations/ConversationsScreen.kt` (Lines 215-219, 222-232)

**Status:** ✅ FIXED - Changed from `LaunchedEffect(Unit)` to `LaunchedEffect(viewModel)` for stable keys

**Previous Issue:**
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

**Fix Applied:**
```kotlin
// Use stable keys to prevent restarts on recomposition
LaunchedEffect(viewModel) {  // Stable key
    viewModel.scrollToIndexEvent.collect { index ->
        listState.animateScrollToItem(index)
    }
}

LaunchedEffect(viewModel) {  // Stable key
    viewModel.newMessageEvent.collect {
        // ...
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

### 8. Derivations in Composition - **FIXED**

**Location:** `ui/conversations/ConversationsScreen.kt` (Lines 99-122)

**Status:** ✅ FIXED - Moved enum lookups and set construction to ViewModel as derived StateFlows

**Previous Issue:**
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

**Fix Applied:**

**In ConversationsViewModel.kt:**
```kotlin
val selectedConversationFilter: StateFlow<ConversationFilter> = _uiState.map { state ->
    ConversationFilter.entries.find {
        it.name.lowercase() == state.conversationFilter.lowercase()
    } ?: ConversationFilter.ALL
}.stateIn(viewModelScope, SharingStarted.Lazily, ConversationFilter.ALL)

val selectedCategoryFilter: StateFlow<MessageCategory?> = _uiState.map { state ->
    state.categoryFilter?.let { savedCategory ->
        MessageCategory.entries.find {
            it.name.equals(savedCategory, ignoreCase = true)
        }
    }
}.stateIn(viewModelScope, SharingStarted.Lazily, null)

val enabledCategories: StateFlow<Set<MessageCategory>> = combine(
    _uiState.map { it.transactionsEnabled }.distinctUntilChanged(),
    _uiState.map { it.deliveriesEnabled }.distinctUntilChanged(),
    _uiState.map { it.promotionsEnabled }.distinctUntilChanged(),
    _uiState.map { it.remindersEnabled }.distinctUntilChanged()
) { transactionsEnabled, deliveriesEnabled, promotionsEnabled, remindersEnabled ->
    buildSet {
        if (transactionsEnabled) add(MessageCategory.TRANSACTIONS)
        if (deliveriesEnabled) add(MessageCategory.DELIVERIES)
        if (promotionsEnabled) add(MessageCategory.PROMOTIONS)
        if (remindersEnabled) add(MessageCategory.REMINDERS)
    }
}.stateIn(viewModelScope, SharingStarted.Lazily, emptySet())
```

**In ConversationsScreen.kt:**
```kotlin
// Collect derived filter states from ViewModel (moved from composition to avoid enum lookups in UI)
val conversationFilter by viewModel.selectedConversationFilter.collectAsStateWithLifecycle()
val categoryFilter by viewModel.selectedCategoryFilter.collectAsStateWithLifecycle()
val enabledCategories by viewModel.enabledCategories.collectAsStateWithLifecycle()
```

---

## Summary Table

| Issue | Severity | File | Lines | Status |
|-------|----------|------|-------|--------|
| Mutable Collections in State | CRITICAL | SearchState.kt | 14, 17 | ✅ FIXED |
| Lambda Capturing (ChatScreen) | HIGH | ChatScreen.kt | 505-536 | ⚠️ TODO |
| I/O in LaunchedEffect | HIGH | ConversationsScreen.kt | 263-273 | ✅ FIXED |
| Lambda Capturing (ChatInputUI) | HIGH | ChatInputUI.kt | 174-219 | ⚠️ TODO |
| Parent Collecting State | MEDIUM | ChatScreen.kt | 314, 319 | ⚠️ TODO |
| LaunchedEffect(Unit) | MEDIUM | ConversationsScreen.kt | 215-232 | ✅ FIXED |
| Expensive Remember | MEDIUM | ChatInputUI.kt | 147-170 | ⚠️ TODO |
| Derivations in Composition | MEDIUM | ConversationsScreen.kt | 99-122 | ✅ FIXED |

---

## Positive Findings

The codebase shows good patterns in many areas:
- Proper use of `@Stable` and `@Immutable` annotations
- Delegate pattern for ViewModel decomposition
- StateFlow usage throughout for reactive state
- `collectAsStateWithLifecycle()` for lifecycle-aware collection
- Comments indicating awareness of recomposition optimization
