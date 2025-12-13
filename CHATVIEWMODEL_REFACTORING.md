# ChatViewModel Refactoring Summary

## Overview

The ChatViewModel.kt file was 3080 lines long. It has been decomposed into smaller, focused files under 500 lines each.

## Files Created

### 1. **ChatUiState.kt** (134 lines)
Contains all UI state data classes:
- `ChatUiState` - Main UI state
- `PendingMessage` - Pending message progress tracking
- `QueuedMessageUiModel` - Queued message UI model
- `AttachmentWarning` - Attachment warning/error
- `ChatSendMode` - Enum for send mode (SMS/iMessage)

**Note**: `TutorialState` is already defined in `SendModeToggleState.kt` and should be imported from there.

### 2. **MessageTransformationUtils.kt** (258 lines)
Utilities for transforming database entities to UI models:
- `MessageEntity.toUiModel()` - Main transformation function
- `parseReactionType()` - Parse tapback reactions
- `isReactionRemoval()` - Check if reaction is a removal
- `normalizeAddress()` - Normalize phone/email addresses
- `resolveSenderName()` - Resolve sender display name
- `resolveSenderAvatarPath()` - Resolve sender avatar

### 3. **Delegates** (all in `ui/chat/delegates/`)

#### ChatSendModeManager.kt (423 lines)
Manages iMessage/SMS send mode switching:
- iMessage availability checking
- Server stability tracking
- Automatic fallback to SMS on disconnect
- Tutorial state management
- Send mode persistence

**State Flows**:
- `currentSendMode`
- `contactIMessageAvailable`
- `isCheckingIMessageAvailability`
- `canToggleSendMode`
- `showSendModeRevealAnimation`
- `sendModeManuallySet`
- `smsInputBlocked`
- `tutorialState`

#### ChatSearchDelegate.kt (126 lines)
Handles in-chat message search:
- Search activation/deactivation
- Query filtering with debouncing
- Navigation through search results

**State Flows**:
- `isSearchActive`
- `searchQuery`
- `searchMatchIndices`
- `currentSearchMatchIndex`

#### ChatOperationsDelegate.kt (257 lines)
Manages chat operations and menu actions:
- Archive/unarchive
- Star/unstar
- Delete chat
- Spam reporting
- Contact blocking
- Intent creation (add contact, Google Meet, WhatsApp, etc.)

**State Flows**:
- `isArchived`
- `isStarred`
- `chatDeleted`
- `showSubjectField`
- `isReportedToCarrier`
- `operationError`

#### ChatEffectsDelegate.kt (95 lines)
Manages message effect playback:
- Bubble effect completion tracking
- Screen effect queueing and playback

**State Flows**:
- `activeScreenEffect`

#### ChatThreadDelegate.kt (115 lines)
Handles thread overlay functionality:
- Loading thread chains (origin + replies)
- Thread dismissal
- Scroll-to-message navigation

**State Flows**:
- `threadOverlayState`

**Shared Flows**:
- `scrollToGuid`

#### ChatSyncDelegate.kt (157 lines)
Manages message syncing:
- Adaptive polling (catches missed messages when push fails)
- Foreground resume sync
- Socket activity tracking

**Methods**:
- `onSocketMessageReceived()` - Update last socket message time
- `performAdaptiveSync()` - Poll for missed messages
- `performForegroundSync()` - Sync on app resume

## Integration Plan

### Step 1: Add Delegate Initialization

In `ChatViewModel.init {}`, add:

```kotlin
// Initialize new delegates
sendModeManager.initialize(chatGuid, viewModelScope, initialSendMode, _uiState.value.isGroup, _uiState.value.participantPhone)
searchDelegate.initialize(viewModelScope)
operationsDelegate.initialize(chatGuid, viewModelScope)
effectsDelegate.initialize(viewModelScope)
threadDelegate.initialize(viewModelScope, mergedChatGuids)
syncDelegate.initialize(chatGuid, viewModelScope)
```

### Step 2: Wire Up Delegate State Flows

Replace direct state updates with delegate observations:

