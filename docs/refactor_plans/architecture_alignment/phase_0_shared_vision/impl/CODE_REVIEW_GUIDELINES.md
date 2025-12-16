# Code Review Guidelines — Architecture Alignment

This document provides guidelines for code reviewers to enforce architectural decisions during PR review.

## Quick Reference

| Pattern | Action | ADR |
|---------|--------|-----|
| `lateinit var` in ViewModels/Delegates for required state | **Block PR** — request AssistedInject migration | ADR 0004 |
| `((String) -> Unit)?` callbacks stored as mutable fields | **Suggest alternative** — use interface or Flow | ADR 0003, 0004 |
| New "Manager" or "Service" naming | **Clarify** — "Manager" for logic, "Service" for Android components | — |
| Direct import of concrete services in UI layer | **Redirect** — should import interface instead | ADR 0003 |
| Global event bus or SharedFlow with replay > 0 for cross-feature communication | **Block PR** — prefer explicit dependencies | ADR 0002 |
| `initialize()` method on delegate | **Block PR** — use AssistedInject factory | ADR 0004 |

## Detailed Guidelines

### 1. Delegate Lifecycle (ADR 0004)

**Block if you see:**

```kotlin
// BAD: lateinit for required state
class ChatSearchDelegate @Inject constructor(...) {
    private lateinit var chatGuid: String
    private lateinit var scope: CoroutineScope

    fun initialize(chatGuid: String, scope: CoroutineScope) {
        this.chatGuid = chatGuid
        this.scope = scope
    }
}
```

**Request this pattern instead:**

```kotlin
// GOOD: AssistedInject factory
class ChatSearchDelegate @AssistedInject constructor(
    private val messageRepository: MessageRepository,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatSearchDelegate
    }
}
```

### 2. Interface Dependencies (ADR 0003)

**Block if you see:**

```kotlin
// BAD: Concrete implementation in UI layer
import com.bothbubbles.services.messaging.MessageSendingService

class ChatSendDelegate @Inject constructor(
    private val messageSendingService: MessageSendingService  // Concrete!
)
```

**Request this pattern instead:**

```kotlin
// GOOD: Interface dependency
import com.bothbubbles.services.messaging.MessageSender

class ChatSendDelegate @Inject constructor(
    private val messageSender: MessageSender  // Interface!
)
```

### 3. Event Communication (ADR 0002)

**Block if you see:**

```kotlin
// BAD: Global event bus
object AppEventBus {
    val events = MutableSharedFlow<AppEvent>(replay = 1)
}

// Anywhere in codebase:
AppEventBus.events.emit(SomeEvent)
```

**Acceptable alternatives:**

```kotlin
// OK: Scoped SharedFlow in a specific component
class ChatViewModel {
    private val _sendResults = MutableSharedFlow<SendResult>()
    val sendResults: SharedFlow<SendResult> = _sendResults
}

// OK: Explicit method call
class ChatSendDelegate {
    fun sendMessage(...): Result<MessageEntity>
}
```

### 4. Mutable Callback Fields

**Flag for discussion:**

```kotlin
// QUESTIONABLE: Mutable callback that can be set/unset
class ChatSendDelegate {
    private var onDraftCleared: (() -> Unit)? = null

    fun setOnDraftCleared(callback: () -> Unit) {
        this.onDraftCleared = callback
    }
}
```

**Prefer:**

```kotlin
// BETTER: Pass callback to the method that needs it
fun sendMessage(
    text: String,
    onDraftCleared: () -> Unit  // Explicit, scoped to this call
)

// OR: Use a Flow for results
val draftCleared: SharedFlow<Unit>
```

## Review Checklist

When reviewing PRs that touch ViewModels or Delegates:

- [ ] No new `lateinit var` for required state
- [ ] No new `initialize()` methods
- [ ] UI layer imports interfaces, not concrete implementations
- [ ] No global event bus patterns
- [ ] Callbacks are scoped (passed to methods) rather than stored as mutable fields
- [ ] Complex coordinator logic lives in ViewModel, not spread across delegates
- [ ] Tests use fakes, not mocks with complex setup

## Exceptions

Some patterns may be acceptable with justification:

1. **`lateinit` for Android framework objects** (e.g., `Context` in a Service) — acceptable when lifecycle is managed by Android
2. **SharedFlow with replay for UI state** — acceptable within a single ViewModel/feature scope
3. **Concrete dependencies for infrastructure** (e.g., `WorkManager`, `Room`) — acceptable as these are unlikely to be swapped

When in doubt, ask: "Could we write a unit test for this without complex mocking setup?"

## Related Documents

- [ADR 0001 — Coordinator vs Delegates](../ADR_0001_coordinator_vs_delegate.md)
- [ADR 0002 — No Global Event Bus](../ADR_0002_no_global_event_bus.md)
- [ADR 0003 — UI Depends on Interfaces](../ADR_0003_ui_depends_on_interfaces.md)
- [ADR 0004 — Delegate Lifecycle Rules](../ADR_0004_delegate_lifecycle_rules.md)
