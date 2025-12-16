# Phase 5: Service Layer Hygiene — Unified Implementation Plan

> **Status**: Maintenance Phase (Lower Priority)
> **Blocking**: Recommended after Phases 2-4 complete
> **Code Changes**: Context usage, scope management, framework thinning
> **Risk Level**: Medium (touches background execution)

## Overview

This phase ensures clean separation between Android framework components (Services, BroadcastReceivers, Workers) and testable business logic (singleton services). Framework components should be thin shells that delegate to injectable singletons.

## Core Principle

> **Framework components handle Android lifecycle; business logic lives in testable singletons.**

## Key Distinction

| Type | Examples | Characteristics |
|------|----------|-----------------|
| **Android Framework Components** | `SocketForegroundService`, `BootReceiver`, `MessageSendWorker` | Lifecycle-bound, Android-aware, thin |
| **Injected Singleton Services** | `SocketService`, `MessageSendingService`, `NotificationService` | Testable, pure logic, no UI coupling |

## Goals

1. **Framework components are thin** — Under 150 LOC, delegate immediately to singletons
2. **No Activity context leaks** — Singletons use `@ApplicationContext` only
3. **Bounded coroutine scopes** — No `GlobalScope`, all scopes cancellable
4. **Safe initialization** — No manual `initialize()` ordering requirements

## Implementation Tasks

### Task 1: Audit Framework Components

Create `service_layer_audit.md`:

```bash
# Find all framework components
grep -r "extends Service" app/src/main/kotlin/
grep -r ": Service()" app/src/main/kotlin/
grep -r ": BroadcastReceiver()" app/src/main/kotlin/
grep -r ": CoroutineWorker" app/src/main/kotlin/
grep -r ": FirebaseMessagingService()" app/src/main/kotlin/
```

| Component | Type | LOC | Concern | Priority |
|-----------|------|-----|---------|----------|
| `SocketForegroundService` | Service | ? | Check if thin | High |
| `BootReceiver` | BroadcastReceiver | ? | Check logic | Medium |
| `MessageSendWorker` | Worker | ? | Verify delegation | High |
| `FcmMessageHandler` | FirebaseMessagingService | ? | Verify thin | Medium |

### Task 2: Verify Framework Component Thinness

**Good Pattern — SocketForegroundService:**

```kotlin
@AndroidEntryPoint
class SocketForegroundService : Service() {

    @Inject
    lateinit var socketService: SocketService  // Delegated singleton

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Thin - just delegates
        socketService.connect()
        startForeground(NOTIF_ID, createNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        socketService.disconnect()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        // Only Android-specific notification code here
    }
}
```

**Bad Pattern — Logic in Framework Component:**

```kotlin
// DO NOT DO THIS
class SocketForegroundService : Service() {
    override fun onStartCommand(...): Int {
        // BAD: Business logic in framework component
        val socket = IO.socket(serverUrl)
        socket.on("message") { data ->
            // Complex message handling - should be in SocketService!
            val message = parseMessage(data)
            messageDao.insert(message)
            notificationManager.notify(...)
        }
        socket.connect()
    }
}
```

### Task 3: Audit Singleton Services

For each singleton service, verify:

```markdown
## Singleton Service Checklist

### SocketService
- [ ] No dependency on Compose/UI/ViewModels
- [ ] Uses `@ApplicationContext` if Context needed
- [ ] Coroutine scopes are bounded (`@ApplicationScope`)
- [ ] Exposes state via `StateFlow`/`SharedFlow`
- [ ] No `lateinit var` for critical state
- [ ] Interface exists (`SocketConnection`)

### MessageSendingService
- [ ] Same checks as above
- [ ] Interface: `MessageSender`

### NotificationService
- [ ] Same checks as above
- [ ] Interface: `Notifier`
```

### Task 4: Fix Context Usage

**Find Context leaks:**

```bash
grep -r "private val context:" app/src/main/kotlin/com/bothbubbles/services/
grep -r "context: Context" app/src/main/kotlin/com/bothbubbles/services/
```

**Bad — Activity context in singleton:**

```kotlin
// WRONG - Could be Activity context, causes memory leak
@Singleton
class MyService @Inject constructor(
    private val context: Context  // Unknown context type!
)
```

**Good — Explicit ApplicationContext:**

```kotlin
// CORRECT
@Singleton
class MyService @Inject constructor(
    @ApplicationContext private val context: Context
)
```

### Task 5: Fix Unbounded Coroutine Scopes

**Find violations:**

```bash
grep -r "GlobalScope" app/src/main/kotlin/com/bothbubbles/
grep -r "CoroutineScope()" app/src/main/kotlin/com/bothbubbles/services/
```

**Bad — GlobalScope:**

```kotlin
// WRONG - Lives forever, never cancelled
class MyService {
    fun doSomething() {
        GlobalScope.launch {
            // Never cancelled!
        }
    }
}
```

**Good — Bounded scope from DI:**

