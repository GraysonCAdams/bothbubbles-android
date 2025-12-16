# Plan: ChatScreen Architecture Refactor (Push-Down Strategy)

## Context & Goal
**Current State**: `ChatScreen` is a "God Composable" that collects 16+ StateFlows at the top level and passes values down. This causes full-screen recomposition on minor updates (e.g., download progress 1% -> 2%).

**Target State**: `ChatScreen` acts as a **Coordinator**. It passes stable Delegates/ViewModel references to children (`ChatInputUI`, `ChatMessageList`), which collect their own state internally.

**Strategy**: "Push Down State, Pull Data Locally."

---

## Phase 1: Critical Performance & Correctness (Stop the Bleeding)
**Goal**: Fix items causing massive lag and logic errors before structural refactor.

### 1.1 Fix Download Progress Recomposition (Critical)
*   **Items**: [E], [O]
*   **Files**: `ChatScreen.kt` (line 170), `ChatMessageList.kt`, `MessageBubble.kt`
*   **Action**:
    1. Remove `downloadingAttachments` collection from `ChatScreen.kt`
    2. Pass `ChatAttachmentDelegate` to `ChatMessageList` → `MessageBubble`
    3. In `MessageBubble`, collect progress only for that specific attachment GUID
*   **Acceptance Criteria**:
    *   `ChatScreen` does not observe `attachmentDelegate.downloadProgress`
    *   Downloading a large file does not trigger `ChatScreen` recomposition (verify with Layout Inspector)

### 1.2 Fix Duplicate & Unbounded State
*   **Items**: [V], [Z], [II], [PP]
*   **Files**: `ChatScreen.kt` (lines 173-180), `ChatMessageList.kt` (lines 305-317), `ChatScreenState.kt`
*   **Action**:
    1. Add to `ChatScreenState`:
       ```kotlin
       val processedEffectMessages = mutableSetOf<String>()
       val animatedMessageGuids = mutableSetOf<String>()
       var revealedInvisibleInkMessages by mutableStateOf(setOf<String>())

       fun markEffectProcessed(guid: String) = processedEffectMessages.add(guid)
       fun isEffectProcessed(guid: String) = guid in processedEffectMessages
       fun markMessageAnimated(guid: String) = animatedMessageGuids.add(guid)
       fun isMessageAnimated(guid: String) = guid in animatedMessageGuids
       fun revealInvisibleInk(guid: String) { revealedInvisibleInkMessages += guid }
       fun concealInvisibleInk(guid: String) { revealedInvisibleInkMessages -= guid }
       ```
    2. Remove duplicate `remember { mutableSetOf<String>() }` from both files
    3. Remove duplicate screen effect `LaunchedEffect` from `ChatMessageList.kt` (keep only in `ChatScreen.kt`)
*   **Acceptance Criteria**:
    *   Only one source of truth for "seen effects", "revealed ink", and "animated messages"
    *   No `mutableSetOf` created inside composition body

### 1.3 Fix Stale Captures & Coroutine Bugs
*   **Items**: [U], [X], [Y]
*   **Files**: `ChatMessageList.kt`, `ChatScrollHelper.kt`
*   **Pattern**:
    ```kotlin
    // BEFORE (stale capture)
    LaunchedEffect(Unit) {
        flow.collect { messages.firstOrNull { ... } }
    }

    // AFTER (always fresh)
    val currentMessages by rememberUpdatedState(messages)
    LaunchedEffect(Unit) {
        flow.collect { currentMessages.firstOrNull { ... } }
    }
    ```
*   **Action**:
    1. Fix `socketNewMessageFlow.collect` in `ChatMessageList.kt` - uses stale `messages`
    2. Fix `LoadMoreOnScroll` in `ChatScrollHelper.kt` - uses stale `canLoadMore`/`isLoadingMore`
    3. Fix scroll-to-safety logic that scans `messages` list
*   **Acceptance Criteria**:
    *   Load More works reliably after rapid state changes
    *   No `LaunchedEffect(Unit)` that reads a list/boolean without `rememberUpdatedState`

### 1.4 Remove Hot-Path Logging
*   **Items**: [T], [LL]
*   **Files**: `ChatScreen.kt`, `ChatInputUI.kt`, `ChatMessageList.kt`, `MessageBubble.kt`
*   **Action**: Wrap `Log.d` and `System.currentTimeMillis` in `if (BuildConfig.DEBUG)` or remove entirely
*   **Acceptance Criteria**:
    *   Release builds have zero logging overhead in composition hot paths

---

## Phase 2: The "Push Down" Refactor (Architectural Shift)
**Goal**: Decouple `ChatScreen` from child-specific state.

### Signature Change Strategy (Apply to All 2.x Tasks)
To avoid breaking changes mid-refactor:
1. Add new delegate parameters to child composables (additive, non-breaking)
2. Add internal `collectAsStateWithLifecycle()` in children
3. Remove state collection from `ChatScreen`
4. Remove old state parameters from children

