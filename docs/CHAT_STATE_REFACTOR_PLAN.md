# ChatViewModel State Refactor Plan

## Executive Summary

**Origin of this work:** Message send latency was ~1.3 seconds (unacceptable UX).

**What we fixed:** Replaced Scaffold with Box layout → 290ms → 10ms per composition (29x faster).

**What remains:** 4 cascade recompositions per message send (~40ms total). Acceptable, but indicates architectural debt that will cause future pain.

**This plan:** Refactor ChatViewModel's monolithic state into delegate-owned states, following industry standards and your existing delegate pattern.

---

## 1. Problem Statement

### 1.1 Current Architecture Issues

```
ChatViewModel (3,200 lines)
    │
    └── _uiState: MutableStateFlow<ChatUiState>  ← 80 fields, 70+ update sites
            │
            ├── ChatSendDelegate updates it
            ├── ChatSearchDelegate updates it
            ├── ChatOperationsDelegate updates it
            ├── ChatEffectsDelegate updates it
            ├── ChatSyncDelegate updates it
            ├── 15+ inline collectors update it
            └── ... no clear ownership
```

**Problems:**

1. **No ownership:** Who owns `isSending`? `isSearching`? Nobody knows.
2. **Cascade recompositions:** ANY field change → new object → full ChatScreen recomposition
3. **Debugging nightmare:** 70+ update sites to search when something breaks
4. **Testing difficulty:** Can't test delegate in isolation - depends on parent state

### 1.2 Performance Impact

| Scenario        | Recompositions | Root Cause                                                                    |
| --------------- | -------------- | ----------------------------------------------------------------------------- |
| Message send    | 4x             | Multiple state updates: draftText, pendingAttachments, uiState, messagesState |
| Message receive | 1x             | Only messagesState changes                                                    |
| Search          | 3x             | searchQuery, searchResults, uiState                                           |

**Goal:** Match message receive behavior (1 recomposition) for all operations.

---

## 2. Architectural Guidelines

### 2.1 Core Principles (Industry Standard: Clean Architecture + MVI)

1. **Single Responsibility:** Each delegate owns ONE domain of state
2. **Clear Ownership:** Every field has exactly ONE owner
3. **Unidirectional Data Flow:** State flows down, events flow up
4. **Immutability:** All state objects are immutable data classes
5. **Testability:** Delegates can be tested in isolation

### 2.2 State Ownership Rules

```
RULE 1: If a delegate manages a feature, it owns that feature's state
RULE 2: Shared state (chatGuid, participants) lives in ChatViewModel
RULE 3: No delegate may update another delegate's state
RULE 4: ChatScreen collects from delegates directly, not through ViewModel proxy
RULE 5: Cross-delegate communication happens via Events exposed to ViewModel (Switchboard pattern)
```

### 2.3 File Organization Standard

```
ui/chat/
├── ChatViewModel.kt              # Coordinator only (~800 lines)
├── ChatScreen.kt                 # Collects delegate states
├── state/
│   ├── ChatSharedState.kt        # Truly shared state (~15 fields)
│   └── ComposerState.kt          # Input field state (existing)
└── delegates/
    ├── send/
    │   ├── ChatSendDelegate.kt   # Send logic + state
    │   └── SendState.kt          # Send-specific state
    ├── search/
    │   ├── ChatSearchDelegate.kt
    │   └── SearchState.kt
    ├── operations/
    │   ├── ChatOperationsDelegate.kt
    │   └── OperationsState.kt
    ├── effects/
    │   ├── ChatEffectsDelegate.kt
    │   └── EffectsState.kt
    ├── sync/
    │   ├── ChatSyncDelegate.kt
    │   └── SyncState.kt
    └── thread/
        ├── ChatThreadDelegate.kt
        └── ThreadState.kt

### 2.4 Technical Implementation Details

1. **Cross-Delegate Communication:**
   - Delegates should not talk to each other directly.
   - Use the **ViewModel as a Switchboard**.
   - Delegates expose Events (e.g., `SendEvents.MessageSent`).
   - ViewModel collects events and calls other delegates (e.g., `searchDelegate.clear()`).

2. **List Stability:**
   - Continue using the existing `StableList` wrapper to guarantee Compose stability.
   - Ensure all list fields in new state classes use `StableList` and not `List`.

3. **Dependency Injection:**
   - Delegates must be scoped correctly (e.g., `@ViewModelScoped` or created in ViewModel) to survive configuration changes but clear when the screen is closed.
```

