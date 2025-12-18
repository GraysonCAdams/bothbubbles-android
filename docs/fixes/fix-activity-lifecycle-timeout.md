# Fix Plan: Activity Lifecycle Timeout

**Error:** Activity pause/resume timeout
**Severity:** Medium
**Log:** `Activity top resumed state loss timeout` and `Activity pause timeout`

---

## Problem

`MainActivity` takes too long to respond to lifecycle events (pause/resume), causing Android to log timeout warnings. This indicates potential ANR (Application Not Responding) risk.

## Root Cause

Possible causes:
1. Heavy operations on main thread during `onPause()`/`onResume()`
2. Synchronous I/O or network calls in lifecycle methods
3. Long-running database operations
4. Blocking socket operations
5. Complex UI teardown/setup

## Implementation Plan

### Step 1: Audit MainActivity Lifecycle Methods

Location: `app/src/main/kotlin/com/bothbubbles/MainActivity.kt`

Check for blocking operations in:
- `onPause()`
- `onResume()`
- `onStop()`
- `onStart()`

### Step 2: Profile with StrictMode (Debug)

Add StrictMode to catch main thread violations:

```kotlin
// In Application.onCreate() for debug builds
if (BuildConfig.DEBUG) {
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyLog()
            .build()
    )
    StrictMode.setVmPolicy(
        StrictMode.VmPolicy.Builder()
            .detectLeakedSqlLiteObjects()
            .detectLeakedClosableObjects()
            .penaltyLog()
            .build()
    )
}
```

### Step 3: Move Heavy Operations Off Main Thread

#### Pattern: Database Operations

```kotlin
// BAD - blocks main thread
override fun onPause() {
    super.onPause()
    database.saveState(currentState)  // Blocking!
}

// GOOD - async
override fun onPause() {
    super.onPause()
    lifecycleScope.launch(Dispatchers.IO) {
        database.saveState(currentState)
    }
}
```

#### Pattern: Socket/Network Operations

```kotlin
// BAD
override fun onResume() {
    super.onResume()
    socketService.reconnect()  // May block
}

// GOOD
override fun onResume() {
    super.onResume()
    lifecycleScope.launch {
        withContext(Dispatchers.IO) {
            socketService.reconnect()
        }
    }
}
```

#### Pattern: Shared Preferences

```kotlin
// BAD - commit() blocks
override fun onPause() {
    super.onPause()
    prefs.edit().putLong("lastSeen", System.currentTimeMillis()).commit()
}

// GOOD - apply() is async
override fun onPause() {
    super.onPause()
    prefs.edit().putLong("lastSeen", System.currentTimeMillis()).apply()
}
```

### Step 4: Review ViewModel State Saving

Check if ViewModels do heavy work on configuration change:

```kotlin
// In ChatViewModel or similar
override fun onCleared() {
    // This runs on main thread - keep it light
    // Move heavy cleanup to coroutine
    viewModelScope.launch(Dispatchers.IO) {
        // Heavy cleanup here
    }
    super.onCleared()
}
```

### Step 5: Check Compose Lifecycle Effects

Review `DisposableEffect` and `LaunchedEffect` in screens:

```kotlin
// BAD - heavy work in onDispose
DisposableEffect(Unit) {
    onDispose {
        heavyCleanupOperation()  // Blocks!
    }
}

// GOOD - launch cleanup async
DisposableEffect(Unit) {
    onDispose {
        scope.launch(Dispatchers.IO) {
            heavyCleanupOperation()
        }
    }
}
```

### Step 6: Review Service Binding/Unbinding

Check `ServiceConnection` callbacks:

```kotlin
// BAD
override fun onServiceDisconnected(name: ComponentName?) {
    // Heavy cleanup on main thread
}

// GOOD
override fun onServiceDisconnected(name: ComponentName?) {
    CoroutineScope(Dispatchers.IO).launch {
        // Heavy cleanup off main thread
    }
}
```

### Step 7: Add Lifecycle Timing Logs (Debug)

```kotlin
// Temporary for debugging
override fun onPause() {
    val start = System.currentTimeMillis()
    super.onPause()
    // ... existing code ...
    Log.d(TAG, "onPause took ${System.currentTimeMillis() - start}ms")
}

override fun onResume() {
    val start = System.currentTimeMillis()
    super.onResume()
    // ... existing code ...
    Log.d(TAG, "onResume took ${System.currentTimeMillis() - start}ms")
}
```

### Step 8: Common Fixes Checklist

- [ ] Replace `SharedPreferences.commit()` with `apply()`
- [ ] Move database writes to IO dispatcher
- [ ] Move socket operations to IO dispatcher
- [ ] Move file I/O to IO dispatcher
- [ ] Check for synchronous API calls
- [ ] Review `DisposableEffect` cleanup blocks
- [ ] Check service binding operations
- [ ] Review bitmap/image processing

## Testing

1. Use Android Profiler to monitor main thread
2. Enable StrictMode in debug builds
3. Test app transitions (home, back, recent apps)
4. Monitor logcat for timeout warnings
5. Test under memory pressure (open many apps)

## Success Criteria

- [ ] No "Activity pause timeout" warnings in logs
- [ ] No "Activity top resumed state loss timeout" warnings
- [ ] StrictMode shows no violations
- [ ] Lifecycle methods complete in < 100ms
- [ ] No ANR dialogs during app transitions