### 2.1 ChatInputUI Independence
*   **Items**: [A] (Smart Replies), [B] (Picker), [C] (Sending), [D] (Voice), [I] (Reply Calculation), [P] (Suggestions), [Q] (GIFs), [JJ] (Composer State)
*   **Files**: `ChatScreen.kt` (lines 351-384), `ChatInputUI.kt`
*   **Action**:
    1. Update `ChatInputUI` signature to accept delegates:
       - `composerDelegate: ChatComposerDelegate` (already passed)
       - `sendDelegate: ChatSendDelegate`
       - `attachmentDelegate: ChatAttachmentDelegate`
    2. Move inside `ChatInputUI`:
       - `smartReplySuggestions` collection
       - `activePanelState`, `gifPickerState`, `gifSearchQuery` collection
       - `sendState` collection
       - `replyingToMessage` derivedStateOf calculation [I]
    3. Remove these collections from `ChatScreen.kt`
*   **Acceptance Criteria**:
    *   `ChatScreen` no longer collects `smartReplySuggestions`, `activePanelState`, `sendState`
    *   Typing or recording audio does not recompose `ChatScreen`

### 2.2 ChatMessageList Independence
*   **Items**: [F] (Search), [G] (Selection), [H] (Sync), [K] (Effects), [L] (Scroll Effects), [BB] (ETA), [EE] (Forwarding), [FF] (Loading), [GG] (Auto-download), [HH] (Initial Load)
*   **Files**: `ChatScreen.kt` (lines 413-519), `ChatMessageList.kt`
*   **Action**:
    1. Update `ChatMessageList` signature to accept delegates:
       - `messageListDelegate: ChatMessageListDelegate`
       - `searchDelegate: ChatSearchDelegate`
       - `syncDelegate: ChatSyncDelegate`
       - `operationsDelegate: ChatOperationsDelegate`
       - `attachmentDelegate: ChatAttachmentDelegate`
       - `etaSharingDelegate: ChatEtaSharingDelegate`
    2. Move inside `ChatMessageList`:
       - `searchState` collection
       - `syncState` collection
       - `operationsState` collection (for selection)
       - `effectsState` collection
       - `etaSharingState` collection [BB]
       - `isLoadingFromServer`, `initialLoadComplete`, `autoDownloadEnabled` [FF/GG/HH]
       - `canLoadMore`/`isLoadingMore` for scroll effects [L]
    3. Remove these collections from `ChatScreen.kt`
*   **Acceptance Criteria**:
    *   `ChatScreen` no longer collects `searchState`, `syncState`, `operationsState`, `etaSharingState`
    *   Syncing messages does not recompose `ChatScreen`

### 2.3 Dialogs & Overlays Independence
*   **Items**: [J] (TopBar), [M] (Dialogs), [Q] (Overlays), [R] (WhatsApp), [AA] (Connection)
*   **Files**: `ChatScreen.kt`, `ChatTopBar.kt`, `ChatDialogsHost.kt`, `AnimatedThreadOverlay.kt`
*   **Action**:
    1. Pass ViewModel/Delegates to:
       - `ChatTopBar` → collect `operationsState` internally
       - `ChatDialogsHost` → collect `connectionState`, `forwardableChats` internally
       - `AnimatedThreadOverlay` → collect `threadOverlayState` internally
    2. Move `isWhatsAppAvailable` check to `ChatDialogsHost` (LaunchedEffect)
    3. Only collect `forwardableChats` when `showForwardDialog == true`
*   **Acceptance Criteria**:
    *   `ChatScreen` no longer collects `connectionState`, `threadOverlayState`
    *   Opening a dialog does not recompose the underlying message list

---

## Phase 3: Stability & Cleanup (Final Polish)
**Goal**: Ensure long-term maintainability and stability.

### 3.1 Controller Pattern & Callbacks
*   **Items**: [Task 1], [Task 3], [W] (Callback Captures), [KK] (Launchers)
*   **Files**: `ChatScreen.kt`, `ChatInputUI.kt`
*   **Action**:
    1. Wrap `MessageListCallbacks` in `remember(viewModel)`:
       ```kotlin
       val callbacks = remember(viewModel) {
           MessageListCallbacks(
               onToggleReaction = viewModel::toggleReaction,
               // ... use method references where possible
           )
       }
       ```
    2. Refactor callbacks that capture `messages` list to use delegate lookups instead
    3. Move `ActivityResultLaunchers` (image/file/contact pickers) to `ChatInputUI`
*   **Acceptance Criteria**:
    *   `MessageListCallbacks` instance remains stable across recompositions
    *   No callbacks capturing the full `messages` list

