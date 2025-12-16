# Phase 7 — Future Scope & Follow-on Work

> **Implementation Plan**: See [impl/README.md](impl/README.md) for detailed migration steps and code examples.
>
> **Status**: Ready to Start (Phase 2+3 Complete for Chat, 2024-12)

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
| **PendingMessageSource interface** | P0 - Critical | 0.5 day | Enables full safety net testing |
| **Migrate remaining modules to interfaces** | P1 - High | 0.5 day | Interfaces exist |
| **VCardExporter interface** | P2 - Medium | 0.5 day | None |
| ConversationsViewModel | P2 - Medium | 1-2 days | P1 completion |
| SetupViewModel DI | P3 - Low | 0.5 day | P1 completion |
| Service Initialization | P4 - Optional | 0.5 day | None |

### Quick Wins (Can Do Now)

11 modules still import concrete services. See [impl/README.md](impl/README.md) for full list.

## Exit Criteria

### Interface Extractions
- [ ] `PendingMessageSource` interface extracted (enables full safety net testing)
- [ ] `VCardExporter` interface extracted
- [ ] All UI modules use interfaces (not concrete services)

### ViewModel Migrations
- [ ] ConversationsViewModel uses AssistedInject for all delegates
- [ ] No callback-based initialization in ConversationObserverDelegate
- [ ] SetupViewModel uses DI for all delegates
- [ ] Patterns match Chat architecture

## When to Start

**Can proceed now** with:
- Interface extractions (PendingMessageSource, VCardExporter)
- Quick-win migrations (SettingsViewModel, AboutViewModel, etc.)

**Wait for stability** before:
- ConversationsViewModel full migration
- SetupViewModel full migration
