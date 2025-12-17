# Architecture Alignment

A structured refactoring plan to align the BothBubbles app with best practices for safety, testability, and maintainability.

## Status

| Category | Status |
|----------|--------|
| Foundation Phases (0-12) | ✅ **Complete** |
| Feature Module Extraction | 🔄 In Progress |
| Testing Infrastructure | 🔜 Planned |
| Security & CI/CD | 🔜 Planned |

## Documentation

- **[COMPLETED.md](COMPLETED.md)** — Summary of all completed phases (0-12)
- **[ROADMAP.md](ROADMAP.md)** — Outstanding tasks and phases (13-16)

## Core Principles

| Principle | Implementation |
|-----------|----------------|
| **Delegates are "born ready"** | AssistedInject factories, no `initialize()` |
| **UI depends on interfaces** | `MessageSender`, not `MessageSendingService` |
| **Explicit coordination** | ChatViewModel orchestrates, no global event bus |
| **Single responsibility** | Delegates don't know about each other |
| **Testable by design** | Interfaces enable fake injection |
| **Privacy-first** | No tracking, local-only crash reporting |

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     UI Layer (Compose)                       │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────────┐│
│  │ ChatScreen  │ │Conversations│ │    Settings Screens     ││
│  │ + ViewModel │ │Screen + VM  │ │                         ││
│  │ + Delegates │ │+ Delegates  │ │                         ││
│  └─────────────┘ └─────────────┘ └─────────────────────────┘│
└────────────────────────────┬────────────────────────────────┘
                             │ depends on interfaces
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                     Services Layer                          │
│  ┌──────────────────┐ ┌──────────────────────────────────┐ │
│  │ MessageSending   │ │ SocketEventHandler               │ │
│  │ Service          │ │ ├─ MessageEventHandler           │ │
│  ├──────────────────┤ │ ├─ ChatEventHandler              │ │
│  │ IncomingMessage  │ │ └─ SystemEventHandler            │ │
│  │ Handler          │ └──────────────────────────────────┘ │
│  └──────────────────┘                                       │
└────────────────────────────┬────────────────────────────────┘
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

## Module Structure

```
bothbubbles-app/
├── app/                    # Application shell
├── core/
│   ├── model/              # ✅ Domain models
│   ├── network/            # ✅ API layer
│   ├── data/               # ✅ Interfaces
│   └── design/             # Theme, shared components
├── feature/
│   ├── chat/               # 🔄 Migration pending
│   ├── conversations/      # 🔄 Migration pending
│   ├── settings/           # 🔄 Migration pending
│   └── setup/              # 🔄 Migration pending
└── navigation/             # ✅ Route contracts
```

## Quick Start

### For New Contributors
1. Read [COMPLETED.md](COMPLETED.md) — Understand what's been done
2. Read [ROADMAP.md](ROADMAP.md) — See what's next
3. Review `docs/COMPOSE_BEST_PRACTICES.md` — Mandatory UI guidelines

### For Continuing Work
1. Check [ROADMAP.md](ROADMAP.md) for current priorities
2. Feature Module Extraction is the next major effort
3. Testing Infrastructure can run in parallel

## Architecture Decision Records (ADRs)

| ADR | Decision |
|-----|----------|
| [ADR 0001](phase_0_shared_vision/ADR_0001_coordinator_vs_delegate.md) | ChatViewModel is coordinator; delegates stay focused |
| [ADR 0002](phase_0_shared_vision/ADR_0002_no_global_event_bus.md) | No global event bus; prefer explicit Flows |
| [ADR 0003](phase_0_shared_vision/ADR_0003_ui_depends_on_interfaces.md) | UI depends on interfaces, not concrete services |
| [ADR 0004](phase_0_shared_vision/ADR_0004_delegate_lifecycle_rules.md) | Use AssistedInject; eliminate `initialize()` |

## Build Commands

```bash
# Set JAVA_HOME (macOS)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Debug build
./gradlew assembleDebug

# Run tests
./gradlew test

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
