# Phase 7: Future Scope — Unified Implementation Plan

> **Status**: Ready to Start (Phase 2+3 Complete for Chat)
> **Blocking**: None - can proceed now
> **Code Changes**: Apply Chat patterns to other ViewModels
> **Risk Level**: Medium (same patterns, different screens)

## Overview

The Chat screen was the biggest and most complex part of the app, so we fixed it first. This phase applies the same architectural patterns to **ConversationsViewModel**, **SetupViewModel**, and service initialization.

## Pre-Requisite: Interface Extractions (High Priority)

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

### 3. Concrete Service Dependencies Found (2024-12)

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

- [x] Chat refactor (Phases 2-3) is complete for ChatViewModel ✓ (2024-12)
- [ ] Phase 4 (Delegate Coupling) complete OR decided to defer
- [ ] Chat refactor is stable and shipped
- [ ] No active regressions from Chat changes
- [ ] Team capacity available for next cycle

**Can proceed now** with interface extractions (PendingMessageSource, VCardExporter) and simple migrations (SettingsViewModel).

## Target 1: ConversationsViewModel (Primary)

### Current Problems

`ConversationsViewModel` (880+ lines) has the same issues we fixed in Chat:

```kotlin
// CURRENT - Same bad patterns
class ConversationsViewModel @Inject constructor(
    val loading: ConversationLoadingDelegate,
    val actions: ConversationActionsDelegate,
    val observer: ConversationObserverDelegate,
    // ...
) : ViewModel() {
    init {
        // Same initialize() pattern
        loading.initialize(viewModelScope)
        actions.initialize(viewModelScope, ::refreshConversations)
        observer.initialize(
            viewModelScope,
            onDataChanged = { refreshConversations() },
            onNewMessage = { handleNewMessage() },
            onMessageUpdated = { handleMessageUpdated() },
            onChatRead = { guid -> markChatRead(guid) }
        )
    }
}
```

### Callback Hell in ConversationObserverDelegate

```kotlin
// CURRENT - 4 callbacks stored as nullable vars
class ConversationObserverDelegate @Inject constructor(...) {
    private var onDataChanged: (suspend () -> Unit)? = null
    private var onNewMessage: (suspend () -> Unit)? = null
    private var onMessageUpdated: (suspend () -> Unit)? = null
    private var onChatRead: ((String) -> Unit)? = null

    fun initialize(
        scope: CoroutineScope,
        onDataChanged: suspend () -> Unit,
        onNewMessage: suspend () -> Unit,
        onMessageUpdated: suspend () -> Unit,
        onChatRead: (String) -> Unit
    ) {
        this.scope = scope
        this.onDataChanged = onDataChanged
        // ... store all callbacks
    }
}
```

### Target State

Replace callbacks with sealed events (cleaner, more Kotlin-idiomatic):

```kotlin
// AFTER - Sealed events instead of callbacks
class ConversationObserverDelegate @AssistedInject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    @Assisted private val scope: CoroutineScope
) {
    sealed interface ConversationEvent {
        object DataChanged : ConversationEvent
        object NewMessage : ConversationEvent
        object MessageUpdated : ConversationEvent
        data class ChatRead(val guid: String) : ConversationEvent
    }

    private val _events = MutableSharedFlow<ConversationEvent>()
    val events: SharedFlow<ConversationEvent> = _events.asSharedFlow()

    init {
        observeChanges()
    }

    private fun observeChanges() {
        scope.launch {
            chatDao.observeAll().collect {
                _events.emit(ConversationEvent.DataChanged)
            }
        }
        // ... other observations emit events
    }

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): ConversationObserverDelegate
    }
}
```

ViewModel collects events with pattern matching:

```kotlin
@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val observerFactory: ConversationObserverDelegate.Factory,
    private val loadingFactory: ConversationLoadingDelegate.Factory,
    private val actionsFactory: ConversationActionsDelegate.Factory
) : ViewModel() {

    private val observer = observerFactory.create(viewModelScope)
    private val loading = loadingFactory.create(viewModelScope)
    private val actions = actionsFactory.create(viewModelScope)

    init {
        // Single collection point, pattern matching
        viewModelScope.launch {
            observer.events.collect { event ->
                when (event) {
                    is ConversationEvent.DataChanged -> refreshConversations()
                    is ConversationEvent.NewMessage -> handleNewMessage()
                    is ConversationEvent.MessageUpdated -> handleMessageUpdated()
                    is ConversationEvent.ChatRead -> markChatRead(event.guid)
                }
            }
        }
    }
}
```