---

## 3. State Domain Mapping

### 3.1 Current ChatUiState Fields → New Owners

| Field                     | Current Location | New Owner           | Rationale              |
| ------------------------- | ---------------- | ------------------- | ---------------------- |
| `chatTitle`               | ChatUiState      | ChatSharedState     | Shared, rarely changes |
| `isGroup`                 | ChatUiState      | ChatSharedState     | Shared, never changes  |
| `avatarPath`              | ChatUiState      | ChatSharedState     | Shared, rarely changes |
| `participantNames`        | ChatUiState      | ChatSharedState     | Shared, rarely changes |
| `isSending`               | ChatUiState      | **SendState**       | Send domain            |
| `sendProgress`            | ChatUiState      | **SendState**       | Send domain            |
| `pendingMessages`         | ChatUiState      | **SendState**       | Send domain            |
| `queuedMessages`          | ChatUiState      | **SendState**       | Send domain            |
| `searchQuery`             | ChatUiState      | **SearchState**     | Search domain          |
| `searchResults`           | ChatUiState      | **SearchState**     | Search domain          |
| `currentSearchMatchIndex` | ChatUiState      | **SearchState**     | Search domain          |
| `isArchived`              | ChatUiState      | **OperationsState** | Operations domain      |
| `isStarred`               | ChatUiState      | **OperationsState** | Operations domain      |
| `isTyping`                | ChatUiState      | **SyncState**       | Sync domain            |
| `isServerConnected`       | ChatUiState      | **SyncState**       | Sync domain            |
| `activeScreenEffect`      | ChatUiState      | **EffectsState**    | Effects domain         |
| `threadOverlayState`      | ChatUiState      | **ThreadState**     | Thread domain          |
| `isLoading`               | ChatUiState      | ChatSharedState     | Shared loading         |
| `error`                   | ChatUiState      | ChatSharedState     | Shared error display   |

### 3.2 New State Classes

```kotlin
// === SendState.kt (~30 lines) ===
@Stable
data class SendState(
    val isSending: Boolean = false,
    val sendProgress: Float = 0f,
    val pendingMessages: StableList<PendingMessage> = emptyList<PendingMessage>().toStable(),
    val queuedMessages: StableList<QueuedMessageUiModel> = emptyList<QueuedMessageUiModel>().toStable(),
    val replyingToGuid: String? = null,
    val isForwarding: Boolean = false,
    val forwardSuccess: Boolean = false,
    val sendError: String? = null
)

// === SearchState.kt (~25 lines) ===
@Stable
data class SearchState(
    val query: String = "",
    val results: StableList<SearchResult> = emptyList<SearchResult>().toStable(),
    val currentMatchIndex: Int = 0,
    val isSearching: Boolean = false,
    val showResultsSheet: Boolean = false
)

// === OperationsState.kt (~20 lines) ===
@Stable
data class OperationsState(
    val isArchived: Boolean = false,
    val isStarred: Boolean = false,
    val isDeleted: Boolean = false,
    val isBlocked: Boolean = false,
    val isReportedToCarrier: Boolean = false
)

// === SyncState.kt (~25 lines) ===
@Stable
data class SyncState(
    val isTyping: Boolean = false,
    val isServerConnected: Boolean = true,
    val isSyncing: Boolean = false,
    val isInFallbackMode: Boolean = false,
    val fallbackReason: FallbackReason? = null,
    val lastSyncTime: Long = 0L
)

// === EffectsState.kt (~15 lines) ===
@Stable
data class EffectsState(
    val activeScreenEffect: ScreenEffect? = null,
    val autoPlayEffects: Boolean = true,
    val replayOnScroll: Boolean = false,
    val reduceMotion: Boolean = false
)

// === ChatSharedState.kt (~40 lines) ===
@Stable
data class ChatSharedState(
    // Identity (never changes after load)
    val chatGuid: String = "",
    val chatTitle: String = "",
    val isGroup: Boolean = false,
    val avatarPath: String? = null,
    val participantNames: StableList<String> = emptyList<String>().toStable(),
    val participantPhone: String? = null,

    // Loading state
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val initialLoadComplete: Boolean = false,

    // Errors (displayed in snackbar)
    val error: String? = null,
    val appError: AppError? = null,

    // Chat type flags
    val isLocalSmsChat: Boolean = false,
    val isIMessageChat: Boolean = false,
    val smsInputBlocked: Boolean = false
)
```

