# Wave 3C (Parallel): Audio Stability & Sound Loading

**Prerequisites**:
- Read `00_shared_conventions.md` first
- Wave 2 (sequential ChatScreen cutover) must be complete

**Owned Files**: `ChatAudioHelper.kt`, `ChatAudioState.kt` (if separate file)

---

## Objective

1. Add `@Stable` annotation to `ChatAudioState`
2. Move `MediaActionSound.load()` off the main thread

---

## Tasks

### 1. Add @Stable Annotation

Find the `ChatAudioState` class and add the annotation:

```kotlin
// BEFORE
class ChatAudioState(
    // ...
)

// AFTER
@Stable
class ChatAudioState(
    // ...
)
```

Add the import if needed:
```kotlin
import androidx.compose.runtime.Stable
```

### 2. Move MediaActionSound.load() Off Main Thread

Find the `initialize()` function that loads sounds:

```kotlin
// BEFORE - blocks main thread during composition
fun initialize() {
    mediaActionSound.load(MediaActionSound.START_VIDEO_RECORDING)
    mediaActionSound.load(MediaActionSound.STOP_VIDEO_RECORDING)
}
```

**Option A: Lazy initialization (preferred)**

```kotlin
// AFTER - lazy load on first use
private var soundsLoaded = false

private fun ensureSoundsLoaded() {
    if (!soundsLoaded) {
        mediaActionSound.load(MediaActionSound.START_VIDEO_RECORDING)
        mediaActionSound.load(MediaActionSound.STOP_VIDEO_RECORDING)
        soundsLoaded = true
    }
}

fun playStartSound() {
    ensureSoundsLoaded()
    mediaActionSound.play(MediaActionSound.START_VIDEO_RECORDING)
}

fun playStopSound() {
    ensureSoundsLoaded()
    mediaActionSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
}
```

**Option B: Background coroutine**

```kotlin
// AFTER - load in background
fun initialize(scope: CoroutineScope) {
    scope.launch(Dispatchers.IO) {
        mediaActionSound.load(MediaActionSound.START_VIDEO_RECORDING)
        mediaActionSound.load(MediaActionSound.STOP_VIDEO_RECORDING)
    }
}
```

**Option C: Move to DisposableEffect with background loading**

```kotlin
// In the rememberChatAudioState() composable
DisposableEffect(Unit) {
    val job = scope.launch(Dispatchers.IO) {
        state.mediaActionSound.load(MediaActionSound.START_VIDEO_RECORDING)
        state.mediaActionSound.load(MediaActionSound.STOP_VIDEO_RECORDING)
    }
    onDispose {
        job.cancel()
        state.release()
    }
}
```

### 3. Add Required Imports

```kotlin
import androidx.compose.runtime.Stable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
```

---

## Verification

- [ ] `ChatAudioState` class has `@Stable` annotation
- [ ] `MediaActionSound.load()` does not block main thread
- [ ] Sound loading happens lazily or in background
- [ ] Build succeeds: `./gradlew assembleDebug`
- [ ] Audio recording/playback still works correctly

---

## Notes

- Lazy initialization (Option A) is simplest and has no threading complexity
- The first sound play might have a tiny delay, but this is acceptable
- MediaActionSound is already designed to handle concurrent access
