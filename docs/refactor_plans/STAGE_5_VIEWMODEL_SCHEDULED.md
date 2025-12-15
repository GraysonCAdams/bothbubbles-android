# Refactor Plan: ChatViewModel Scheduled Message Delegate

**Status:** ✅ COMPLETED
**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatViewModel.kt`
**Goal:** Extract scheduled message management into a specialized delegate.

## Design Philosophy: The Delegate Pattern

- **State Ownership:** The Delegate owns the list of scheduled messages.
- **Scoped Logic:** CRUD operations for scheduled messages live here.

## Implementation Summary

### 1. Created Delegate Class

Created: `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatScheduledMessageDelegate.kt`

**Structure:**

```kotlin
class ChatScheduledMessageDelegate @Inject constructor(
    private val scheduledMessageRepository: ScheduledMessageRepository,
    private val workManager: WorkManager
) {
    // 1. Internal State (ScheduledMessagesState)
    private val _state = MutableStateFlow(ScheduledMessagesState())
    val state: StateFlow<ScheduledMessagesState> = _state.asStateFlow()

    // 2. Initialize
    fun initialize(chatGuid: String, scope: CoroutineScope)

    // 3. Public Actions
    fun scheduleMessage(text: String, attachments: List<PendingAttachmentInput>, sendAt: Long)
    fun cancelScheduledMessage(id: Long)
    fun updateScheduledTime(id: Long, newSendAt: Long)
    fun deleteScheduledMessage(id: Long)
    fun retryScheduledMessage(id: Long, newSendAt: Long)
    fun cleanupSentMessages()
}
```

### 2. Created State Class

Created: `app/src/main/kotlin/com/bothbubbles/ui/chat/state/ScheduledMessagesState.kt`

```kotlin
@Stable
data class ScheduledMessagesState(
    val scheduledMessages: List<ScheduledMessageEntity> = emptyList(),
    val pendingCount: Int = 0
)
```

### 3. Moved Logic from ChatViewModel

Moved the following from `ChatViewModel.kt`:

- **Logic:**
  - `scheduleMessage()` - now delegates to `scheduledMessageDelegate.scheduleMessage()`
  - Added `updateScheduledTime()` - delegates to `scheduledMessageDelegate.updateScheduledTime()`
  - Added `deleteScheduledMessage()` - delegates to `scheduledMessageDelegate.deleteScheduledMessage()`
  - Added `cancelScheduledMessage()` - delegates to `scheduledMessageDelegate.cancelScheduledMessage()`
  - Added `retryScheduledMessage()` - delegates to `scheduledMessageDelegate.retryScheduledMessage()`

### 4. Integrated into ChatViewModel

1. ✅ Injected `ChatScheduledMessageDelegate` as constructor parameter
2. ✅ Initialized in `init` block: `scheduledMessageDelegate.initialize(chatGuid, viewModelScope)`
3. ✅ Exposed state: `val scheduledMessagesState = scheduledMessageDelegate.state`
4. ✅ Removed unused dependencies: `ScheduledMessageRepository`, `WorkManager`
5. ✅ Removed unused imports: `TimeUnit`, `ScheduledMessageEntity`, `ScheduledMessageWorker`, etc.

## Files Changed

- `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatViewModel.kt` - Refactored to use delegate
- `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatScheduledMessageDelegate.kt` - NEW
- `app/src/main/kotlin/com/bothbubbles/ui/chat/state/ScheduledMessagesState.kt` - NEW

## Verification

- **Functionality:** Scheduled messages state is exposed via `scheduledMessagesState` StateFlow
- **Behavior:** All scheduled message operations delegate to `ChatScheduledMessageDelegate`
- **State Isolation:** Scheduled message state changes don't trigger unrelated recompositions