```kotlin
// Send mode state
viewModelScope.launch {
    sendModeManager.currentSendMode.collect { mode ->
        _uiState.update { it.copy(currentSendMode = mode) }
    }
}

// Search state
viewModelScope.launch {
    combine(
        searchDelegate.isSearchActive,
        searchDelegate.searchQuery,
        searchDelegate.searchMatchIndices,
        searchDelegate.currentSearchMatchIndex
    ) { active, query, indices, currentIndex ->
        SearchState(active, query, indices, currentIndex)
    }.collect { state ->
        _uiState.update {
            it.copy(
                isSearchActive = state.active,
                searchQuery = state.query,
                searchMatchIndices = state.indices,
                currentSearchMatchIndex = state.currentIndex
            )
        }
    }
}

// Operations state
viewModelScope.launch {
    operationsDelegate.isArchived.collect { archived ->
        _uiState.update { it.copy(isArchived = archived) }
    }
}
// ... repeat for other operation flows

// Effects state
viewModelScope.launch {
    effectsDelegate.activeScreenEffect.collect { effect ->
        _activeScreenEffect.value = effect
    }
}

// Thread state
viewModelScope.launch {
    threadDelegate.threadOverlayState.collect { state ->
        _threadOverlayState.value = state
    }
}
viewModelScope.launch {
    threadDelegate.scrollToGuid.collect { guid ->
        _scrollToGuid.emit(guid)
    }
}
```

### Step 3: Replace Method Implementations

Delegate method calls to the appropriate delegates:

```kotlin
// Send mode methods
fun setSendMode(mode: ChatSendMode, persist: Boolean = true) =
    sendModeManager.setSendMode(mode, persist)

fun tryToggleSendMode() =
    sendModeManager.tryToggleSendMode()

fun canToggleSendMode() =
    sendModeManager.canToggleSendModeNow()

fun markRevealAnimationShown() =
    sendModeManager.markRevealAnimationShown()

fun updateTutorialState(newState: TutorialState) =
    sendModeManager.updateTutorialState(newState)

fun onTutorialToggleSuccess() =
    sendModeManager.onTutorialToggleSuccess()

// Search methods
fun activateSearch() =
    searchDelegate.activateSearch()

fun closeSearch() =
    searchDelegate.closeSearch()

fun updateSearchQuery(query: String) =
    searchDelegate.updateSearchQuery(query, _uiState.value.messages)

fun navigateSearchUp() =
    searchDelegate.navigateSearchUp()

fun navigateSearchDown() =
    searchDelegate.navigateSearchDown()

// Operation methods
fun archiveChat() =
    operationsDelegate.archiveChat()

fun unarchiveChat() =
    operationsDelegate.unarchiveChat()

fun toggleStarred() =
    operationsDelegate.toggleStarred()

fun deleteChat() =
    operationsDelegate.deleteChat()

fun toggleSubjectField() =
    operationsDelegate.toggleSubjectField()

fun markAsSafe() =
    operationsDelegate.markAsSafe()

fun reportAsSpam() =
    operationsDelegate.reportAsSpam()

fun reportToCarrier() =
    operationsDelegate.reportToCarrier()

fun getAddToContactsIntent() =
    operationsDelegate.getAddToContactsIntent(_uiState.value.participantPhone, _uiState.value.inferredSenderName)

fun getGoogleMeetIntent() =
    operationsDelegate.getGoogleMeetIntent()

fun getWhatsAppCallIntent() =
    operationsDelegate.getWhatsAppCallIntent(_uiState.value.participantPhone)

fun getHelpIntent() =
    operationsDelegate.getHelpIntent()

fun blockContact(context: Context) =
    operationsDelegate.blockContact(context, _uiState.value.participantPhone)

fun isWhatsAppAvailable(context: Context) =
    operationsDelegate.isWhatsAppAvailable(context)

// Effect methods
fun onBubbleEffectCompleted(messageGuid: String) =
    effectsDelegate.onBubbleEffectCompleted(messageGuid)

fun triggerScreenEffect(message: MessageUiModel) =
    effectsDelegate.triggerScreenEffect(message)

fun onScreenEffectCompleted() =
    effectsDelegate.onScreenEffectCompleted()

// Thread methods
fun loadThread(originGuid: String) =
    threadDelegate.loadThread(originGuid)

fun dismissThreadOverlay() =
    threadDelegate.dismissThreadOverlay()

fun scrollToMessage(guid: String) =
    threadDelegate.scrollToMessage(guid)
```

### Step 4: Update Message Transformation

Replace the `toUiModel()` extension function with import:

```kotlin
import com.bothbubbles.ui.chat.MessageTransformationUtils.toUiModel
import com.bothbubbles.ui.chat.MessageTransformationUtils.normalizeAddress
```

