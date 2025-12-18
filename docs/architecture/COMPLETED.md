# Architecture Alignment — Completed Phases

This document summarizes all completed architecture refactoring phases (0-15).

## Phase Summary

| Phase | Focus | Status |
|-------|-------|--------|
| Phase 0 | Shared Vision & ADRs | ✅ Complete |
| Phase 1 | Documentation Alignment | ✅ Complete |
| Phase 2 | Dependency Boundaries | ✅ Complete |
| Phase 3 | Delegate Lifecycle | ✅ Complete |
| Phase 4 | Delegate Coupling | ✅ Complete |
| Phase 5 | Service Layer Hygiene | ✅ Complete |
| Phase 6 | Modularization (core:model) | ✅ Complete |
| Phase 7 | Interface Extraction | ✅ Complete |
| Phase 8 | Conversations Architecture | ✅ Complete |
| Phase 9 | Setup Architecture | ✅ Complete |
| Phase 10 | Service Modernization | ✅ Complete |
| Phase 11 | Architectural Completion | ✅ Complete |
| Phase 12 | Observability (Privacy-First) | ✅ Complete |
| Phase 14 | Core Module Extraction | ✅ Complete |
| Phase 15 | Feature Module Extraction | ✅ Complete |

---

## Phase 0: Shared Vision & ADRs

Established architectural principles and documented decisions:

