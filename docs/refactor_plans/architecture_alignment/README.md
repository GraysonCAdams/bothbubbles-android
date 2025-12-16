# Architecture Alignment Plan

A structured set of refactor phases to align the app's architecture with best practices for safety, testability, and maintainability.

## The Shared Vision

Our architecture aims for five core principles:

| Principle | Implementation |
|-----------|----------------|
| **Delegates are "born ready"** | AssistedInject factories, no `initialize()` |
| **UI depends on interfaces** | `MessageSender`, not `MessageSendingService` |
| **Explicit coordination** | ChatViewModel orchestrates, no global event bus |
| **Single responsibility** | Delegates don't know about each other |
| **Testable by design** | Interfaces enable fake injection |

## Phase Overview

| Phase | Focus | Status | Implementation |
|-------|-------|--------|----------------|
| [Phase 0](phase_0_shared_vision/) | Shared Vision & ADRs | ✅ **Complete** | [impl/](phase_0_shared_vision/impl/) |
| [Phase 1](phase_1_docs_alignment/) | Documentation Alignment | Parallel | [impl/](phase_1_docs_alignment/impl/) |
| [Phase 2](phase_2_dependency_boundaries/) | Dependency Boundaries | **Next** (with Phase 3) | [impl/](phase_2_dependency_boundaries/impl/) |
| [Phase 3](phase_3_delegate_lifecycle/) | Delegate Lifecycle | **Next** (with Phase 2) | [impl/](phase_3_delegate_lifecycle/impl/) |
| [Phase 4](phase_4_delegate_coupling/) | Delegate Coupling | After Phase 3 | [impl/](phase_4_delegate_coupling/impl/) |
| [Phase 5](phase_5_service_layer_hygiene/) | Service Layer Hygiene | Optional | [impl/](phase_5_service_layer_hygiene/impl/) |
| [Phase 6](phase_6_modularization_optional/) | Modularization | Optional/Deferred | [impl/](phase_6_modularization_optional/impl/) |
| [Phase 7](phase_7_future_scope/) | Future Scope | Backlog | [impl/](phase_7_future_scope/impl/) |

## Execution Order

```
┌─────────────────────────────────────────────────────────────────────┐
│  Phase 0: Shared Vision & ADRs  ✅ COMPLETE                         │
│  - Review and finalize all 4 ADRs ✓                                 │
│  - Create safety net test ✓                                         │
│  - Update PR template ✓                                             │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Phase 2+3 (Combined): Interfaces + Lifecycle                       │
│  - Swap concrete services to interfaces                             │
│  - Convert to AssistedInject factories                              │
│  - Migrate one delegate at a time                                   │
│                                                                     │
│  Phase 1 runs in parallel: Update docs with each PR                 │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Phase 4: Delegate Coupling Reduction                               │
│  - Remove setDelegates() patterns                                   │
│  - ViewModel becomes single coordinator                             │
│  - Extract workflows for complex flows                              │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Chat Architecture Complete!                                        │
│                                                                     │
│  Optional: Phase 5 (Service Hygiene), Phase 6 (Modularization)      │
│  Future: Phase 7 (ConversationsViewModel, SetupViewModel)           │
└─────────────────────────────────────────────────────────────────────┘
```

## Key Transformations

### Before → After: Delegate Lifecycle

```kotlin
// BEFORE - Temporal coupling
class ChatSendDelegate @Inject constructor(...) {
    private lateinit var chatGuid: String
    fun initialize(chatGuid: String, scope: CoroutineScope) {
        this.chatGuid = chatGuid
    }
}

// AFTER - Safe by construction
class ChatSendDelegate @AssistedInject constructor(
    private val messageSender: MessageSender,  // Interface!
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatSendDelegate
    }
}
```

### Before → After: Delegate Coupling

```kotlin
// BEFORE - Hidden delegate web
send.setDelegates(messageList, composer, chatInfo, connection, onDraftCleared)

// AFTER - ViewModel coordinates explicitly
fun sendMessage() {
    val input = composer.getInput()
    val queued = send.queueMessage(input)
    messageList.insertOptimistic(queued)
    composer.clearInput()
}
```

## Architecture Decision Records (ADRs)

| ADR | Decision |
|-----|----------|
| [ADR 0001](phase_0_shared_vision/ADR_0001_coordinator_vs_delegate.md) | ChatViewModel is coordinator; delegates stay focused |
| [ADR 0002](phase_0_shared_vision/ADR_0002_no_global_event_bus.md) | No global event bus; prefer explicit Flows |
| [ADR 0003](phase_0_shared_vision/ADR_0003_ui_depends_on_interfaces.md) | UI depends on interfaces, not concrete services |
| [ADR 0004](phase_0_shared_vision/ADR_0004_delegate_lifecycle_rules.md) | Use AssistedInject; eliminate `initialize()` |

## Safety & Testing

- **Safety net test** required before any refactoring (Phase 0)
- **Migrate one delegate at a time** for easy rollback
- **Keep changes mechanical** — no behavior changes mixed with architecture
- **Update docs in the same PR as code changes** (Phase 1)

## Cross-Phase Guardrails

- No global event bus as default solution ([ADR 0002](phase_0_shared_vision/ADR_0002_no_global_event_bus.md))
- Prefer explicit dependencies and unidirectional data flow
- Add tests only where they directly support the refactor
- Preserve Compose performance rules ([COMPOSE_BEST_PRACTICES.md](../../COMPOSE_BEST_PRACTICES.md))

## Quick Start

1. **Read Phase 0** — Understand the shared vision and ADRs
2. **Create safety net test** — Before any code changes
3. **Pick a delegate** — Start with low-complexity delegates
4. **Combine Phase 2+3** — Change interfaces and lifecycle together
5. **Update docs** — In the same PR as code changes
6. **Continue to Phase 4** — Remove coupling after lifecycle migration