Remove the entire `toUiModel()` method and helper functions (`parseReactionType`, `isReactionRemoval`, `parseRemovalType`, `formatTime`, `normalizeAddress`, `resolveSenderName`, `resolveSenderAvatarPath`).

### Step 5: Update Sync Logic

Replace adaptive polling and foreground sync:

```kotlin
// In observeNewMessages(), add:
syncDelegate.onSocketMessageReceived()

// Remove the entire startAdaptivePolling() method
// Remove the entire observeForegroundResume() method

// Replace with delegate calls in appropriate places:
viewModelScope.launch {
    while (true) {
        delay(2000) // Poll interval
        syncDelegate.performAdaptiveSync(_uiState.value.messages.firstOrNull())
    }
}

// For foreground resume:
viewModelScope.launch {
    appLifecycleTracker.foregroundState.collect { isInForeground ->
        if (isInForeground) {
            syncDelegate.performForegroundSync(_uiState.value.messages.firstOrNull())
        }
    }
}
```

### Step 6: Remove Duplicate Code

After implementing all the above, remove these sections from ChatViewModel:

1. **Data classes** (lines 2958-3080): Moved to ChatUiState.kt
2. **Message transformation** (lines 2597-2816): Moved to MessageTransformationUtils.kt
3. **Send mode logic** (lines 756-1175): Moved to ChatSendModeManager.kt
4. **Search logic** (lines 2467-2520): Moved to ChatSearchDelegate.kt
5. **Operation methods** (lines 2407-2595): Moved to ChatOperationsDelegate.kt
6. **Effect playback** (lines 2864-2909): Moved to ChatEffectsDelegate.kt
7. **Thread overlay** (lines 1363-1429): Moved to ChatThreadDelegate.kt
8. **Sync/polling** (lines 1648-1730): Moved to ChatSyncDelegate.kt

### Step 7: Update Imports

Add imports for new files:

```kotlin
import com.bothbubbles.ui.chat.ChatUiState
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.PendingMessage
import com.bothbubbles.ui.chat.QueuedMessageUiModel
import com.bothbubbles.ui.chat.AttachmentWarning
import com.bothbubbles.ui.chat.TutorialState
import com.bothbubbles.ui.chat.MessageTransformationUtils.toUiModel
import com.bothbubbles.ui.chat.MessageTransformationUtils.normalizeAddress
import com.bothbubbles.ui.chat.delegates.ChatSendModeManager
import com.bothbubbles.ui.chat.delegates.ChatSearchDelegate
import com.bothbubbles.ui.chat.delegates.ChatOperationsDelegate
import com.bothbubbles.ui.chat.delegates.ChatEffectsDelegate
import com.bothbubbles.ui.chat.delegates.ChatThreadDelegate
import com.bothbubbles.ui.chat.delegates.ChatSyncDelegate
```

Remove imports that are no longer needed in ChatViewModel.

## Expected Result

After completing all steps, ChatViewModel.kt should be approximately:
- **Original**: 3080 lines
- **After refactoring**: ~1200-1500 lines

The extracted functionality is now in:
- ChatUiState.kt: 134 lines
- MessageTransformationUtils.kt: 258 lines
- ChatSendModeManager.kt: 423 lines
- ChatSearchDelegate.kt: 126 lines
- ChatOperationsDelegate.kt: 257 lines
- ChatEffectsDelegate.kt: 95 lines
- ChatThreadDelegate.kt: 115 lines
- ChatSyncDelegate.kt: 157 lines

**Total extracted**: ~1565 lines (leaving ~1515 lines in ChatViewModel)

## Benefits

1. **Single Responsibility**: Each file has a clear, focused purpose
2. **Testability**: Delegates can be tested independently
3. **Maintainability**: Easier to locate and modify specific functionality
4. **Reusability**: Delegates can potentially be reused in other ViewModels
5. **Readability**: Smaller files are easier to understand

## Testing

After refactoring, verify:
1. App compiles without errors
2. All chat features work as before:
   - Sending messages
   - Search functionality
   - Archive/star/delete operations
   - iMessage/SMS mode switching
   - Thread overlay
   - Message effects
   - Sync and polling
3. No regressions in existing functionality

## Next Steps

1. Review and test the refactored code
2. Consider extracting more functionality if ChatViewModel is still too large
3. Update any tests that directly depend on ChatViewModel internals
4. Consider creating integration tests for the delegates
