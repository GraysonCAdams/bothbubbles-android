# Phase 5 — Service Layer Hygiene (Framework vs Pure Logic)

> **Implementation Plan**: See [impl/README.md](impl/README.md) for audit checklists and code examples.
>
> **Status**: Optional but recommended after Phases 2-4 complete.

## Layman's Explanation

Some code needs Android (permissions, background execution). Some code should be "pure" business logic (send message, parse socket events). If we mix these too much, testing becomes harder and background behavior becomes fragile.

This phase audits and tightens the boundary between:
- **Android framework components** (real `Service`, receivers, WorkManager workers)
- **Injected singleton "services"** (application/domain services)

## Connection to Shared Vision

This phase reinforces the testability goals from [ADR 0003](../phase_0_shared_vision/ADR_0003_ui_depends_on_interfaces.md):

> **Business logic should be testable. Framework components should be thin shells.**

## Key Distinction

| Type | Examples | Characteristics |
|------|----------|-----------------|
| **Framework Components** | `SocketForegroundService`, `BootReceiver`, `MessageSendWorker` | Lifecycle-bound, thin |
| **Singleton Services** | `SocketService`, `MessageSendingService`, `NotificationService` | Testable, pure logic |

## Goals

- Framework components are thin wrappers (<150 LOC)
- Singleton services don't leak Activity contexts
- Coroutine scopes are bounded (no GlobalScope)
- Service initialization is safe (documented order OR automated)

## Key Checks

```kotlin
// Framework component - should be thin
class SocketForegroundService : Service() {
    @Inject lateinit var socketService: SocketService
    override fun onStartCommand(...) {
        socketService.connect()  // Delegate immediately
        startForeground(...)
    }
}

// Singleton service - should use @ApplicationContext
@Singleton
class SocketService @Inject constructor(
    @ApplicationContext private val context: Context  // NOT Activity context!
)
```

## Exit Criteria

- [ ] Framework components are thin (<150 LOC, delegate only)
- [ ] Singleton services use `@ApplicationContext`
- [ ] No `GlobalScope` usage in services
- [ ] Service initialization is safe
- [ ] Interfaces exist for key singletons

## Risks

- Medium: can touch background execution behavior
- Keep changes minimal and well-tested

## Next Steps

Phase 6 (Modularization) is optional and should only be pursued if build times are painful.
