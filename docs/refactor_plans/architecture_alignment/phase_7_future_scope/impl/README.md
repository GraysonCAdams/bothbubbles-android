# Phase 7b: Future Scope — Overview & Tracking

> **Status**: Ready to Start (Phase 2-4 Complete for Chat, 2025-12)
> **Blocking**: Phase 7a (Interface Extraction) must stay in sync — see [../phase_7_interface_extraction/](../phase_7_interface_extraction/)
> **Implementation Details**: See dedicated phase documents below
> **Risk Level**: Medium (same patterns, different screens)

## Overview

This document serves as a **tracking overview** for applying Chat architecture patterns to the rest of the app. Detailed implementation plans live in dedicated phase documents:

| Target | Implementation Plan |
|--------|---------------------|
| ConversationsViewModel | [Phase 8 — Conversations Architecture](../../phase_8_conversations_architecture/impl/README.md) |
| SetupViewModel | [Phase 9 — Setup Architecture](../../phase_9_setup_architecture/impl/README.md) |
| Service Initialization | [Phase 10 — Service Modernization](../../phase_10_service_modernization/impl/README.md) |

## Pre-Requisite: Interface Extractions (Phase 7a)

Before migrating other ViewModels, extract interfaces for remaining concrete dependencies:

### 1. PendingMessageRepository → PendingMessageSource (BLOCKING)

**Why**: ChatSendDelegate depends on `PendingMessageRepository` directly, blocking full unit testing.

```kotlin
// CURRENT - Concrete class, untestable
class ChatSendDelegate @AssistedInject constructor(
    private val pendingMessageRepository: PendingMessageRepository,  // Concrete!
    private val messageSender: MessageSender,  // Interface ✓
    // ...
)

// TARGET - Interface for testability
interface PendingMessageSource {
    suspend fun queueMessage(chatGuid: String, text: String, deliveryMode: MessageDeliveryMode, ...)
    suspend fun retryMessage(tempGuid: String)
    suspend fun cancelMessage(tempGuid: String)
}

class PendingMessageRepository @Inject constructor(...) : PendingMessageSource
```

**Impact**: Enables FakePendingMessageRepository injection for full ChatSendDelegate testing.

### 2. VCardService → VCardExporter (Medium Priority)

**Why**: ChatComposerDelegate depends on `VCardService` directly.

```kotlin
// CURRENT
import com.bothbubbles.services.contacts.VCardService

// TARGET
interface VCardExporter {
    suspend fun exportContact(contactUri: Uri): Result<String>
}
```

### 3. Concrete Service Dependencies Found

UI modules still importing concrete services instead of interfaces:

| Module | Concrete Import | Should Use |
|--------|-----------------|------------|
| `BubbleChatViewModel` | `MessageSendingService`, `SocketService` | `MessageSender`, `SocketConnection` |
| `ConversationObserverDelegate` | `SocketService` | `SocketConnection` |
| `RecipientSelectionDelegate` | `SocketService` | `SocketConnection` |
| `ContactSearchDelegate` | `SocketService` | `SocketConnection` |
| `GroupCreatorViewModel` | `SocketService` | `SocketConnection` |
| `SetupViewModel` | `SocketService` | `SocketConnection` |
| `SyncDelegate` | `SocketService` | `SocketConnection` |
| `ServerSettingsViewModel` | `SocketService` | `SocketConnection` |
| `AboutViewModel` | `SocketService` | `SocketConnection` |
| `SettingsViewModel` | `SocketService`, `SoundManager` | `SocketConnection`, `SoundPlayer` |
| `ChatComposerDelegate` | `VCardService` | `VCardExporter` (needs extraction) |

## Core Principle

> **Don't leave the app half-modernized. Apply consistent patterns everywhere.**

## When to Start

- [x] Chat refactor (Phases 2-3) is complete for ChatViewModel ✓
- [x] Phase 4 (Delegate Coupling) complete (ChatViewModel ✅)
- [ ] Chat refactor is stable and shipped
- [ ] No active regressions from Chat changes
- [ ] Team capacity available for next cycle

**Can proceed now** with interface extractions (PendingMessageSource, VCardExporter) and simple migrations (SettingsViewModel) **as long as the 7a bindings are merged or tracked in the same sprint**.

## ViewModel Migration Targets

> **Note**: Detailed implementation plans for each target live in dedicated phase documents.
> This section provides quick reference and tracks readiness.

### Target 1: ConversationsViewModel → [Phase 8](../../phase_8_conversations_architecture/impl/README.md)

**Summary**: 880+ line ViewModel with callback hell and `initialize()` patterns.
**Solution**: AssistedInject + sealed events via SharedFlow.
**Status**: Ready when Phase 7a interfaces complete.

