# ChatViewModel Refactoring Progress

## Summary

This document tracks the incremental refactoring of `ChatViewModel.kt` to reduce its size and improve maintainability by delegating domain-specific state and logic to specialized delegate classes.

## Results

| Metric | Before | After | Change |
|--------|--------|-------|--------|
| Lines of code | 3,478 | 3,016 | -462 (13.3%) |
| Constructor params | 28 | 26 | -2 |
| Unused imports | ~8 | 0 | -8 |

## Changes Made

### 1. Removed Duplicate Thread Overlay State/Logic

**Before:** ChatViewModel had its own `_threadOverlayState`, `_scrollToGuid`, and thread methods duplicating ChatThreadDelegate.

**After:** Now uses `threadDelegate.state` and `threadDelegate.scrollToGuid` directly.

```kotlin
// Before
private val _threadOverlayState = MutableStateFlow<ThreadChain?>(null)
val threadOverlayState: StateFlow<ThreadChain?> = _threadOverlayState.asStateFlow()
private val _scrollToGuid = MutableSharedFlow<String>()

// After
val threadState: StateFlow<ThreadState> get() = threadDelegate.state
val scrollToGuid: SharedFlow<String> get() = threadDelegate.scrollToGuid
```

### 2. Removed Duplicate Effect Playback State/Logic

**Before:** ChatViewModel had `_activeScreenEffect`, `screenEffectQueue`, `isPlayingScreenEffect` duplicating ChatEffectsDelegate.

**After:** Now uses `effectsDelegate.state` directly.

```kotlin
// Before
private val _activeScreenEffect = MutableStateFlow<ScreenEffectState?>(null)
private val screenEffectQueue = mutableListOf<ScreenEffectState>()
private var isPlayingScreenEffect = false

// After
val effectsState: StateFlow<EffectsState> get() = effectsDelegate.state
```

### 3. Consolidated Passthrough Wrapper Methods

Converted verbose multi-line wrapper methods to one-liners:

```kotlin
// Thread overlay
fun loadThread(originGuid: String) = threadDelegate.loadThread(originGuid)
fun dismissThreadOverlay() = threadDelegate.dismissThreadOverlay()
fun scrollToMessage(guid: String) = threadDelegate.scrollToMessage(guid)

// Effect playback
fun onBubbleEffectCompleted(messageGuid: String) = effectsDelegate.onBubbleEffectCompleted(messageGuid)
fun triggerScreenEffect(message: MessageUiModel) = effectsDelegate.triggerScreenEffect(message)
fun onScreenEffectCompleted() = effectsDelegate.onScreenEffectCompleted()

// Operations
fun archiveChat() = operationsDelegate.archiveChat()
fun unarchiveChat() = operationsDelegate.unarchiveChat()
fun toggleStarred() = operationsDelegate.toggleStarred()
fun deleteChat() = operationsDelegate.deleteChat()
fun markAsSafe() = operationsDelegate.markAsSafe()
fun reportAsSpam() = operationsDelegate.reportAsSpam()

// Search
fun activateSearch() = searchDelegate.activateSearch()
fun closeSearch() = searchDelegate.closeSearch()
fun navigateSearchUp() = searchDelegate.navigateSearchUp()
fun navigateSearchDown() = searchDelegate.navigateSearchDown()
```

### 4. Removed Unused Constructor Parameters

Removed `spamRepository` and `spamReportingService` from ChatViewModel constructor - these are now only used by `ChatOperationsDelegate`.

### 5. Consolidated Observer Blocks Using `combine()`

Reduced number of coroutine launches by combining related flows:

```kotlin
// Before: 3 separate launches
viewModelScope.launch {
    settingsDataStore.autoPlayEffects.collect { effectsDelegate.setAutoPlayEffects(it) }
}
viewModelScope.launch {
    settingsDataStore.replayEffectsOnScroll.collect { effectsDelegate.setReplayOnScroll(it) }
}
viewModelScope.launch {
    settingsDataStore.reduceMotion.collect { effectsDelegate.setReduceMotion(it) }
}

// After: 1 combined launch
viewModelScope.launch {
    combine(
        settingsDataStore.autoPlayEffects,
        settingsDataStore.replayEffectsOnScroll,
        settingsDataStore.reduceMotion
    ) { autoPlay, replayOnScroll, reduceMotion -> Triple(autoPlay, replayOnScroll, reduceMotion) }
        .collect { (autoPlay, replayOnScroll, reduceMotion) ->
            effectsDelegate.setAutoPlayEffects(autoPlay)
            effectsDelegate.setReplayOnScroll(replayOnScroll)
            effectsDelegate.setReduceMotion(reduceMotion)
        }
}
```

