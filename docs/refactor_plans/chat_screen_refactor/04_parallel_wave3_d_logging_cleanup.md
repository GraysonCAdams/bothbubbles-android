# Wave 3D (Parallel): Logging Cleanup

**Prerequisites**:
- Read `00_shared_conventions.md` first
- Wave 2 (sequential ChatScreen cutover) must be complete

**Owned Files**: `ChatScreen.kt`, `ChatInputUI.kt`, `ChatMessageList.kt`, `MessageBubble.kt`, `ChatScrollHelper.kt`

---

## Objective

Remove or guard all logging and timing calls in composition hot paths to eliminate overhead in release builds.

---

## Tasks

### 1. Search for Logging Calls

Search for these patterns in all owned files:

- `Log.d(`
- `Log.v(`
- `Log.i(`
- `Log.w(`
- `Log.e(`
- `System.currentTimeMillis()`
- `PerfTrace`
- `Timber.`

### 2. Guard or Remove Logging

For each logging call found, either:

**Option A: Guard with BuildConfig.DEBUG**

```kotlin
// BEFORE
Log.d(TAG, "Recomposition triggered for message: $guid")

// AFTER
if (BuildConfig.DEBUG) {
    Log.d(TAG, "Recomposition triggered for message: $guid")
}
```

**Option B: Remove entirely if not useful**

```kotlin
// REMOVE if logging is just noise
Log.d(TAG, "onDraw called")
```

### 3. Guard Timing Calls

```kotlin
// BEFORE
val start = System.currentTimeMillis()
// ... work ...
val elapsed = System.currentTimeMillis() - start
Log.d(TAG, "Operation took ${elapsed}ms")

// AFTER
if (BuildConfig.DEBUG) {
    val start = System.currentTimeMillis()
    // ... work ...
    val elapsed = System.currentTimeMillis() - start
    Log.d(TAG, "Operation took ${elapsed}ms")
}

// OR use inline function that compiles out:
inline fun <T> debugTimed(tag: String, label: String, block: () -> T): T {
    return if (BuildConfig.DEBUG) {
        val start = System.currentTimeMillis()
        block().also {
            Log.d(tag, "$label took ${System.currentTimeMillis() - start}ms")
        }
    } else {
        block()
    }
}
```

### 4. Special Cases

**SideEffect logging (MessageBubble.kt)**

```kotlin
// BEFORE - runs on every recomposition
SideEffect {
    Log.d(TAG, "MessageBubble recomposed: $guid")
}

// AFTER - remove or guard
if (BuildConfig.DEBUG) {
    SideEffect {
        Log.d(TAG, "MessageBubble recomposed: $guid")
    }
}
```

**Composition-time logging**

```kotlin
// BEFORE - creates string on every composition
Text(text = "Count: ${Log.d(TAG, "rendering"); count}")  // DON'T DO THIS

// AFTER - move logging out of composition
if (BuildConfig.DEBUG) Log.d(TAG, "rendering")
Text(text = "Count: $count")
```

### 5. Add BuildConfig Import

Ensure import exists in each file:

```kotlin
import com.bothbubbles.BuildConfig
```

---

## Files to Check

| File | Common Logging Locations |
|------|-------------------------|
| `ChatScreen.kt` | Recomposition debug, effect triggers |
| `ChatInputUI.kt` | Input handling, recording state |
| `ChatMessageList.kt` | Scroll events, message loading |
| `MessageBubble.kt` | Bubble rendering, temp message state |
| `ChatScrollHelper.kt` | Scroll position tracking |

---

## Verification

- [x] No unguarded `Log.*` calls in composition paths
- [x] No unguarded `System.currentTimeMillis()` in composition paths
- [x] All logging wrapped in `if (BuildConfig.DEBUG)`
- [x] Build succeeds: `./gradlew assembleDebug`
- [ ] Release build has no logging output: `./gradlew assembleRelease`

---

## Notes

- Focus on hot paths (composition, scroll handlers, message rendering)
- Error logging (`Log.e`) for actual errors can remain unguarded
- Timber calls follow the same pattern - guard with `BuildConfig.DEBUG`
