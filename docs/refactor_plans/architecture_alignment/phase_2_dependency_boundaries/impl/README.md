# Phase 2: Dependency Boundaries — Unified Implementation Plan

> **Status**: Core Refactor Phase
> **Blocking**: Requires Phase 0 complete
> **Code Changes**: Type changes and DI bindings only
> **Recommendation**: Combine with Phase 3 for efficiency

## Overview

UI layer code should depend on **interfaces**, not concrete implementations. This phase replaces direct service dependencies with interface contracts, enabling testability and decoupling.

## Core Principle

> **UI components depend on "what something does" (interface), not "how it's implemented" (concrete class).**

## Why Combine with Phase 3?

When migrating a delegate to AssistedInject (Phase 3), you'll already be editing its constructor. This is the ideal time to also swap concrete dependencies to interfaces—avoiding touching the same delegate twice.

## Objectives

1. **Replace concrete service usage** — `MessageSendingService` → `MessageSender`
2. **Verify interface bindings exist** — All interfaces must have DI bindings
3. **Introduce narrow interfaces where needed** — Socket, active conversation tracking
4. **Enable fake injection for tests** — Interfaces make fakes possible

## Current State Analysis

### Delegates Using Concrete Implementations

| Delegate | Concrete → Interface |
|----------|---------------------|
| `ChatSendDelegate` | `MessageSendingService` → `MessageSender` |
| `ChatSendDelegate` | `SocketService` → `SocketConnection` |
| `ChatOperationsDelegate` | `MessageSendingService` → `MessageSender` |
| `ChatMessageListDelegate` | `SocketService` → `SocketConnection` |
| `ChatSyncDelegate` | `SocketService` → `SocketConnection` |
| `ChatSendModeManager` | `SocketService` → `SocketConnection` |
| `ChatComposerDelegate` | `SocketService` → `SocketConnection` |

### Existing Bindings (ServiceModule.kt)

```kotlin
// These bindings already exist - just use the interfaces!
@Binds abstract fun bindMessageSender(impl: MessageSendingService): MessageSender
@Binds abstract fun bindSocketConnection(impl: SocketService): SocketConnection
@Binds abstract fun bindIncomingMessageProcessor(impl: IncomingMessageHandler): IncomingMessageProcessor
@Binds abstract fun bindNotifier(impl: NotificationService): Notifier
```

## Implementation Tasks

### Task 1: Audit Concrete Usage in UI Layer

Run this command to find violations:

```bash
# Find concrete service imports in UI layer
grep -r "import.*MessageSendingService" app/src/main/kotlin/com/bothbubbles/ui/
grep -r "import.*SocketService" app/src/main/kotlin/com/bothbubbles/ui/
grep -r "import.*NotificationService" app/src/main/kotlin/com/bothbubbles/ui/
```

Log results in `dependency_boundary_audit.md`:

```markdown
# Dependency Boundary Audit

| File | Concrete Import | Target Interface |
|------|-----------------|------------------|
| ChatSendDelegate.kt | MessageSendingService | MessageSender |
| ChatSendDelegate.kt | SocketService | SocketConnection |
| ... | ... | ... |
```

### Task 2: Transform Delegate Constructors

Combine with Phase 3 AssistedInject migration:

```kotlin
// BEFORE (Phase 2 only shows type change)
class ChatSendDelegate @Inject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSendingService: MessageSendingService,  // Concrete
    private val socketService: SocketService,                   // Concrete
    private val soundManager: SoundManager
)

// AFTER (combined Phase 2+3)
class ChatSendDelegate @AssistedInject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender,           // Interface
    private val socketConnection: SocketConnection,     // Interface
    private val soundManager: SoundManager,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatSendDelegate
    }
}
```

### Task 3: Verify Interface Coverage

Ensure the interface includes all methods the delegate uses:

```kotlin
// MessageSender interface - verify all used methods exist
interface MessageSender {
    val uploadProgress: StateFlow<UploadProgress?>

    suspend fun sendUnified(
        chatGuid: String,
        text: String,
        attachments: List<PendingAttachmentInput>,
        // ... all parameters
    ): Result<MessageEntity>

    suspend fun retryMessage(guid: String): Result<MessageEntity>
    suspend fun sendReaction(/*...*/)
    // Add any missing methods!
}
```

If a method is missing from the interface:

1. **Option A (Preferred)**: Add the method to the interface
2. **Option B (Pragmatic)**: Keep concrete dependency with TODO comment

```kotlin
// Option B - temporary, document the gap
private val socketService: SocketService,  // TODO: Extract sendTypingIndicator to SocketConnection
```

### Task 4: Introduce Facade Interfaces (Optional)

For singletons that UI accesses directly, create narrow interfaces:

