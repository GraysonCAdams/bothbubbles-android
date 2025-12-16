# Phase 5: Implementation Guide (Service Layer Hygiene)

## Goal

Audit and tighten boundaries between Android framework components and testable singleton services. This phase is **lower priority** than Phases 2-4 but improves long-term maintainability.

## When to Do This

- After Phases 2-4 are complete
- When you notice testing difficulties in services
- When background behavior is fragile

## Key Distinction

| Type | Examples | Characteristics |
|------|----------|-----------------|
| **Android Framework Components** | `SocketForegroundService`, `BootReceiver`, `MessageSendWorker` | Lifecycle-bound, Android-aware, thin |
| **Injected Singleton Services** | `SocketService`, `MessageSendingService`, `NotificationService` | Testable, pure logic, no UI |

## Current State Analysis

### Services with Initialize Pattern (Concern)

```kotlin
// BothBubblesApp.onCreate()
appLifecycleTracker.initialize()
activeConversationManager.initialize()
connectionModeManager.initialize()
```

**Problem**: If a developer adds a service without calling `initialize()`, it silently fails.

### Framework Components (Audit These)

| Component | Type | Concern |
|-----------|------|---------|
| `SocketForegroundService` | `Service` | Should delegate to `SocketService` |
| `BootReceiver` | `BroadcastReceiver` | Should be thin, delegate logic |
| `MessageSendWorker` | `Worker` | Should delegate to `MessageSendingService` |
| `FcmMessageHandler` | `FirebaseMessagingService` | Should be thin |

## Audit Checklist

### Singleton Service Checklist

For each singleton service (`SocketService`, `MessageSendingService`, etc.):

- [ ] No dependency on Compose/UI/ViewModels
- [ ] Uses `@ApplicationContext` if Context is needed (not Activity context)
- [ ] Coroutine scopes are bounded and cancelable
- [ ] Exposes state via `StateFlow`/`SharedFlow` with clear ownership
- [ ] No `lateinit var` for critical state
- [ ] Interface exists for testability (`MessageSender`, `SocketConnection`, etc.)

### Framework Component Checklist

For each framework component:

- [ ] Minimal logic in the component itself
- [ ] Delegates to injected singleton for business logic
- [ ] Handles lifecycle cleanly (start/stop/cancel)
- [ ] No business logic that can't be unit tested

## Example Audit: SocketForegroundService

### What to Check

```kotlin
// SocketForegroundService.kt
class SocketForegroundService : Service() {

    @Inject
    lateinit var socketService: SocketService  // ✅ Delegates to singleton

    override fun onStartCommand(...): Int {
        // ✅ GOOD: Thin wrapper, delegates to SocketService
        socketService.connect()
        return START_STICKY
    }

    override fun onDestroy() {
        // ✅ GOOD: Clean lifecycle
        socketService.disconnect()
    }
}
```

### Red Flags to Look For

```kotlin
// ❌ BAD: Business logic in framework component
class SocketForegroundService : Service() {
    override fun onStartCommand(...): Int {
        // ❌ Should be in SocketService, not here
        val socket = IO.socket(serverUrl)
        socket.on("message") { data ->
            // Complex message handling logic
        }
        socket.connect()
    }
}
```

## Example Audit: MessageSendWorker

### What to Check

```kotlin
class MessageSendWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val messageSender: MessageSender  // ✅ Delegates via interface
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // ✅ GOOD: Thin wrapper
        val messageGuid = inputData.getString("guid") ?: return Result.failure()
        return when (messageSender.retryMessage(messageGuid)) {
            is kotlin.Result.Success -> Result.success()
            is kotlin.Result.Failure -> Result.retry()
        }
    }
}
```

## Fixing Initialize Pattern

### Option A: Eager Initialization via Hilt

```kotlin
// Use Hilt's @EagerSingleton or init in module
@Module
@InstallIn(SingletonComponent::class)
object ServiceInitModule {

    @Provides
    @Singleton
    fun provideAppLifecycleTracker(
        @ApplicationContext context: Context
    ): AppLifecycleTracker {
        return AppLifecycleTracker(context).also {
            it.initialize()  // Initialize during provision
        }
    }
}
```

### Option B: Lazy Property Initialization

```kotlin
@Singleton
class AppLifecycleTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // ✅ Lazy initialization - safe, no explicit call needed
    private val processLifecycleOwner by lazy {
        ProcessLifecycleOwner.get()
    }

    val foregroundState: StateFlow<Boolean> by lazy {
        // Setup observation lazily
        MutableStateFlow(false).also { flow ->
            processLifecycleOwner.lifecycle.addObserver(...)
        }
    }
}
```

### Option C: Document Required Order (Minimal Change)

If refactoring is too risky, at least document:

```kotlin
// BothBubblesApp.kt
override fun onCreate() {
    super.onCreate()

    // IMPORTANT: Initialize in this exact order
    // See docs/service_initialization.md for rationale
    appLifecycleTracker.initialize()      // 1. Must be first (others depend on it)
    activeConversationManager.initialize() // 2. Uses lifecycle tracker
    connectionModeManager.initialize()     // 3. Uses both above
}
```

## Context Usage Audit

### Find All Context Usage

```bash
grep -r "context:" app/src/main/kotlin/com/bothbubbles/services/
grep -r "private val context" app/src/main/kotlin/com/bothbubbles/services/
```

### Check for Activity Context Leaks

```kotlin
// ❌ BAD: Activity context in singleton (memory leak)
@Singleton
class MyService @Inject constructor(
    private val context: Context  // Could be Activity context!
)

// ✅ GOOD: Explicitly ApplicationContext
@Singleton
class MyService @Inject constructor(
    @ApplicationContext private val context: Context
)
```

## Coroutine Scope Audit

### Find Unbounded Scopes

```bash
grep -r "GlobalScope" app/src/main/kotlin/com/bothbubbles/
grep -r "CoroutineScope()" app/src/main/kotlin/com/bothbubbles/services/
```

### Fix Unbounded Scopes

```kotlin
// ❌ BAD: Unbounded scope
class MyService {
    fun doSomething() {
        GlobalScope.launch {
            // Lives forever, never cancelled
        }
    }
}

// ✅ GOOD: Bounded scope from DI
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

## Naming Clarification

The codebase uses "Service" for both:
1. Android `Service` components (e.g., `SocketForegroundService`)
2. Business logic singletons (e.g., `SocketService`, `MessageSendingService`)

### Recommendation (Optional)

Consider renaming to clarify:
- `SocketService` → `SocketManager` or `SocketClient`
- `MessageSendingService` → `MessageSendingManager`
- Keep actual Android Services with "Service" suffix

**Note**: This is a style preference. The current naming is internally consistent - just be aware of the distinction.

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

- [ ] Framework components are thin wrappers (no business logic)
- [ ] Singleton services use `@ApplicationContext` (no Activity leaks)
- [ ] Coroutine scopes are bounded
- [ ] Service initialization order is documented or automated
- [ ] No `GlobalScope` usage in services
