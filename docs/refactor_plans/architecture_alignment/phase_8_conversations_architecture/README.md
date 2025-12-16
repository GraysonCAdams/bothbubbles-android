# Phase 8 — Conversations Architecture

> **Status**: Planned
> **Prerequisite**: Phase 7 (Interface Extraction)

## Layman's Explanation

The "Conversation List" is the second most important screen in the app. It currently suffers from the same issues the Chat screen used to have: "lateinit" variables, manual initialization, and a web of callbacks.

This phase applies the successful patterns from the Chat refactor (Phases 2-4) to the Conversation List.

## Goals

1.  **AssistedInject**: Convert `ConversationsViewModel` delegates to use `AssistedInject`.
2.  **Remove Callbacks**: Replace callback interfaces with `SharedFlow` events.
3.  **Explicit Coordination**: Ensure `ConversationsViewModel` coordinates its delegates explicitly.

## Key Transformations

### 1. Lifecycle Safety

```kotlin
// BEFORE
class ConversationObserverDelegate {
    fun initialize(scope: CoroutineScope, onDataChanged: () -> Unit) { ... }
}

// AFTER
class ConversationObserverDelegate @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope
) {
    val events: SharedFlow<ConversationEvent> = ...
}
```

### 2. Event-Based Communication

```kotlin
// BEFORE
interface ConversationCallbacks {
    fun onNewMessage()
    fun onChatDeleted()
}

// AFTER
sealed class ConversationEvent {
    data object NewMessage : ConversationEvent()
    data class ChatDeleted(val guid: String) : ConversationEvent()
}
```

## Exit Criteria

- [ ] `ConversationsViewModel` uses `AssistedInject` for all delegates.
- [ ] No `lateinit var` properties in delegates.
- [ ] No manual `initialize()` calls.
- [ ] All callbacks replaced with `SharedFlow` events.
 - [ ] Table above is fully checked with owners assigned in tracking doc / Jira.

## Delegate Inventory (Plan of Record)

| Delegate / Module | Current Issue | Planned Action | Owner | Status |
|-------------------|---------------|----------------|-------|--------|
| `ConversationLoadingDelegate` | `initialize()` + `lateinit` scope | Convert to AssistedInject factory, expose `StateFlow` | _Unassigned_ | ☐ |
| `ConversationActionsDelegate` | Manual wiring back into VM | AssistedInject + explicit APIs for archive/delete | _Unassigned_ | ☐ |
| `ConversationObserverDelegate` | Stores 4 callbacks | Emit `ConversationEvent` via `SharedFlow` | _Unassigned_ | ☐ |
| `ConversationFilterDelegate` | Push-style callbacks | Emit `FilterState` as `StateFlow` | _Unassigned_ | ☐ |
| `UnifiedGroupMappingDelegate` | Internal caches rely on `initialize()` | Factory + lifecycle-safe caches | _Unassigned_ | ☐ |
| `ConversationsViewModel` | God object orchestrating hidden delegate web | Create delegates via factories, collect events, provide explicit coordination methods | _Unassigned_ | ☐ |