```kotlin
// CORRECT - Bounded to application lifecycle
@Singleton
class MyService @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope
) {
    fun doSomething() {
        scope.launch {
            // Cancelled when app terminates
        }
    }
}
```

### Task 6: Fix Initialize() Pattern in Services

**Current problem in BothBubblesApp.kt:**

```kotlin
override fun onCreate() {
    super.onCreate()
    appLifecycleTracker.initialize()      // Manual call - easy to forget
    activeConversationManager.initialize()
    connectionModeManager.initialize()
}
```

**Option A: Eager Initialization in DI Module:**

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object ServiceInitModule {

    @Provides
    @Singleton
    fun provideAppLifecycleTracker(
        @ApplicationContext context: Context
    ): AppLifecycleTracker {
        return AppLifecycleTracker(context).apply {
            initialize()  // Initialize during DI provision
        }
    }
}
```

**Option B: Lazy Property Initialization:**

```kotlin
@Singleton
class AppLifecycleTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Lazy - initializes on first access
    val foregroundState: StateFlow<Boolean> by lazy {
        MutableStateFlow(false).also { flow ->
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        flow.value = true
                    }
                    override fun onStop(owner: LifecycleOwner) {
                        flow.value = false
                    }
                }
            )
        }
    }
}
```

**Option C: AndroidX Startup (For complex dependencies):**

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

// AndroidManifest.xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup">
    <meta-data
        android:name="com.bothbubbles.AppLifecycleInitializer"
        android:value="androidx.startup" />
</provider>
```

**Option D: Document Order (Minimal change):**

```kotlin
// BothBubblesApp.kt
override fun onCreate() {
    super.onCreate()

    // IMPORTANT: Initialize in this exact order!
    // See docs/service_initialization.md for rationale
    appLifecycleTracker.initialize()      // 1. Must be first
    activeConversationManager.initialize() // 2. Uses lifecycle tracker
    connectionModeManager.initialize()     // 3. Uses both above
}
```

### Task 7: MessageSendWorker Verification

Ensure Workers delegate correctly:

```kotlin
class MessageSendWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val messageSender: MessageSender  // Interface!
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Thin - just delegates
        val messageGuid = inputData.getString("guid") ?: return Result.failure()

        return when (messageSender.retryMessage(messageGuid)) {
            is kotlin.Result.Success -> Result.success()
            is kotlin.Result.Failure -> Result.retry()
        }
    }

    @AssistedFactory
    interface Factory : ChildWorkerFactory
}
```

## Naming Clarification (Optional)

The codebase uses "Service" for both:
1. Android `Service` components (`SocketForegroundService`)
2. Business logic singletons (`SocketService`, `MessageSendingService`)

**Consider renaming** for clarity (optional, breaking change):
- `SocketService` → `SocketManager` or `SocketClient`
- `MessageSendingService` → `MessageSendingManager`

**Keep "Service" suffix** for actual Android Services.

## Files to Audit

| File | Type | Priority |
|------|------|----------|
| `services/socket/SocketService.kt` | Singleton | High |
| `services/messaging/MessageSendingService.kt` | Singleton | High |
| `services/foreground/SocketForegroundService.kt` | Framework | Medium |
| `services/messaging/MessageSendWorker.kt` | Framework | Medium |
| `services/AppLifecycleTracker.kt` | Singleton | Medium |
| `BothBubblesApp.kt` | Application | Low |

## Exit Criteria

- [ ] Framework components are thin (<150 LOC, delegate only)
- [ ] Singleton services use `@ApplicationContext` (no Activity leaks)
- [ ] Coroutine scopes are bounded (no GlobalScope)
- [ ] Service initialization is safe (documented order OR automated)
- [ ] No `GlobalScope` usage in services
- [ ] Interfaces exist for key singletons (testability)

## Verification Commands

```bash
# Check for GlobalScope
grep -r "GlobalScope" app/src/main/kotlin/com/bothbubbles/

# Check for context without @ApplicationContext
grep -r "context: Context" app/src/main/kotlin/com/bothbubbles/services/ | grep -v "@ApplicationContext"

# Count LOC in framework components
wc -l app/src/main/kotlin/com/bothbubbles/services/foreground/*.kt

# Verify interfaces exist
ls app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSender.kt
ls app/src/main/kotlin/com/bothbubbles/services/socket/SocketConnection.kt
```

## Test Validation

After cleanup, verify singletons are testable:

```kotlin
@Test
fun `MessageSendingService can be unit tested`() = runTest {
    // Should work with fakes - no Android dependencies
    val service = MessageSendingService(
        pendingMessageRepository = FakePendingMessageRepository(),
        api = FakeBothBubblesApi(),
        context = mockApplicationContext()
    )

    val result = service.sendUnified(...)
    assertTrue(result.isSuccess)
}
```

---

**Note**: This phase is optional but recommended for long-term maintainability. Prioritize if you notice testing difficulties or background behavior issues.
