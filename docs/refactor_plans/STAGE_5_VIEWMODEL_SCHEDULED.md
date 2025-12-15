# Refactor Plan: ChatViewModel Scheduled Message Delegate

**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatViewModel.kt`
**Goal:** Extract scheduled message management into a specialized delegate.

## Design Philosophy: The Delegate Pattern
- **State Ownership:** The Delegate owns the list of scheduled messages.
- **Scoped Logic:** CRUD operations for scheduled messages live here.

## Instructions

### 1. Create the Delegate Class
Create: `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatScheduledMessageDelegate.kt`

**Structure:**
```kotlin
class ChatScheduledMessageDelegate @Inject constructor(
    private val scheduledMessageRepository: ScheduledMessageRepository,
    private val workManager: WorkManager,
    // Inject CoroutineScope
) {
    // 1. Internal State
    private val _scheduledMessages = MutableStateFlow<List<ScheduledMessageEntity>>(emptyList())
    val scheduledMessages = _scheduledMessages.asStateFlow()

    // 2. Public Actions
    fun loadScheduledMessages() { ... }
    fun scheduleMessage(text: String, time: Long) { ... }
    fun cancelScheduledMessage(id: Long) { ... }
}
```

### 2. Move Logic from ChatViewModel
Move the following from `ChatViewModel.kt`:
- **Fields:** `scheduledMessages` (if exposed), any internal lists.
- **Logic:** 
    - `scheduleMessage()`
    - `updateScheduledMessage()`
    - `deleteScheduledMessage()`
    - `ScheduledMessageWorker` observation logic.

### 3. Integrate into ChatViewModel
1.  Inject `ChatScheduledMessageDelegate`.
2.  Remove moved fields/methods.
3.  Expose `val scheduledMessages = scheduledDelegate.scheduledMessages`.

## Verification
- **Functionality:** Scheduled messages appear in the UI.
- **Behavior:** Scheduling a new message works and triggers the Worker.