---

## 4. Migration Plan

### 4.1 Phase Overview

| Phase | Delegate               | Impact                        | Risk   | Duration  |
| ----- | ---------------------- | ----------------------------- | ------ | --------- |
| 0     | Setup infrastructure   | Low                           | Low    | 1 hour    |
| 1     | ChatSendDelegate       | **High** - fixes send cascade | Medium | 2-3 hours |
| 2     | ChatSearchDelegate     | Medium                        | Low    | 1-2 hours |
| 3     | ChatOperationsDelegate | Low                           | Low    | 1 hour    |
| 4     | ChatSyncDelegate       | Medium                        | Low    | 1-2 hours |
| 5     | ChatEffectsDelegate    | Low                           | Low    | 1 hour    |
| 6     | ChatThreadDelegate     | Low                           | Low    | 1 hour    |
| 7     | Cleanup ChatUiState    | Low                           | Low    | 1 hour    |

**Total estimated time:** 10-14 hours (can be done incrementally)

### 4.2 Phase 0: Infrastructure Setup

**Goal:** Create state files and establish patterns without breaking anything.

**Tasks:**

1. Create `ui/chat/state/` directory
2. Create `ui/chat/delegates/send/`, `ui/chat/delegates/search/`, etc.
3. Move existing delegate files to subdirectories
4. Create empty state classes with TODO comments
5. Add state exposure pattern to one delegate as template

**Files created:**

- `ui/chat/state/ChatSharedState.kt`
- `ui/chat/delegates/send/SendState.kt`
- etc.

**Verification:** Build succeeds, no behavior change.

### 4.3 Phase 1: ChatSendDelegate (HIGHEST IMPACT)

**Goal:** Eliminate cascade recompositions on message send.

**Current flow:**

```
User taps Send
    → ChatViewModel.sendMessage()
    → ChatSendDelegate.sendMessage()
    → Callbacks update _uiState 3x:
        1. _draftText.value = ""
        2. _pendingAttachments.value = []
        3. _uiState.update { copy(attachmentCount = 0) }
    → pagingController.insertOptimistically()
    → _messagesState.value = ...
    → _uiState.update { copy(isLoading = false, canLoadMore = ...) }
= 4 recompositions
```

**Target flow:**

```
User taps Send
    → ChatViewModel.sendMessage()
    → ChatSendDelegate.sendMessage()
    → sendDelegate._state.update { copy(isSending = true) }
    → pagingController.insertOptimistically()
    → _messagesState.value = ...
    → sendDelegate._state.update { copy(isSending = false) }
= 1-2 recompositions (messages + send state only)
```

**Implementation steps:**

1. **Create SendState.kt:**

```kotlin
@Stable
data class SendState(
    val isSending: Boolean = false,
    val sendProgress: Float = 0f,
    val pendingMessages: StableList<PendingMessage> = emptyList<PendingMessage>().toStable(),
    val replyingToGuid: String? = null,
    val error: String? = null
)
```

