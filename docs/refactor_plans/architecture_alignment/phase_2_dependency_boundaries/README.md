# Phase 2 — Dependency Boundaries (UI → Interfaces)

> **Implementation Plan**: See [impl/README.md](impl/README.md) for detailed code examples and migration steps.
>
> **Recommendation**: Combine with Phase 3 for efficiency — change interfaces and lifecycle together.

## Layman's Explanation

We want the UI to depend on "what something does," not "how it's implemented."

That means the Chat UI should talk to a `MessageSender` contract, not a specific `MessageSendingService` class. It makes testing easier and prevents the UI from becoming glued to one internal implementation.

## Connection to Shared Vision

This phase implements [ADR 0003](../phase_0_shared_vision/ADR_0003_ui_depends_on_interfaces.md):

> **UI components depend on interfaces, not concrete implementations.**

| Concrete Class | Target Interface |
|----------------|------------------|
| `MessageSendingService` | `MessageSender` |
| `SocketService` | `SocketConnection` |
| `NotificationService` | `Notifier` |
| `IncomingMessageHandler` | `IncomingMessageProcessor` |

## Goals

- Replace UI dependencies on concrete service implementations with interfaces
- Keep DI bindings in one place
- Make testing and refactoring safer
- Enable fake injection for tests

## Scope

- Refactor types and injection only (no feature behavior change)
- Start with Chat send flow; expand outward

## Key Transformation

```kotlin
// BEFORE
class ChatSendDelegate @Inject constructor(
    private val messageSendingService: MessageSendingService,  // Concrete
)

// AFTER
class ChatSendDelegate @AssistedInject constructor(
    private val messageSender: MessageSender,  // Interface
)
```

## Work Items

| Item | Change |
|------|--------|
| A | `MessageSendingService` → `MessageSender` in Chat delegates |
| B | `SocketService` → `SocketConnection` in Chat delegates |
| C | Introduce narrow facade interfaces where needed |

## Exit Criteria

- [ ] Chat UI layer has no direct dependency on `MessageSendingService`
- [ ] Chat UI layer has no direct dependency on `SocketService` (or documented exceptions)
- [ ] Interfaces are documented
- [ ] DI modules compile and tests pass
- [ ] `FakeMessageSender` can be used in tests

## Risks

- Medium: refactor touches many call sites
- Risk controlled if changes are mechanical and combined with Phase 3

## Next Steps

Implement together with Phase 3 (Delegate Lifecycle) for efficiency.
