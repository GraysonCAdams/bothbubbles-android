# Phase 4 — Delegate Coupling Reduction (Tame the "Delegate Web")

> **Implementation Plan**: See [impl/README.md](impl/README.md) for detailed code examples and workflow patterns.
>
> **Prerequisite**: Phase 3 must be complete (all delegates use AssistedInject).

## Layman's Explanation

Having delegates is fine. The problem starts when delegates need to know about each other. That creates a spider web where small changes in one place break another.

This phase reduces cross-delegate references and keeps coordination explicit in one place: the ViewModel.

## Connection to Shared Vision

This phase implements [ADR 0001](../phase_0_shared_vision/ADR_0001_coordinator_vs_delegate.md):

> **ChatViewModel is the coordinator. Delegates should not know about each other.**

## Key Transformation

```kotlin
// BEFORE - Hidden delegate web
class ChatSendDelegate {
    private var messageListDelegate: ChatMessageListDelegate? = null
    fun sendMessage() {
        messageListDelegate?.insertOptimistic(...)  // Hidden coupling!
    }
}

// AFTER - ViewModel coordinates explicitly
class ChatViewModel {
    fun sendMessage() {
        val queued = send.queueMessage(...)
        messageList.insertOptimistic(queued)  // Visible coordination
    }
}
```

## Goals

- Minimize delegate-to-delegate references
- Keep `ChatViewModel` as an explicit coordinator (per ADR 0001)
- Remove all `setDelegates()` patterns
- Extract workflows for complex multi-step flows

## Current Coupling to Remove

```kotlin
// THIS MUST GO
send.setDelegates(messageList, composer, chatInfo, connection, onDraftCleared)
operations.setMessageListDelegate(messageList)
search.setMessageListDelegate(messageList)
```

## Design Options

| Option | Use When |
|--------|----------|
| **Coordinator-only** (default) | Simple flows, ViewModel calls delegates explicitly |
| **Workflow extraction** | Complex multi-step flows that would bloat ViewModel |
| **Localized Flows** | Pure data dependencies between delegates |

## Exit Criteria

- [ ] No `setDelegates()` methods in any delegate
- [ ] No delegate stores a reference to another delegate
- [ ] ChatViewModel orchestrates all cross-delegate interactions
- [ ] Control flow is explicit and traceable
- [ ] Build passes and app functions correctly

## Risks

- Medium: refactor impacts control flow
- Mitigated by: careful regression testing, one coupling at a time

## Next Steps

After Phase 4, the Chat architecture is clean. Phase 5 (Service Layer Hygiene) is optional but recommended for long-term maintenance.