### Migration Steps

1. **Apply AssistedInject to delegates** (same as Phase 3):

```kotlin
class ConversationLoadingDelegate @AssistedInject constructor(
    private val chatRepository: ChatRepository,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): ConversationLoadingDelegate
    }
}
```

2. **Replace callbacks with events** in `ConversationObserverDelegate`

3. **Update ConversationsViewModel** to use factories and collect events

### Files to Modify

| File | Change |
|------|--------|
| `ConversationLoadingDelegate.kt` | Add AssistedInject |
| `ConversationActionsDelegate.kt` | Add AssistedInject |
| `ConversationObserverDelegate.kt` | Replace callbacks with events |
| `UnifiedGroupMappingDelegate.kt` | Add AssistedInject if applicable |
| `ConversationsViewModel.kt` | Use factories, collect events |

## Target 2: SetupViewModel DI

### Current Problem

SetupViewModel manually constructs delegates:

```kotlin
// CURRENT - Manual construction, NOT DI
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val api: BothBubblesApi,
    // ...
) : ViewModel() {
    // Manual construction - not injectable!
    private val permissionsDelegate = PermissionsDelegate(context)
    private val serverConnectionDelegate = ServerConnectionDelegate(settingsDataStore, api)
    private val smsSetupDelegate = SmsSetupDelegate(smsPermissionHelper, settingsDataStore, smsRepository)
}
```

### Target State

```kotlin
// AFTER - Proper DI injection
class SetupViewModel @Inject constructor(
    // Injected via Hilt
    val permissions: PermissionsDelegate,
    val serverConnection: ServerConnectionDelegate,
    val smsSetup: SmsSetupDelegate
) : ViewModel()

// Each delegate becomes @Inject-able (no AssistedInject needed - no runtime params)
class PermissionsDelegate @Inject constructor(
    @ApplicationContext private val context: Context
)

class ServerConnectionDelegate @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val api: BothBubblesApi
)

class SmsSetupDelegate @Inject constructor(
    private val smsPermissionHelper: SmsPermissionHelper,
    private val settingsDataStore: SettingsDataStore,
    private val smsRepository: SmsRepository
)
```

**Note**: Setup delegates don't need AssistedInject because they don't require runtime parameters like `chatGuid`.

### Files to Modify

| File | Change |
|------|--------|
| `PermissionsDelegate.kt` | Add @Inject constructor |
| `ServerConnectionDelegate.kt` | Add @Inject constructor |
| `SmsSetupDelegate.kt` | Add @Inject constructor |
| `SetupViewModel.kt` | Inject delegates, remove manual construction |

## Target 3: Service Bootstrapping

### Current Problem

Manual initialization calls in Application:

```kotlin
// CURRENT - Manual calls, easy to forget order
override fun onCreate() {
    super.onCreate()
    appLifecycleTracker.initialize()
    activeConversationManager.initialize()
    connectionModeManager.initialize()
}
```

### Option A: AndroidX Startup (Recommended)

```kotlin
class AppLifecycleInitializer : Initializer<AppLifecycleTracker> {
    override fun create(context: Context): AppLifecycleTracker {
        val tracker = EntryPointAccessors.fromApplication(
            context,
            AppLifecycleTrackerEntryPoint::class.java
        ).tracker()
        tracker.initialize()
        return tracker
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppLifecycleTrackerEntryPoint {
    fun tracker(): AppLifecycleTracker
}
```

**AndroidManifest.xml:**
```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup">
    <meta-data
        android:name="com.bothbubbles.AppLifecycleInitializer"
        android:value="androidx.startup" />
</provider>
```

### Option B: Eager DI (Simpler)

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object ServiceInitModule {
    @Provides
    @Singleton
    fun provideAppLifecycleTracker(
        @ApplicationContext context: Context
    ): AppLifecycleTracker {
        return AppLifecycleTracker(context).apply { initialize() }
    }
}
```

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

**Status Update (2024-12)**: Phase 2+3 complete for ChatViewModel. Interface extractions (PendingMessageSource, VCardExporter) and quick-win migrations (SettingsViewModel) can proceed now. ConversationsViewModel migration should wait until after Phase 4 or stability verification.
