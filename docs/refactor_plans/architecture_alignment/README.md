# Architecture Alignment Plan

A structured set of refactor phases to align the app's architecture with best practices for safety, testability, and maintainability — culminating in enterprise-grade infrastructure.

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

### Foundation Phases (0-10)

| Phase | Focus | Status | Implementation |
|-------|-------|--------|----------------|
| [Phase 0](phase_0_shared_vision/) | Shared Vision & ADRs | ✅ **Complete** | [impl/](phase_0_shared_vision/impl/) |
| [Phase 1](phase_1_docs_alignment/) | Documentation Alignment | Parallel | [impl/](phase_1_docs_alignment/impl/) |
| [Phase 2](phase_2_dependency_boundaries/) | Dependency Boundaries | ✅ **Complete** (ChatViewModel) | [impl/](phase_2_dependency_boundaries/impl/) |
| [Phase 3](phase_3_delegate_lifecycle/) | Delegate Lifecycle | ✅ **Complete** (ChatViewModel) | [impl/](phase_3_delegate_lifecycle/impl/) |
| [Phase 4](phase_4_delegate_coupling/) | Delegate Coupling | ✅ **Complete** | [impl/](phase_4_delegate_coupling/impl/) |
| [Phase 5](phase_5_service_layer_hygiene/) | Service Layer Hygiene | ✅ **Complete** | [impl/](phase_5_service_layer_hygiene/impl/) |
| [Phase 6](phase_6_modularization_optional/) | Modularization | ✅ **Complete** (:core:model) | [impl/](phase_6_modularization_optional/impl/) |
| [Phase 7](phase_7_interface_extraction/) | Interface Extraction | Planned | [impl/](phase_7_interface_extraction/impl/) |
| [Phase 8](phase_8_conversations_architecture/) | Conversations Architecture | Planned | [impl/](phase_8_conversations_architecture/impl/) |
| [Phase 9](phase_9_setup_architecture/) | Setup Architecture | Planned | [impl/](phase_9_setup_architecture/impl/) |
| [Phase 10](phase_10_service_modernization/) | Service Modernization | Planned | [impl/](phase_10_service_modernization/impl/) |

### Enterprise Phases (11-17)

| Phase | Focus | Status | Implementation |
|-------|-------|--------|----------------|
| [Phase 11](phase_11_architectural_completion/) | Architectural Completion | **Next** | [impl/](phase_11_architectural_completion/impl/) |
| [Phase 12](phase_12_observability/) | Observability & Crash Reporting | Planned | [impl/](phase_12_observability/impl/) |
| [Phase 13](phase_13_testing_infrastructure/) | Testing Infrastructure | Planned | [impl/](phase_13_testing_infrastructure/impl/) |
| [Phase 14](phase_14_core_module_extraction/) | Core Module Extraction | Planned | [impl/](phase_14_core_module_extraction/impl/) |
| [Phase 15](phase_15_feature_module_extraction/) | Feature Module Extraction | Planned | [impl/](phase_15_feature_module_extraction/impl/) |
| [Phase 16](phase_16_security_polish/) | Security & Polish | Planned | [impl/](phase_16_security_polish/impl/) |
| [Phase 17](phase_17_ci_cd_pipeline/) | CI/CD Pipeline | Planned | [impl/](phase_17_ci_cd_pipeline/impl/) |

## Execution Order

