# Stage 1: ChatScreen Refactoring Plan

**Goal:** Decompose `ChatScreen.kt` (2186 lines) into smaller, single-responsibility components to improve readability and maintainability.

**Source File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatScreen.kt`

**Status:** ✅ Completed (partial - key extractions done)

## 1. Extract `ChatScreenContent.kt`
**Responsibility:** Pure UI layout (Scaffold, Background, LazyColumn).
**Input:** All UI state (messages, input text, etc.) passed as parameters.
**Status:** ⏸️ Deferred - Layout was optimized with Box-based approach instead of Scaffold to avoid SubcomposeLayout overhead. Further extraction would add complexity without significant benefit.

## 2. Extract `ChatScreenDialogs.kt` ✅
**Responsibility:** Manage all dialogs shown on the chat screen.
**Input:** Boolean states for each dialog (e.g., `showDeleteDialog`, `showBlockDialog`).
**Status:** ✅ **Done** - Already exists with:
- `DeleteConversationDialog`
- `BlockAndReportDialog`
- `SmsBlockedDialog`
- `VideoCallMethodDialog`
- `RetryMessageBottomSheet`

## 3. Extract `ChatAudioHelper.kt` ✅
**Responsibility:** Encapsulate `MediaRecorder` and `MediaPlayer` logic.
**Input:** Context, file paths.
**Status:** ✅ **Done** - Created with:
- `ChatAudioState` class managing all audio state
- `rememberChatAudioState()` composable for lifecycle-aware state
- `ChatAudioEffects()` for recording duration and playback tracking
- Methods: `startRecording()`, `stopRecording()`, `togglePlayback()`, `cancelRecording()`, etc.

## 4. Extract `ChatScrollHelper.kt` ✅
**Responsibility:** Handle complex scroll logic.
**Input:** `LazyListState`, `messages`, `keyboardController`.
**Status:** ✅ **Done** - Created with:
- `ChatScrollEffects()` - Combined keyboard hiding, load more, scroll position tracking
- `ScrollToSafetyEffect()` - Ensures message is visible for tapback menu
- `rememberIsScrolledAwayFromBottom()` - Derived state for jump-to-bottom indicator
- `ScrollToSafetyState` class for tracking programmatic scroll

## Execution Order (Completed)
1. ✅ `ChatScreenDialogs.kt` - Already existed
2. ⏸️ `ChatScreenContent.kt` - Deferred (layout already optimized)
3. ✅ `ChatScrollHelper.kt` - Created and integrated
4. ✅ `ChatAudioHelper.kt` - Created and integrated
5. ✅ `ChatScreen.kt` updated to use new components

## Results
- **ChatScreen.kt reduced** by ~200 lines (removed duplicate scroll effects, audio state management)
- **Better separation of concerns** - Audio and scroll logic now in dedicated files
- **Improved testability** - Helper classes can be unit tested independently
- **Cleaner imports** - MediaRecorder, MediaPlayer, MediaActionSound no longer imported in ChatScreen