```kotlin
// ActiveConversationTracker.kt (new interface)
interface ActiveConversationTracker {
    val activeChatGuid: StateFlow<String?>
    fun setActiveChat(guid: String?)
    fun clearActiveChat()
}

// ServiceModule.kt - add binding
@Binds
@Singleton
abstract fun bindActiveConversationTracker(
    impl: ActiveConversationManager
): ActiveConversationTracker
```

### Task 5: Update Method Calls (Mechanical)

When swapping types, update variable names consistently:

```kotlin
// BEFORE
messageSendingService.sendUnified(...)
messageSendingService.retryMessage(...)

// AFTER (method names stay same - interface matches implementation)
messageSender.sendUnified(...)
messageSender.retryMessage(...)
```

## Code Examples

### Complete Migration: ChatSendDelegate

```kotlin
// BEFORE
package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.services.messaging.MessageSendingService  // Concrete
import com.bothbubbles.services.socket.SocketService             // Concrete

class ChatSendDelegate @Inject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSendingService: MessageSendingService,
    private val socketService: SocketService,
    private val soundManager: SoundManager
) {
    private lateinit var chatGuid: String
    private lateinit var scope: CoroutineScope

    fun initialize(chatGuid: String, scope: CoroutineScope) {
        this.chatGuid = chatGuid
        this.scope = scope
    }

    fun sendMessage(text: String) {
        scope.launch {
            messageSendingService.sendUnified(chatGuid, text, ...)
        }
    }
}
```

```kotlin
// AFTER (Phase 2 + Phase 3 combined)
package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.services.messaging.MessageSender       // Interface
import com.bothbubbles.services.socket.SocketConnection      // Interface
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class ChatSendDelegate @AssistedInject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender,               // Interface
    private val socketConnection: SocketConnection,         // Interface
    private val soundManager: SoundManager,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {
    // No lateinit, no initialize()

    init {
        observeUploadProgress()
    }

    fun sendMessage(text: String) {
        scope.launch {
            messageSender.sendUnified(chatGuid, text, ...)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatSendDelegate
    }
}
```

### Complete Migration: ChatOperationsDelegate

```kotlin
// AFTER
class ChatOperationsDelegate @AssistedInject constructor(
    private val chatRepository: ChatRepository,
    private val spamRepository: SpamRepository,
    private val spamReportingService: SpamReportingService,
    private val messageSender: MessageSender,           // Interface
    private val discordContactService: DiscordContactService,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatOperationsDelegate
    }
}
```

## Files to Modify

| File | Change |
|------|--------|
| `ChatSendDelegate.kt` | `MessageSendingService` → `MessageSender` |
| `ChatSendDelegate.kt` | `SocketService` → `SocketConnection` |
| `ChatOperationsDelegate.kt` | `MessageSendingService` → `MessageSender` |
| `ChatMessageListDelegate.kt` | `SocketService` → `SocketConnection` |
| `ChatSyncDelegate.kt` | `SocketService` → `SocketConnection` |
| `ChatSendModeManager.kt` | `SocketService` → `SocketConnection` |
| `ChatComposerDelegate.kt` | `SocketService` → `SocketConnection` |

## Exit Criteria

- [ ] No Chat delegates import `MessageSendingService` directly
- [ ] No Chat delegates import `SocketService` directly (or have documented exceptions)
- [ ] All dependencies use injectable interfaces
- [ ] `FakeMessageSender` can be used in delegate tests
- [ ] Interface methods cover all UI layer usage

## Verification Commands

```bash
# After migration, these should find NO matches in ui/ folder
grep -r "MessageSendingService" app/src/main/kotlin/com/bothbubbles/ui/
grep -r "import.*SocketService" app/src/main/kotlin/com/bothbubbles/ui/

# Verify DI bindings exist
grep -r "@Binds" app/src/main/kotlin/com/bothbubbles/di/ServiceModule.kt

# Verify fakes exist for testing
ls app/src/test/kotlin/com/bothbubbles/fakes/
```

## Test Validation

After migration, this test should work:

```kotlin
@Test
fun `delegate uses interface - can inject fake`() = runTest {
    val fakeSender = FakeMessageSender()
    val fakeSocket = FakeSocketConnection()

    val delegate = ChatSendDelegate(
        pendingMessageRepository = FakePendingMessageRepository(),
        messageSender = fakeSender,           // Fake via interface!
        socketConnection = fakeSocket,        // Fake via interface!
        soundManager = FakeSoundManager(),
        chatGuid = "test-guid",
        scope = this
    )

    delegate.sendMessage("Hello")

    assertTrue(fakeSender.sentMessages.isNotEmpty())
}
```

---

**Next Step**: Implement these changes together with Phase 3 (Delegate Lifecycle) for efficiency.