2. **Update ChatSendDelegate:**

```kotlin
class ChatSendDelegate @Inject constructor(...) {
    private val _state = MutableStateFlow(SendState())
    val state: StateFlow<SendState> = _state.asStateFlow()

    // Remove: onUpdateUiState callback
    // Add: direct state updates

    fun sendMessage(...) {
        _state.update { it.copy(isSending = true) }
        // ... existing logic ...
        _state.update { it.copy(isSending = false) }
    }
}
```

3. **Update ChatViewModel:**

```kotlin
// Remove: isSending, sendProgress, pendingMessages from _uiState updates
// Expose: sendDelegate.state directly

val sendState: StateFlow<SendState> = sendDelegate.state
```

4. **Update ChatScreen:**

```kotlin
// Change:
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
val isSending = uiState.isSending

// To:
val sendState by viewModel.sendState.collectAsStateWithLifecycle()
val isSending = sendState.isSending
```

5. **Remove fields from ChatUiState:**

```kotlin
// Remove: isSending, sendProgress, pendingMessages, queuedMessages,
//         replyingToGuid, isForwarding, forwardSuccess
```

**Verification:**

- Send message → observe 1-2 recompositions (down from 4)
- All send functionality works
- Progress indicator works
- Retry/cancel works

### 4.4 Phase 2-6: Remaining Delegates

Follow same pattern for each delegate. Lower priority since send is the main UX issue.

### 4.5 Phase 7: Cleanup

1. Remove unused fields from ChatUiState
2. Rename ChatUiState → ChatSharedState
3. Update documentation
4. Remove cascade debugging code

---

## 5. ChatScreen Collection Pattern

### 5.1 Before (Current)

```kotlin
@Composable
fun ChatScreen(...) {
    // One giant state
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Extract everything from it
    val isSending = uiState.isSending
    val searchQuery = uiState.searchQuery
    val isArchived = uiState.isArchived
    // ... 30 more extractions
}
```

### 5.2 After (Target)

```kotlin
@Composable
fun ChatScreen(...) {
    // Shared state (rarely changes)
    val sharedState by viewModel.sharedState.collectAsStateWithLifecycle()

    // Domain-specific states (only recompose when domain changes)
    val sendState by viewModel.sendState.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val messages by viewModel.messagesState.collectAsStateWithLifecycle()

    // Pass to child composables
    ChatTopBar(
        title = sharedState.chatTitle,
        isSearching = searchState.query.isNotEmpty()
    )

    MessageList(
        messages = messages,
        // No send state needed here
    )

    ChatComposer(
        isSending = sendState.isSending,
        sendProgress = sendState.sendProgress,
        // No search state needed here
    )
}
```

### 5.3 Recomposition Boundaries

```
ChatScreen
├── ChatTopBar           ← recomposes on: sharedState, searchState
├── MessageList          ← recomposes on: messages only
├── ChatComposer         ← recomposes on: sendState, composerState
└── SearchResultsSheet   ← recomposes on: searchState only

BENEFIT: Sending a message only recomposes ChatComposer, not MessageList or TopBar
```

---

## 6. Success Metrics

### 6.1 Performance Metrics

| Metric                         | Current | Target | Measurement       |
| ------------------------------ | ------- | ------ | ----------------- |
| Message send recompositions    | 4       | 1-2    | CascadeDebug logs |
| Message receive recompositions | 1       | 1      | CascadeDebug logs |
| Search recompositions          | ~3      | 1      | CascadeDebug logs |
| Total send latency             | ~40ms   | ~20ms  | PerfTrace logs    |

### 6.2 Code Quality Metrics

| Metric                       | Current    | Target                  |
| ---------------------------- | ---------- | ----------------------- |
| ChatViewModel.kt lines       | ~3,200     | ~800                    |
| ChatUiState fields           | ~80        | ~15                     |
| `_uiState.update` call sites | ~70        | ~10                     |
| Average delegate size        | ~250 lines | ~300 lines (owns state) |