Similar consolidation applied to:
- Typing indicator settings (2 → 1 launch)
- Quality settings (2 → 1 launch)
- Attachment delegate state forwarding (2 → 1 launch)

### 6. Removed ~220 Lines of Duplicate Code

The biggest win: removed duplicate `MessageEntity.toUiModel()` extension function and 7 helper functions that already existed in `MessageTransformationUtils.kt`:

- `toUiModel()` - Message entity to UI model transformation
- `parseReactionType()` - Parse tapback from associated message type
- `isReactionRemoval()` - Check if reaction code indicates removal
- `parseRemovalType()` - Parse removal tapback type
- `formatTime()` - Format timestamp as time string
- `normalizeAddress()` - Normalize phone/email for lookup
- `resolveSenderName()` - Resolve sender display name
- `resolveSenderAvatarPath()` - Resolve sender avatar

Now imports these from the shared utility:
```kotlin
import com.bothbubbles.ui.chat.MessageTransformationUtils.normalizeAddress
import com.bothbubbles.ui.chat.MessageTransformationUtils.toUiModel
```

### 7. Cleaned Up Unused Imports

Removed imports no longer needed after refactoring:
- `android.provider.BlockedNumberContract` (moved to ChatOperationsDelegate)
- `android.provider.ContactsContract` (moved to ChatOperationsDelegate)
- `com.bothbubbles.ui.components.message.ThreadChain` (now internal to delegate)
- `com.bothbubbles.ui.effects.MessageEffect` (now internal to delegate)
- `com.bothbubbles.services.spam.SpamReportingService` (moved to delegate)
- `com.bothbubbles.services.spam.SpamRepository` (moved to delegate)
- `java.text.SimpleDateFormat` (was only used by removed formatTime)
- `java.util.*` (was only used by removed code)

## Architecture After Refactoring

ChatViewModel now exposes delegate state directly to ChatScreen:

```kotlin
// Delegate state exposed directly (no duplication)
val sendState: StateFlow<SendState> get() = sendDelegate.state
val searchState: StateFlow<SearchState> get() = searchDelegate.state
val operationsState: StateFlow<OperationsState> get() = operationsDelegate.state
val syncState: StateFlow<SyncState> get() = syncDelegate.state
val effectsState: StateFlow<EffectsState> get() = effectsDelegate.state
val threadState: StateFlow<ThreadState> get() = threadDelegate.state
val scrollToGuid: SharedFlow<String> get() = threadDelegate.scrollToGuid
```

## Why ~800 Lines Wasn't Achievable

The original refactor plan targeted ~800 lines, but this would require fundamental architectural changes, not incremental refactoring. The remaining ~3,000 lines contain interconnected business logic:

1. **Connection state management** (~80 lines) - Server stability tracking, flip-flop detection, SMS fallback scheduling. All mutate shared UI state.

2. **Send mode management** (~150 lines) - iMessage availability checking, mode switching with stability checks, manual override tracking.

3. **Composer state derivation** (~100 lines) - Complex `combine()` of 6 flows with memoization caches.

4. **Message loading/observation** (~200 lines) - Paging controller coordination, reaction handling, optimistic updates.

5. **Various observers** (~300 lines) - Each observer touches multiple pieces of ViewModel state that can't be cleanly isolated.

To reach ~800 lines would require either:
- Complete rewrite where delegates own entire domains end-to-end
- Making ViewModel a thin coordinator with almost no logic

Both are significant architectural changes beyond incremental cleanup.

## Realistic Targets

| Target | Effort Required |
|--------|-----------------|
| ~3,000 lines | ✅ Achieved with incremental refactoring |
| ~2,000 lines | Moderate restructuring of observers and state management |
| ~800 lines | Fundamental rearchitecture |

## Files Modified

- `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatViewModel.kt` - Main refactoring target
- `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatScreen.kt` - Updated to use delegate state
- `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatThreadDelegate.kt` - Added `emitScrollEvent()`
- `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatEffectsDelegate.kt` - Added settings methods
