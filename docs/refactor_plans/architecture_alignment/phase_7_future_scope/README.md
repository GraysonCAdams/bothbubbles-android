# Phase 7 — Future Scope & Follow-on Work

> **Implementation Plan**: See [impl/README.md](impl/README.md) for detailed migration steps and code examples.
>
> **Status**: Backlog. Do NOT start until Chat refactor (Phases 2-4) is stable and shipped.

## Layman's Explanation

The Chat screen was the biggest and most complex part of the app, so we fixed it first. But other parts of the app (like the Conversation List and Setup screens) were built with the same "old" patterns.

This phase is a reminder list of what to fix *next*, so we don't leave the app in a half-modernized state forever.

## Connection to Shared Vision

This phase extends the [Shared Vision](../phase_0_shared_vision/README.md) to the rest of the app:

- **ConversationsViewModel** — Apply AssistedInject, replace callbacks with events
- **SetupViewModel** — Proper Hilt DI for delegates
- **Service Initialization** — Safe bootstrapping (AndroidX Startup or documented order)

## Primary Target: ConversationsViewModel

The `ConversationsViewModel` (880+ lines) has the same issues we fixed in Chat:

| Issue | Current State | Target State |
|-------|---------------|--------------|
| Lifecycle | `initialize()` + `lateinit` | AssistedInject factories |
| Callbacks | 4+ callbacks in initialize | Sealed events + SharedFlow |
| Wiring | Manual delegate wiring | Factory creation |

```kotlin
// BEFORE - Callback hell
observer.initialize(
    scope,
    onDataChanged = { refreshConversations() },
    onNewMessage = { handleNewMessage() },
    ...
)

// AFTER - Sealed events
observer.events.collect { event ->
    when (event) {
        is ConversationEvent.DataChanged -> refreshConversations()
        is ConversationEvent.NewMessage -> handleNewMessage()
    }
}
```

## Secondary Targets

| Target | Issue | Fix |
|--------|-------|-----|
| **SetupViewModel** | Manual delegate construction | Hilt @Inject for delegates |
| **Service Init** | Manual `.initialize()` calls in Application | AndroidX Startup or documented order |

## Prioritized Backlog

| Target | Priority | Effort | Dependency |
|--------|----------|--------|------------|
| ConversationsViewModel | P1 - High | 1-2 days | None |
| SetupViewModel DI | P2 - Medium | 0.5 day | None |
| Service Initialization | P3 - Low | 0.5 day | None |

## Exit Criteria

- [ ] ConversationsViewModel uses AssistedInject for all delegates
- [ ] No callback-based initialization in ConversationObserverDelegate
- [ ] SetupViewModel uses DI for all delegates
- [ ] Service initialization is safe
- [ ] Patterns match Chat architecture

## When to Start

> **Do NOT start this until Chat (Phases 2-4) is stable and shipped.**
>
> Treat this as "Technical Debt Paydown" for the next release cycle.
