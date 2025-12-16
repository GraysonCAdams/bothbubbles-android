# Phase 10 — Service Layer Modernization

> **Status**: Planned
> **Prerequisite**: Phase 5 (Service Layer Hygiene) — *partial; foreground service audit and worker constraints can proceed independently*

## Layman's Explanation

We've cleaned up *how* services are called (Phase 5), but *how they start* is still a bit manual. We manually start services in `BothBubblesApp.onCreate()`, which can slow down app startup.

This phase adopts modern Android standards like **AndroidX Startup** and **WorkManager** constraints to make the app start faster and behave better in the background.

## Goals

1.  **AndroidX Startup**: Use the Startup library to initialize components lazily and in parallel where possible.
2.  **WorkManager Constraints**: Ensure all background work respects battery and network constraints.
3.  **Foreground Service Types**: Ensure all foreground services declare their type (Data Sync, Connected Device, etc.) for Android 14 compliance.

## Targets

- `BothBubblesApp.kt` (Reduce `onCreate` logic)
- `SocketForegroundService` (Verify Android 14 compliance)
- `BackgroundSyncWorker` (Optimize constraints)

## Exit Criteria

- [ ] `BothBubblesApp.onCreate()` is minimal (< 20 lines of logic).
- [ ] Components initialize via `Initializer<T>`.
- [ ] All foreground services have valid `foregroundServiceType` in Manifest.

## Modernization Inventory

| Area | Current State | Planned Change | Owner | Status |
|------|---------------|----------------|-------|--------|
| `BothBubblesApp` startup | 40+ lines of initialization ordering comments | Move to AndroidX Startup initializers, leave only order-critical code | _Unassigned_ | ☐ |
| Coil/ImageLoader setup | Built inline in `BothBubblesApp` | Create `CoilInitializer` or reuse `ImageLoaderFactory` hook with Startup | _Unassigned_ | ☐ |
| WorkManager config | Defined via `BothBubblesApp.Configuration.Provider` | Provide custom `WorkManagerInitializer` leveraging Hilt WorkerFactory | _Unassigned_ | ☐ |
| Foreground services | Need audit for Android 14 `foregroundServiceType` | Update manifest entries for Socket, SMS, FCM services | _Unassigned_ | ☐ |
| Worker constraints | Some workers run unbounded | Apply network/battery constraints & documented rationale | _Unassigned_ | ☐ |