### 6.3 Debugging Improvement

| Scenario             | Current                      | Target                               |
| -------------------- | ---------------------------- | ------------------------------------ |
| "Send is broken"     | Search 3,200 lines           | Search ~300 lines (ChatSendDelegate) |
| "Why recomposition?" | Check 70+ update sites       | Check 1 delegate's state             |
| "Add send feature"   | Figure out where state lives | Add to SendState                     |

---

## 7. Risk Mitigation

### 7.1 Risks and Mitigations

| Risk                            | Likelihood | Impact | Mitigation                                         |
| ------------------------------- | ---------- | ------ | -------------------------------------------------- |
| Breaking existing functionality | Medium     | High   | Incremental migration, extensive testing per phase |
| Increased complexity            | Low        | Medium | Clear patterns, documentation                      |
| Performance regression          | Low        | High   | Measure before/after each phase                    |
| Merge conflicts                 | Medium     | Low    | Complete each phase quickly                        |

### 7.2 Rollback Plan

Each phase is independent. If a phase causes issues:

1. Revert the phase's commits
2. Keep previous phases' improvements
3. Investigate and retry

---

## 8. Testing Strategy

### 8.1 Per-Phase Testing

**Before each phase:**

1. Run existing tests
2. Manual test affected functionality
3. Record recomposition count baseline

**After each phase:**

1. Run existing tests
2. Manual test affected functionality
3. Verify recomposition count improved or unchanged
4. Verify no regressions

### 8.2 Key Test Scenarios

**Send functionality:**

- [ ] Send text message
- [ ] Send with attachment
- [ ] Send with multiple attachments
- [ ] Reply to message
- [ ] Forward message
- [ ] Retry failed message
- [ ] Cancel pending message

**Search functionality:**

- [ ] Search opens
- [ ] Results appear
- [ ] Navigate between results
- [ ] Clear search

**Operations:**

- [ ] Archive/unarchive
- [ ] Star/unstar
- [ ] Delete chat
- [ ] Block contact

---

## 9. Implementation Order Recommendation

1. **Phase 0** (30 min) - Setup infrastructure
2. **Phase 1** (2-3 hours) - ChatSendDelegate ← **START HERE, HIGHEST IMPACT**
3. **Validate & ship Phase 1**
4. **Phases 2-6** (as needed) - Can be done incrementally over time
5. **Phase 7** (cleanup) - After all delegates migrated

---

## 10. Decision Points for Review

Before proceeding, please confirm:

1. **Agree with state ownership mapping?** (Section 3.1)
2. **Agree with file organization?** (Section 2.3)
3. **Start with Phase 1 (ChatSendDelegate)?**
4. **Timeline preference?** (All at once vs. incremental)

---

## Appendix A: Quick Reference

### A.1 State Update Pattern (New)

```kotlin
// IN DELEGATE:
class ChatSendDelegate {
    private val _state = MutableStateFlow(SendState())
    val state: StateFlow<SendState> = _state.asStateFlow()

    fun doSomething() {
        _state.update { it.copy(isSending = true) }
    }
}

// IN VIEWMODEL:
class ChatViewModel {
    val sendState: StateFlow<SendState> = sendDelegate.state
}

// IN SCREEN:
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val sendState by viewModel.sendState.collectAsStateWithLifecycle()
}
```

### A.2 Migration Checklist Per Delegate

- [ ] Create `{Domain}State.kt` data class
- [ ] Add `_state` and `state` to delegate
- [ ] Move relevant fields from ChatUiState to new state
- [ ] Update delegate methods to use `_state.update`
- [ ] Expose state from ChatViewModel
- [ ] Update ChatScreen to collect new state
- [ ] Remove fields from ChatUiState
- [ ] Remove old `_uiState.update` calls
- [ ] Test functionality
- [ ] Verify recomposition improvement