```
┌─────────────────────────────────────────────────────────────────────┐
│  FOUNDATION PHASES (0-10): Architecture Alignment                   │
├─────────────────────────────────────────────────────────────────────┤
│  Phase 0: Shared Vision & ADRs  ✅ COMPLETE                         │
│  Phase 2+3: Interfaces + Lifecycle  ✅ COMPLETE (ChatViewModel)     │
│  Phase 4: Delegate Coupling  ✅ COMPLETE                            │
│  Phase 5: Service Hygiene  ✅ COMPLETE                              │
│  Phase 6: Modularization  ✅ COMPLETE (:core:model)                 │
│  Phase 7-10: Remaining architecture work (in Phase 11)              │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Phase 11: Architectural Completion  ← NEXT                         │
│  - Complete Phases 7-10 (Interface Extraction, Conversations,       │
│    Setup Architecture, Service Modernization)                       │
│  - Fix critical TODOs (ChatSendDelegate:337, etc.)                  │
│  - collectAsStateWithLifecycle migration (14 instances)             │
│  - Decompose large files (ConversationsScreen, ChatMessageList)     │
│  Effort: 55-75 hours                                                │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Phase 12: Observability                                            │
│  - Firebase Crashlytics for crash reporting                         │
│  - Timber logging with breadcrumbs                                  │
│  - LeakCanary for memory leak detection                             │
│  - Firebase Performance Monitoring                                  │
│  Effort: 18-20 hours                                                │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Phase 13: Testing Infrastructure                                   │
│  - Unit tests for all delegates (ChatViewModel, ConversationsVM)    │
│  - Compose UI tests for critical flows                              │
│  - Screenshot tests with Paparazzi                                  │
│  - Contract tests for service interfaces                            │
│  - Database migration tests (37 migrations)                         │
│  Effort: 95-105 hours                                               │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Phase 14: Core Module Extraction                                   │
│  - :core:network (Retrofit, OkHttp, API interfaces)                 │
│  - :core:data (Room, DAOs, Repositories)                            │
│  - :core:design (Theme, shared Compose components)                  │
│  Effort: 40-45 hours                                                │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Phase 15: Feature Module Extraction                                │
│  - :navigation (Route contracts, deeplinks)                         │
│  - :feature:chat (ChatScreen, ChatViewModel, 14 delegates)          │
│  - :feature:conversations (ConversationsScreen, 5 delegates)        │
│  - :feature:settings (15+ settings screens)                         │
│  - :feature:setup (Setup wizard)                                    │
│  Effort: 60-70 hours                                                │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Phase 16: Security & Polish                                        │
│  - Secrets management (API keys to BuildConfig)                     │
│  - Security audit (OWASP Mobile Top 10)                             │
│  - Module documentation (README for each module)                    │
│  - Accessibility audit (TalkBack, touch targets)                    │
│  - Performance baseline metrics                                     │
│  Effort: 28-38 hours                                                │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Phase 17: CI/CD Pipeline  (FINAL)                                  │
│  - PR Checks: lint, detekt, unit tests, build                       │
│  - Nightly: full test suite, release build, dependency audit        │
│  - Release: tag-based, Play Store upload                            │
│  - Branch protection with required status checks                    │
│  Effort: 16-18 hours                                                │
└─────────────────────────────────────────────────────────────────────┘

Total Enterprise Phases Effort: ~315-370 hours
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

## Enterprise Goals (Phases 11-17)

After completing the foundation phases, the enterprise phases transform the project into a production-grade application:

| Goal | Phase | Outcome |
|------|-------|---------|
| **Complete Architecture** | 11 | All ViewModels use consistent patterns |
| **Production Visibility** | 12 | Crash reports, logging, performance metrics |
| **Quality Assurance** | 13 | 60%+ test coverage, UI tests, screenshot tests |
| **Build Performance** | 14-15 | Modular builds, faster incremental compilation |
| **Security Hardened** | 16 | No secrets in code, accessibility compliant |
| **Automated Quality** | 17 | CI/CD gates enforce all standards |

## Quick Start

### For New Contributors
1. **Read Phase 0** — Understand the shared vision and ADRs
2. **Review COMPOSE_BEST_PRACTICES.md** — Mandatory UI guidelines
3. **Check the current phase** — See what's being worked on

### For Continuing Work
1. **Phase 11 is NEXT** — Completes remaining architecture work
2. **Review Phase 11 README** — Detailed implementation steps
3. **Pick a task** — Start with interface extraction or delegate migration

### Phase Dependencies
- Phases 11-13 can run in parallel (no dependencies)
- Phase 14 requires Phase 11 (stable architecture)
- Phase 15 requires Phase 14 (core modules exist)
- Phase 16 requires Phase 15 (all modules exist)
- Phase 17 requires Phase 16 (quality standards in place)
