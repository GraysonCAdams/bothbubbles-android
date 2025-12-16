# Phase 12 — Observability & Crash Reporting

> **Status**: Planned
> **Prerequisite**: Phase 11 (Architectural Completion) — recommended but not blocking

## Layman's Explanation

Right now, when the app crashes on a user's device, we have no visibility into what went wrong. The only logging we have is an in-memory `DeveloperEventLog` that's lost when the app restarts. This makes debugging production issues nearly impossible.

This phase adds production-grade observability: crash reporting, structured logging with breadcrumbs, memory leak detection, and performance monitoring.

## Connection to Shared Vision

This phase supports our testability and maintainability goals by making production issues visible and diagnosable. Good observability enables faster iteration and higher confidence in releases.

## Goals

1. **Firebase Crashlytics**: Automatic crash reporting with stack traces and device info
2. **Timber Logging**: Replace `android.util.Log` with Timber for structured logging and breadcrumbs
3. **LeakCanary**: Memory leak detection in debug builds
4. **Performance Monitoring**: Track app startup, ANR rates, and frame drops

## Current State

| Component | Current | Target |
|-----------|---------|--------|
| Crash Reporting | None | Firebase Crashlytics |
| Logging | 1,035+ `android.util.Log` calls | Timber with Crashlytics tree |
| Memory Leaks | No detection | LeakCanary (debug) |
| Performance | Manual observation | Firebase Performance / Android Vitals |
| Developer Log | In-memory 500-event buffer | Retained, plus Crashlytics breadcrumbs |

## Implementation Steps

### Step 1: Add Firebase Crashlytics

```kotlin
// build.gradle.kts (app)
implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
implementation("com.google.firebase:firebase-crashlytics-ktx")
implementation("com.google.firebase:firebase-analytics-ktx")

// build.gradle.kts (project)
id("com.google.firebase.crashlytics") version "2.9.9" apply false
```

**Files to modify:**
- `app/build.gradle.kts` — Add dependencies
- `build.gradle.kts` — Add plugin
- `BothBubblesApp.kt` — Initialize Crashlytics

### Step 2: Add Timber with Crashlytics Tree

```kotlin
// build.gradle.kts (app)
implementation("com.jakewharton.timber:timber:5.0.1")
```

```kotlin
// BothBubblesApp.kt
override fun onCreate() {
    super.onCreate()

    if (BuildConfig.DEBUG) {
        Timber.plant(Timber.DebugTree())
    } else {
        Timber.plant(CrashlyticsTree())
    }
}

// util/logging/CrashlyticsTree.kt
class CrashlyticsTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority >= Log.INFO) {
            FirebaseCrashlytics.getInstance().log("$tag: $message")
        }
        if (t != null && priority >= Log.ERROR) {
            FirebaseCrashlytics.getInstance().recordException(t)
        }
    }
}
```

### Step 3: Migrate Log Calls to Timber

Create a migration script or use IDE find/replace:

| Before | After |
|--------|-------|
| `Log.d(TAG, "message")` | `Timber.d("message")` |
| `Log.e(TAG, "error", e)` | `Timber.e(e, "error")` |
| `Log.w(TAG, "warning")` | `Timber.w("warning")` |
| `Log.i(TAG, "info")` | `Timber.i("info")` |

**Scope**: ~1,035 log calls across the codebase

**Migration Strategy**:
1. Start with high-traffic files (services, repositories)
2. Use regex find/replace: `Log\.(d|e|w|i|v)\(TAG,\s*` → `Timber.$1(`
3. Remove TAG constants from files after migration
4. Can be done incrementally across multiple PRs

### Step 4: Add LeakCanary (Debug Only)

```kotlin
// build.gradle.kts (app)
debugImplementation("com.squareup.leakcanary:leakcanary-android:2.12")
```

No code changes needed — LeakCanary auto-initializes in debug builds.

### Step 5: Add Firebase Performance Monitoring

```kotlin
// build.gradle.kts (app)
implementation("com.google.firebase:firebase-perf-ktx")

// build.gradle.kts (project)
id("com.google.firebase.firebase-perf") version "1.4.2" apply false
```

**Key metrics to track:**
- App startup time (cold/warm)
- Network request latency (already traced via OkHttp)
- Frame rendering time
- ANR rate

### Step 6: Integrate with DeveloperEventLog

Keep the existing `DeveloperEventLog` for in-app debugging, but also forward events to Crashlytics:

```kotlin
// services/developer/DeveloperEventLog.kt
fun logSocketEvent(event: String, details: String? = null) {
    val entry = EventEntry(...)
    events.add(entry)

    // Also log to Crashlytics for breadcrumbs
    Timber.d("Socket: $event ${details ?: ""}")
}
```

### Step 7: Add User Context to Crashlytics

```kotlin
// When user signs in or server connects
FirebaseCrashlytics.getInstance().apply {
    setUserId(anonymizedUserId)  // Hash of server URL or similar
    setCustomKey("server_version", serverVersion)
    setCustomKey("has_sms_permissions", hasSmsPermissions)
}
```

## Exit Criteria

- [ ] Firebase Crashlytics configured and receiving crash reports
- [ ] Timber replaces all `android.util.Log` calls (or 80%+ with plan for rest)
- [ ] `CrashlyticsTree` logs INFO+ to Crashlytics breadcrumbs
- [ ] `CrashlyticsTree` records ERROR+ exceptions to Crashlytics
- [ ] LeakCanary installed in debug builds
- [ ] Firebase Performance Monitoring enabled
- [ ] `DeveloperEventLog` events forwarded to Timber
- [ ] No regressions in app startup time (< 10% increase)
- [ ] Crash reports verified in Firebase Console

## Inventory

| Task | Files | Effort | Owner | Status |
|------|-------|--------|-------|--------|
| Add Firebase Crashlytics | `build.gradle.kts`, `BothBubblesApp.kt` | 2h | _Unassigned_ | ☐ |
| Add Timber dependency | `build.gradle.kts` | 30m | _Unassigned_ | ☐ |
| Create CrashlyticsTree | `util/logging/CrashlyticsTree.kt` | 1h | _Unassigned_ | ☐ |
| Migrate Log.d calls (~400) | Various | 4h | _Unassigned_ | ☐ |
| Migrate Log.e calls (~200) | Various | 2h | _Unassigned_ | ☐ |
| Migrate Log.w/Log.i calls (~435) | Various | 3h | _Unassigned_ | ☐ |
| Add LeakCanary | `build.gradle.kts` | 30m | _Unassigned_ | ☐ |
| Add Firebase Performance | `build.gradle.kts` | 1h | _Unassigned_ | ☐ |
| Update DeveloperEventLog | `DeveloperEventLog.kt` | 1h | _Unassigned_ | ☐ |
| Add user context | Various | 1h | _Unassigned_ | ☐ |
| Verify crash reporting works | Manual testing | 2h | _Unassigned_ | ☐ |

**Total Estimated Effort**: 18-20 hours

## Risks

- **Low**: Adding observability is additive, minimal behavior change
- **Medium**: Timber migration touches many files (can be done incrementally)
- **Low**: Firebase SDK size increase (~1-2 MB)

## Dependencies

- Firebase project must be configured (`google-services.json`)
- No dependency on CI/CD — can be verified manually
- No dependency on Phase 11 (can run in parallel if needed)

## Next Steps

Phase 13 (Testing Infrastructure) can proceed in parallel. Observability helps debug test failures in production.