**Key Principles:**
- Delegates are "born ready" (AssistedInject, no `initialize()`)
- UI depends on interfaces, not concrete services
- Explicit coordination (ViewModel orchestrates, no global event bus)
- Single responsibility (delegates don't know about each other)
- Testable by design (interfaces enable fake injection)

**ADRs Created:**
- ADR 0001: Coordinator vs Delegate pattern
- ADR 0002: No global event bus
- ADR 0003: UI depends on interfaces
- ADR 0004: Delegate lifecycle rules

---

## Phase 1: Documentation Alignment

Created comprehensive README.md files in every major package:
- `app/src/main/kotlin/com/bothbubbles/README.md`
- `data/README.md`, `services/README.md`, `ui/README.md`
- `di/README.md`, `app/schemas/README.md`
- `docs/COMPOSE_BEST_PRACTICES.md`

---

## Phase 2: Dependency Boundaries

Established clean dependency boundaries:
- Data layer does NOT depend on Services layer
- Services layer can depend on Data layer
- UI layer can depend on both

**Key Changes:**
- Created service interfaces (`MessageSender`, `SocketConnection`, `Notifier`)
- ViewModels depend on interfaces, not implementations
- Bindings in `di/ServiceModule.kt`

---

## Phase 3: Delegate Lifecycle

Migrated delegates to AssistedInject pattern:
- Eliminated `initialize()` methods
- Delegates receive required context via constructor
- Factory interfaces for creating delegates

**Example:**
```kotlin
class ChatSendDelegate @AssistedInject constructor(
    private val messageSender: MessageSender,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatSendDelegate
    }
}
```

---

## Phase 4: Delegate Coupling

Removed inter-delegate dependencies:
- Delegates no longer call each other directly
- ViewModel orchestrates all delegate coordination
- SharedFlow for events that cross delegate boundaries

**Before:**
```kotlin
send.setDelegates(messageList, composer, connection, onDraftCleared)
```

**After:**
```kotlin
fun sendMessage() {
    val input = composer.getInput()
    val queued = send.queueMessage(input)
    messageList.insertOptimistic(queued)
    composer.clearInput()
}
```

---

## Phase 5: Service Layer Hygiene

Cleaned up service layer:
- Decomposed `SocketEventHandler` into focused handlers
- Created `MessageEventHandler`, `ChatEventHandler`, `SystemEventHandler`
- Extracted `MessageSendingService` from repository
- Added `IncomingMessageHandler` with interface

---

## Phase 6: Modularization

Created initial module structure:
- `:core:model` — Domain models (ChatEntity, MessageEntity, etc.)
- `:core:network` — Retrofit, OkHttp, API interfaces
- `:core:data` — SettingsProvider, interfaces
- `:navigation` — Type-safe routes

---

## Phase 7: Interface Extraction

Created interfaces for all key services:
- `MessageSender` ← `MessageSendingService`
- `SocketConnection` ← `SocketService`
- `Notifier` ← `NotificationService`
- `IncomingMessageProcessor` ← `IncomingMessageHandler`

Test fakes created in `src/test/kotlin/com/bothbubbles/fakes/`.

---

## Phase 8: Conversations Architecture

Refactored ConversationsViewModel with delegates:
- `ConversationLoadingDelegate` — Data loading and pagination
- `ConversationActionsDelegate` — Pin, mute, archive, delete
- `ConversationObserverDelegate` — Database/socket observers
- `UnifiedGroupMappingDelegate` — iMessage/SMS merging

All delegates use AssistedInject + SharedFlow pattern.

---

## Phase 9: Setup Architecture

Refactored SetupViewModel with delegates:
- `PermissionsDelegate`
- `ServerConnectionDelegate`
- `SmsSetupDelegate`
- `SyncDelegate`

All delegates use StateFlow, no callbacks.

---

## Phase 10: Service Modernization

Modernized services with AndroidX Startup:
- `TimberInitializer` — Logging setup
- `WorkManagerInitializer` — Background work
- `AppLifecycleTrackerInitializer` — Foreground/background state

---

## Phase 11: Architectural Completion

Final cleanup and verification:
- All ViewModels use consistent patterns
- `collectAsStateWithLifecycle` migration (154 instances)
- Critical TODOs resolved
- Documentation updated

---

## Phase 12: Observability (Privacy-First)

Implemented privacy-first observability:

**Implemented:**
- ACRA local crash reporting (no auto-upload)
- Crash dialog with opt-in email sharing
- Crash logs viewer in settings
- Timber structured logging
- LeakCanary (debug builds only)

**Removed (Privacy):**
- ❌ Firebase Crashlytics
- ❌ Firebase Analytics
- ❌ Firebase Performance
- ❌ Any automatic data collection

---

## Architecture Achieved

```
┌─────────────────────────────────────────────────────────────┐
│                     UI Layer (Compose)                       │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────────┐│
│  │ ChatScreen  │ │Conversations│ │    Settings Screens     ││
│  │ + ViewModel │ │Screen + VM  │ │                         ││
│  │ + Delegates │ │+ Delegates  │ │                         ││
│  └─────────────┘ └─────────────┘ └─────────────────────────┘│
│         │                │                                   │
│         └────────────────┴──────────────┐                   │
│                                         ▼                    │
│                              ┌──────────────────┐           │
│                              │   Interfaces     │           │
│                              │ MessageSender    │           │
│                              │ SocketConnection │           │
│                              │ Notifier         │           │
│                              └────────┬─────────┘           │
└────────────────────────────────────────┼────────────────────┘
                                         │
┌────────────────────────────────────────┼────────────────────┐
│                     Services Layer     │                     │
│  ┌──────────────────┐ ┌───────────────┴────────────────┐   │
│  │ MessageSending   │ │ SocketEventHandler             │   │
│  │ Service          │ │ ├─ MessageEventHandler         │   │
│  ├──────────────────┤ │ ├─ ChatEventHandler            │   │
│  │ IncomingMessage  │ │ └─ SystemEventHandler          │   │
│  │ Handler          │ └────────────────────────────────┘   │
│  └──────────────────┘                                       │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     Data Layer                              │
│  ┌──────────────────┐ ┌──────────────────────────────────┐ │
│  │ Repositories     │ │ Local Storage                    │ │
│  │ - Message        │ │ - Room Database                  │ │
│  │ - Chat           │ │ - DataStore Preferences          │ │
│  │ - Attachment     │ │                                  │ │
│  └──────────────────┘ └──────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

---

## Phase 14: Core Module Extraction

Completed the core module extraction to enable feature module migration:

**Modules Created/Completed:**
- `:core:model` — Domain entities (already existed from Phase 6)
- `:core:network` — API layer (BothBubblesApi, DTOs, AuthInterceptor, CoreNetworkModule)
- `:core:data` — Interfaces (SettingsProvider, ConnectionState, ServerConnectionProvider, DeveloperModeTracker)
- `:core:design` — Shared UI components (fonts, typography, SettingsComponents)

**Key Files:**
- `core/design/src/main/kotlin/.../theme/Type.kt` — Font families and AppTypography
- `core/design/src/main/kotlin/.../component/SettingsComponents.kt` — SettingsCard, SettingsMenuItem, ProfileHeader
- `core/data/src/main/kotlin/.../ConnectionState.kt` — Connection state enum
- `core/network/src/main/kotlin/.../di/CoreNetworkModule.kt` — Hilt DI for networking

**Benefits Achieved:**
- Feature modules can now depend on core modules without circular dependencies
- Shared UI components available to all feature modules
- Clean separation between core infrastructure and feature code
- Incremental build improvements for UI-only changes

---

## Phase 15: Feature Module Extraction

Completed feature module structure and demonstrated migration pattern:

**Module Structure:**
- `:navigation` — Type-safe routes for all destinations (Route.kt)
- `:feature:settings` — AboutScreen + AboutViewModel migrated as first example
- `:feature:setup` — Structure ready for migration
- `:feature:conversations` — Structure ready for migration
- `:feature:chat` — Structure ready for migration

**Key Files:**
- `feature/settings/src/.../about/AboutScreen.kt` — Settings about screen (migrated)
- `feature/settings/src/.../about/AboutViewModel.kt` — Uses core:data interfaces
- `feature/settings/src/.../SettingsNavigation.kt` — NavGraphBuilder extension
- `navigation/src/.../Routes.kt` — All route definitions

**Migration Pattern Established:**
1. Create screen + ViewModel in feature module
2. Use interfaces from `:core:data` (SettingsProvider, ServerConnectionProvider, etc.)
3. Use components from `:core:design` (SettingsCard, fonts, etc.)
4. Update app's navigation to use feature module screen
5. Feature module has no dependencies on other feature modules

**App Module Dependencies:**
- All feature modules now wired to app module
- App module serves as composition root for all features
- Navigation delegated to feature modules via extensions

---

## Key Files Modified

Total files changed across all phases: ~200+

**Major structural changes:**
- Created 14 ChatViewModel delegates
- Created 5 ConversationsViewModel delegates
- Created 4 SetupViewModel delegates
- Created 6 service interfaces
- Created 4 AndroidX Startup initializers
- Created 4 core modules (`:core:model`, `:core:network`, `:core:data`, `:core:design`)
- Created `:navigation` module
- Created 4 feature module stubs (`:feature:chat`, `:feature:conversations`, `:feature:settings`, `:feature:setup`)

---

## Lessons Learned

1. **Incremental refactoring works** — Each phase was self-contained and shippable
2. **Interfaces enable testing** — Fake injection makes unit tests straightforward
3. **Explicit coordination beats implicit** — ViewModel orchestration is clearer than event buses
4. **Privacy is a feature** — Users appreciate no tracking/analytics
5. **Documentation is essential** — README files in packages guide future development
