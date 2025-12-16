# Phase 2: Implementation Guide (Dependency Boundaries)

## Goal

Replace concrete service dependencies with interfaces in UI layer code (ViewModels, Delegates).

## Combine with Phase 3

**Do this at the same time as Phase 3 (AssistedInject migration).** When you're already changing a delegate's constructor for AssistedInject, also swap concrete → interface dependencies.

## Current State Analysis

### Delegates Using Concrete Implementations

| Delegate | Concrete Dependency | Interface Available |
|----------|---------------------|---------------------|
| `ChatSendDelegate` | `MessageSendingService` | `MessageSender` ✅ |
| `ChatSendDelegate` | `SocketService` | `SocketConnection` ✅ |
| `ChatOperationsDelegate` | `MessageSendingService` | `MessageSender` ✅ |
| `ChatMessageListDelegate` | `SocketService` | `SocketConnection` ✅ |
| `ChatSyncDelegate` | `SocketService` | `SocketConnection` ✅ |
| `ChatSendModeManager` | `SocketService` | `SocketConnection` ✅ |
| `ChatComposerDelegate` | `SocketService` | `SocketConnection` ✅ |

### Existing Interface Bindings (ServiceModule.kt)

```kotlin
// These bindings already exist - just use the interfaces!
MessageSendingService → MessageSender
SocketService → SocketConnection
IncomingMessageHandler → IncomingMessageProcessor
NotificationService → Notifier
```

## Code Transformations

### Example 1: ChatSendDelegate

```kotlin
// BEFORE
class ChatSendDelegate @Inject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSendingService: MessageSendingService,  // ❌ Concrete
    private val socketService: SocketService,                   // ❌ Concrete
    private val soundManager: SoundManager
)

// AFTER
class ChatSendDelegate @AssistedInject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender,           // ✅ Interface
    private val socketConnection: SocketConnection,     // ✅ Interface
    private val soundManager: SoundManager,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
)
```

### Example 2: ChatOperationsDelegate

```kotlin
// BEFORE
class ChatOperationsDelegate @Inject constructor(
    private val chatRepository: ChatRepository,
    private val spamRepository: SpamRepository,
    private val spamReportingService: SpamReportingService,
    private val messageSendingService: MessageSendingService,  // ❌ Concrete
    private val discordContactService: DiscordContactService
)

// AFTER
class ChatOperationsDelegate @AssistedInject constructor(
    private val chatRepository: ChatRepository,
    private val spamRepository: SpamRepository,
    private val spamReportingService: SpamReportingService,
    private val messageSender: MessageSender,           // ✅ Interface
    private val discordContactService: DiscordContactService,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
)
```

## Method Call Updates

When you swap `messageSendingService` → `messageSender`, update method calls:

```kotlin
// BEFORE
messageSendingService.sendUnified(...)
messageSendingService.retryMessage(...)

// AFTER (same method names - interface matches)
messageSender.sendUnified(...)
messageSender.retryMessage(...)
```

The `MessageSender` interface already has all the methods, so this is mostly a rename.

## SocketService → SocketConnection

The `SocketConnection` interface may not have all methods. Check what the delegate actually uses:

```kotlin
// If delegate only uses connection state:
interface SocketConnection {
    val connectionState: StateFlow<ConnectionState>
    // ... other methods
}

// If delegate needs more, you may need to:
// 1. Add methods to SocketConnection interface
// 2. Or keep using SocketService directly (acceptable for some cases)
```

### Pragmatic Approach

If `SocketConnection` doesn't have a method you need:

1. **Option A**: Add the method to `SocketConnection` interface
2. **Option B**: Keep `SocketService` for now, add TODO comment

```kotlin
// Option B - pragmatic, fix later
private val socketService: SocketService,  // TODO: Extract to SocketConnection interface
```

## Verification

After migration, grep for concrete service usage in UI layer:

```bash
# Should find NO matches in ui/ folder
grep -r "MessageSendingService" app/src/main/kotlin/com/bothbubbles/ui/
grep -r "import.*SocketService" app/src/main/kotlin/com/bothbubbles/ui/
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
- [ ] All dependencies are injectable interfaces
- [ ] `FakeMessageSender` can be used in tests