### 3.2 Final Code Cleanup
*   **Items**: [N] (Stable Annotations), [S] (UiState Cleanup), [CC] (Scroll Cache), [DD] (Scroll Logic), [MM] (Sound Load), [NN] (Height State), [OO] (Scroll Duplication)
*   **Action**:
    1. Add `@Stable` annotation to `ChatAudioState`
    2. Verify `ChatUiState.messages` is unused; remove if dead code
    3. Use `cachedScrollPosition` only once during `rememberChatScreenState` (not continuously observed)
    4. Consolidate duplicate scroll position calculation
    5. Move `MediaActionSound.load()` off main thread (lazy init or background coroutine)
    6. Move height tracking state (`topBarHeightPx`, `bottomBarBaseHeightPx`) into `ChatScreenState`
    7. Consolidate scroll-to-message logic (remove from `ChatMessageList` if duplicated in `ChatScreenEffects`)
*   **Acceptance Criteria**:
    *   `ChatAudioState` is marked `@Stable`
    *   No duplicate scroll logic between `ChatScreenEffects` and `ChatMessageList`
    *   No main thread blocking in sound loading

---

## Documentation Updates
After each phase, update the relevant README files:
- `ui/chat/README.md` - Update architecture diagram showing delegate flow
- `ui/chat/delegates/README.md` - Document which delegates are passed to which composables
- `ui/chat/components/README.md` - Note internal state collection patterns

---

## Key Reference Points

### Files to Modify
| File | Primary Changes |
|------|-----------------|
| `ChatScreen.kt` | Remove 12+ state collections, pass delegates instead |
| `ChatInputUI.kt` | Add delegate parameters, collect state internally |
| `ChatMessageList.kt` | Add delegate parameters, collect state internally, remove duplicate sets |
| `ChatScreenState.kt` | Add consolidated effect/animation tracking sets with helper methods |
| `ChatScrollHelper.kt` | Fix stale captures with `rememberUpdatedState` |
| `ChatTopBar.kt` | Collect `operationsState` internally |
| `ChatDialogsHost.kt` | Collect `connectionState`, `forwardableChats` internally |

### Existing Patterns to Follow
- **Lambda providers**: `composerHeightPxProvider = { state.composerHeightPx }` (line 515)
- **derivedStateOf**: Used for `replyingToMessage` (lines 330-334)
- **Delegate passing**: `composerDelegate = viewModel.composer` already done (line 352)
- **Perf comments**: Document fixes with `// PERF FIX:` comments

### Current State Collection Lines (ChatScreen.kt)
- Lines 69-82: Main collections (12 flows)
- Lines 163-170: Secondary collections (initialLoadComplete, isLoadingFromServer, autoDownloadEnabled, downloadingAttachments)
- Lines 173-180: Duplicate sets to consolidate
- Lines 191-194: forwardableChats, pendingAttachments
- Lines 252-254: activePanelState, gifPickerState, gifSearchQuery

---

## Item Mapping (Complete)
- **Phase 1**: E, O, V, Z, II, PP, U, X, Y, T, LL
- **Phase 2**: A, B, C, D, F, G, H, I, J, K, L, M, P, Q, R, AA, BB, EE, FF, GG, HH, JJ
- **Phase 3**: Task 1, Task 3, W, N, S, CC, DD, KK, MM, NN, OO

---

## Parallelization Strategy

This refactor is split into waves for parallel execution:

### Wave 1 (Parallel) - Additive Changes
All agents work simultaneously on their owned files:

| Task File | Owned Files | Agent Focus |
|-----------|-------------|-------------|
| `02_parallel_wave1_chatscreenstate.md` | `ChatScreenState.kt` | Add consolidated sets + helpers |
| `02_parallel_wave1_chatinputui.md` | `ChatInputUI.kt` | Add delegates, internal collection |
| `02_parallel_wave1_chatmessagelist.md` | `ChatMessageList.kt`, `ChatScrollHelper.kt` | Add delegates, fix stale captures |
| `02_parallel_wave1_dialogs.md` | `ChatDialogsHost.kt`, `ChatTopBar.kt`, `AnimatedThreadOverlay.kt` | Add delegates, internal collection |

### Wave 2 (Sequential) - The Cutover
Single agent performs the critical update:

| Task File | Owned Files | Agent Focus |
|-----------|-------------|-------------|
| `03_sequential_chatscreen.md` | `ChatScreen.kt` | Remove collections, pass delegates |

### Wave 3 (Parallel) - Cleanup
All agents work on cleanup tasks:

| Task File | Owned Files | Agent Focus |
|-----------|-------------|-------------|
| `04_parallel_wave3_a_deprecated_params.md` | All child composables | Remove deprecated params |
| `04_parallel_wave3_b_duplicate_sets.md` | `ChatMessageList.kt` | Remove duplicate sets |
| `04_parallel_wave3_c_audio_stability.md` | `ChatAudioHelper.kt` | Stability + sound loading |
| `04_parallel_wave3_d_logging_cleanup.md` | Multiple | Logging cleanup |
| `04_parallel_wave3_e_scroll_consolidation.md` | Scroll files | Consolidate scroll logic |
| `04_parallel_wave3_f_height_consolidation.md` | `ChatScreenState.kt`, `ChatScreen.kt` | Height state |
| `04_parallel_wave3_g_callbacks.md` | `ChatScreen.kt` | Callbacks stabilization |

### Shared Reference
All agents must read: `00_shared_conventions.md`
