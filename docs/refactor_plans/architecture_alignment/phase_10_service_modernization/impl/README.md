# Phase 10: Service Modernization — Implementation Plan

> **Status**: Planned
> **Goal**: Optimize app startup and background execution using modern Android libraries.

## Overview

We will move away from monolithic `Application.onCreate` initialization and towards `androidx.startup`. We will also ensure all background work is compliant with Android 14+ requirements.

## Step-by-Step Implementation

### Step 1: Add Dependencies

Add to `app/build.gradle.kts`:
```kotlin
implementation("androidx.startup:startup-runtime:1.1.1")
```

### Step 2: Create Initializers

Create `com.bothbubbles.app.initializers`:

1.  **`WorkManagerInitializer`**: Configures WorkManager (replaces default).
2.  **`CoilInitializer`**: Configures ImageLoader.
3.  **`TimberInitializer`** (if used): Sets up logging.

### Step 3: Clean up `BothBubblesApp.onCreate`

Move logic out of `onCreate` and into Initializers or lazy singletons.

**Goal:**
```kotlin
override fun onCreate() {
    super.onCreate()
    // Only critical, order-dependent logic remains here
    // e.g. AppLifecycleTracker
}
```

### Step 4: Audit Foreground Services

Check `AndroidManifest.xml` for `<service>` tags. Ensure all foreground services have a `foregroundServiceType`.

*   `SocketForegroundService`: likely `dataSync` or `remoteMessaging`.
*   `MessageSendingService`: likely `shortService` (if used as foreground).

### Step 5: Optimize WorkManager Constraints

Review `BackgroundSyncWorker` and `MessageSendWorker`. Ensure they have appropriate constraints:

```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .setRequiresBatteryNotLow(true)
    .build()
```

## Implementation Checklist

> **Note**: Items marked with ⚡ are **independent** and can be done anytime without waiting for other phases.

| Item | Description | Owner | Status | Deps |
|------|-------------|-------|--------|------|
| SVC-01 | Add `androidx.startup:startup-runtime` dependency | _Unassigned_ | ☐ | — |
| SVC-02 | Create `WorkManagerInitializer` that wires in `HiltWorkerFactory` | _Unassigned_ | ☐ | SVC-01 |
| SVC-03 | Create `CoilInitializer` (and optional logging initializer) | _Unassigned_ | ☐ | SVC-01 |
| SVC-04 | Strip `BothBubblesApp.onCreate` down to <20 LOC, document any remaining order dependencies | _Unassigned_ | ☐ | SVC-02, SVC-03 |
| SVC-05 ⚡ | Audit every foreground service and add `android:foregroundServiceType` | _Unassigned_ | ☐ | **None** |
| SVC-06 ⚡ | Apply modern constraints to `BackgroundSyncWorker`, `MessageSendWorker`, `ScheduledMessageWorker`, etc. | _Unassigned_ | ☐ | **None** |
| SVC-07 | Document startup measurements before/after change | _Unassigned_ | ☐ | SVC-04 |

### Quick Wins (Can Do Now)

These items don't depend on AndroidX Startup and can be completed independently:

- **SVC-05**: Foreground service type audit — Required for Android 14+ compliance
- **SVC-06**: WorkManager constraints — Improves battery/network efficiency

## Verification

*   **Startup Time**: Measure `App.onCreate` duration using Profiler.
*   **Lint Check**: Run Android Lint to check for Android 14 compliance issues.
