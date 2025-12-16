# Won't Do Appendix — Rejected Approaches

This document records architectural approaches that were considered and explicitly rejected. Reference this when reviewing PRs to prevent relitigating settled decisions.

## Rejected Approaches

### 1. Global Event Bus

**Status: REJECTED**

**Proposed Pattern:**
```kotlin
// Application-wide event bus for cross-feature communication
object AppEventBus {
    val events = MutableSharedFlow<AppEvent>(replay = 1, extraBufferCapacity = 64)

    suspend fun emit(event: AppEvent) = events.emit(event)
}

sealed class AppEvent {
    data class MessageSent(val messageGuid: String) : AppEvent()
    data class ChatOpened(val chatGuid: String) : AppEvent()
    data class ConnectionChanged(val state: ConnectionState) : AppEvent()
}
```

**Why Rejected:**
- **Debugging difficulty**: When a bug occurs, tracing "who emitted this?" and "who is subscribed?" requires searching the entire codebase
- **Hidden dependencies**: Components appear decoupled but are actually tightly coupled via event types
- **Ordering problems**: No guarantee of subscriber execution order; race conditions are easy to introduce
- **Memory leaks**: Easy to forget subscription cleanup; leaked subscribers continue receiving events
- **Testing complexity**: Tests must set up event bus state and verify emissions

**Alternative:**
- Explicit method calls or interface dependencies
- Scoped SharedFlows within a single feature/ViewModel
- Coordinator pattern where ViewModel orchestrates delegates

**ADR Reference:** [ADR 0002](../ADR_0002_no_global_event_bus.md)

---

### 2. Hidden Singletons

**Status: REJECTED**

**Proposed Pattern:**
```kotlin
// Singleton accessed via companion object
class MessageCache private constructor() {
    companion object {
        private var INSTANCE: MessageCache? = null

        fun getInstance(): MessageCache {
            return INSTANCE ?: MessageCache().also { INSTANCE = it }
        }
    }
}

// Usage anywhere:
MessageCache.getInstance().getMessages(chatGuid)
```

**Why Rejected:**
- **Temporal coupling**: Code depends on singleton being initialized before use
- **Testing difficulty**: Cannot inject fakes; must use reflection or PowerMock
- **Lifecycle ambiguity**: Unclear when singleton is created/destroyed
- **Hidden state**: State changes are invisible to callers

**Alternative:**
- Constructor injection via Hilt
- `@Singleton` annotation for single-instance classes
- Interface + implementation pattern for testability

---

### 3. Implicit Initialization Order

**Status: REJECTED**

**Proposed Pattern:**
```kotlin
class ChatViewModel @Inject constructor(
    private val sendDelegate: ChatSendDelegate,
    private val searchDelegate: ChatSearchDelegate,
    private val infoDelegate: ChatInfoDelegate
) : ViewModel() {

    private var chatGuid: String? = null

    fun setChatGuid(guid: String) {
        chatGuid = guid
        // Order matters! sendDelegate must be initialized before searchDelegate uses it
        sendDelegate.initialize(guid, viewModelScope)
        infoDelegate.initialize(guid, viewModelScope)
        searchDelegate.initialize(guid, viewModelScope, sendDelegate)  // Uses sendDelegate!
    }
}
```

**Why Rejected:**
- **Fragile**: Reordering initialize() calls can break functionality
- **Runtime crashes**: Forgetting to call initialize() causes NPE/UninitializedPropertyAccess
- **Hidden coupling**: Dependencies between delegates are invisible in constructor

**Alternative:**
- AssistedInject factories that require all dependencies at construction
- Explicit dependency graph via factory injection
- "Born ready" delegates that are fully functional after construction

**ADR Reference:** [ADR 0004](../ADR_0004_delegate_lifecycle_rules.md)

---

### 4. Delegate-to-Delegate Direct References

**Status: REJECTED**

**Proposed Pattern:**
```kotlin
class ChatSendDelegate @Inject constructor(...) {
    private var messageListDelegate: ChatMessageListDelegate? = null

    fun setDelegates(messageList: ChatMessageListDelegate) {
        this.messageListDelegate = messageList
    }

    fun sendMessage(...) {
        // Delegate directly manipulates another delegate's state
        messageListDelegate?.insertOptimisticMessage(...)
    }
}
```

**Why Rejected:**
- **Circular dependencies**: Delegates reference each other, creating a web
- **Testing complexity**: Must set up multiple delegates and their interactions
- **Hidden control flow**: Actions in one delegate trigger actions in others
- **Single responsibility violation**: Delegates know too much about each other

**Alternative:**
- ViewModel acts as coordinator and calls delegates explicitly
- Delegates return results; ViewModel decides what to do with them
- Workflow/use-case classes for complex multi-step operations

**ADR Reference:** [ADR 0001](../ADR_0001_coordinator_vs_delegate.md)

---

### 5. UI-Layer Database Access

**Status: REJECTED**

**Proposed Pattern:**
```kotlin
@Composable
fun ChatScreen(chatGuid: String) {
    val database = LocalDatabase.current  // Composed dependency
    val messages by database.messageDao()
        .getMessagesForChat(chatGuid)
        .collectAsState(initial = emptyList())
}
```

**Why Rejected:**
- **Layer violation**: UI directly accesses data layer, bypassing business logic
- **Testing difficulty**: Composables require database setup
- **No abstraction**: Changes to database schema affect UI directly
- **Lifecycle issues**: Database operations in composition can cause issues

**Alternative:**
- Repository pattern for data access
- ViewModel exposes UI state via StateFlow
- Composables receive state, not data sources

---

## When to Revisit

These decisions may be revisited if:

1. **Scale changes significantly** — e.g., modular app with 50+ features might benefit from event bus for truly decoupled features
2. **New tooling emerges** — e.g., compile-time event bus validation
3. **Team consensus shifts** — after retrospective with concrete pain points from current approach

To propose revisiting a decision:
1. Document specific pain points with current approach
2. Show how rejected approach solves those pain points
3. Address the original rejection reasons
4. Propose migration path and timeline