### Target 2: SetupViewModel → [Phase 9](../../phase_9_setup_architecture/impl/README.md)

**Summary**: Manual delegate construction, no DI.
**Solution**: Hilt injection + unified `SetupUiState`.
**Status**: Ready when Phase 7a interfaces complete.

### Target 3: Service Initialization → [Phase 10](../../phase_10_service_modernization/impl/README.md)

**Summary**: 40+ lines of manual initialization in `BothBubblesApp.onCreate()`.
**Solution**: AndroidX Startup initializers.
**Status**: Can proceed independently (some items don't depend on Phase 7a).

---

## Prioritized Backlog

| Target | Priority | Effort | Dependency |
|--------|----------|--------|------------|
| **PendingMessageSource interface** | P0 - Critical | 0.5 day | None - enables full safety net testing |
| **SocketService → SocketConnection** (other modules) | P1 - High | 0.5 day | None - interfaces already exist |
| **VCardExporter interface** | P2 - Medium | 0.5 day | None |
| ConversationsViewModel | P2 - Medium | 1-2 days | P1 completion for SocketConnection |
| SettingsViewModel | P2 - Medium | 0.5 day | P1 completion for SocketConnection |
| SetupViewModel DI | P3 - Low | 0.5 day | P1 completion for SocketConnection |
| BubbleChatViewModel | P3 - Low | 0.5 day | None |
| Service Initialization | P4 - Optional | 0.5 day | None |

### Quick Wins (Can Do Now)

These modules just need import changes (interfaces already exist):
- `SettingsViewModel`: Change `SocketService` → `SocketConnection`, `SoundManager` → `SoundPlayer`
- `ServerSettingsViewModel`: Change `SocketService` → `SocketConnection`
- `AboutViewModel`: Change `SocketService` → `SocketConnection`

## Exit Criteria

### Interface Extractions (P0-P2)
- [ ] `PendingMessageSource` interface extracted, FakePendingMessageRepository updated
- [ ] `VCardExporter` interface extracted from VCardService
- [ ] All UI modules use `SocketConnection` interface (not `SocketService`)
- [ ] All UI modules use `SoundPlayer` interface (not `SoundManager`)
- [ ] ChatSendDelegateTest fully enabled (tests delegate instantiation)

### ViewModel Migrations (P2-P3)
- [ ] ConversationsViewModel uses AssistedInject for all delegates
- [ ] No callback-based initialization in ConversationObserverDelegate
- [ ] SetupViewModel uses DI for all delegates (no manual construction)
- [ ] Service initialization is safe (AndroidX Startup OR documented order)

### Quality Gates
- [ ] No `lateinit var` for critical state in any delegate
- [ ] All tests pass
- [ ] No regressions in Conversations or Setup flows

## Verification Commands

```bash
# Check for concrete service imports in UI (should be 0 when complete)
grep -r "import com.bothbubbles.services.socket.SocketService" app/src/main/kotlin/com/bothbubbles/ui/
grep -r "import com.bothbubbles.services.sound.SoundManager" app/src/main/kotlin/com/bothbubbles/ui/
grep -r "import com.bothbubbles.services.messaging.MessageSendingService" app/src/main/kotlin/com/bothbubbles/ui/

# Check for remaining initialize() calls
grep -r "\.initialize(" app/src/main/kotlin/com/bothbubbles/ui/conversations/
grep -r "\.initialize(" app/src/main/kotlin/com/bothbubbles/ui/setup/

# Check for lateinit in delegates
grep -r "lateinit var" app/src/main/kotlin/com/bothbubbles/ui/conversations/delegates/
grep -r "lateinit var" app/src/main/kotlin/com/bothbubbles/ui/setup/delegates/

# Check for manual delegate construction
grep -r "= .*Delegate(" app/src/main/kotlin/com/bothbubbles/ui/setup/SetupViewModel.kt

# Verify AssistedInject usage
grep -r "@AssistedInject" app/src/main/kotlin/com/bothbubbles/ui/conversations/delegates/ | wc -l

# Run safety net tests
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test --tests "ChatSendDelegateTest"
```

## Process

1. **Track progress** in `future_scope_board.md` kanban
2. **Require ADR references** for any new architectural choices
3. **Treat each target as mini-phase** with discovery, implementation, documentation
4. **One PR per target** to ease review and rollback

---

**Status Update (2025-12)**: Phase 2-4 complete for ChatViewModel. Interface extractions (PendingMessageSource, VCardExporter) and quick-win migrations (SettingsViewModel) can proceed now. See Phase 8/9/10 for detailed implementation plans.
