# Refactor Plan: ChatViewModel Connection Delegate

**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatViewModel.kt`
**Goal:** Extract server connection, iMessage availability, and SMS fallback logic.

## Design Philosophy: The Delegate Pattern
- **State Ownership:** The Delegate owns the "Connection State" (is iMessage available? is server connected?).
- **Scoped Logic:** All logic for checking availability and handling fallbacks lives here.

## Instructions

### 1. Create the Delegate Class
Create: `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatConnectionDelegate.kt`

**Structure:**
```kotlin
class ChatConnectionDelegate @Inject constructor(
    private val socketService: SocketService,
    private val iMessageAvailabilityService: IMessageAvailabilityService,
    private val chatFallbackTracker: ChatFallbackTracker,
    private val activeConversationManager: ActiveConversationManager,
    // Inject CoroutineScope
) {
    // 1. Internal State
    private val _connectionState = MutableStateFlow(ConnectionState.Connected)
    private val _isIMessageAvailable = MutableStateFlow(true)
    
    // 2. Public Actions
    fun checkAvailability() { ... }
    fun handleConnectionEvent(event: SocketEvent) { ... }
    fun determineSendMode(chat: ChatEntity): ChatSendMode { ... }
}
```

### 2. Move Logic from ChatViewModel
Move the following from `ChatViewModel.kt`:
- **Fields:** `initialSendMode`, `_uiState.currentSendMode` logic (partially).
- **Logic:** 
    - `AVAILABILITY_CHECK_COOLDOWN` logic.
    - `SERVER_STABILITY_PERIOD_MS` logic.
    - `FLIP_FLOP` detection logic.
    - `SocketEvent.Connect/Disconnect` handling.

### 3. Integrate into ChatViewModel
1.  Inject `ChatConnectionDelegate`.
2.  Remove moved fields/methods.
3.  Use the delegate to determine initial send mode and handle connection events.

## Verification
- **Functionality:** App correctly switches between SMS and iMessage based on server status.
- **Stability:** No "flip-flopping" of send modes.
