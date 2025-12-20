# Android-Specific Anti-Patterns

**Scope:** Context leaks, lifecycle, resources, permissions

---

## Medium Severity Issues

### 1. ~~Handler Lifecycle Leak - NavigationListenerService~~ FIXED

**Location:** `services/eta/NavigationListenerService.kt` (Lines 8, 66)

**Status:** FIXED - Now uses `handler.removeCallbacksAndMessages(null)` in `onDestroy()` to clean up all messages and callbacks.

---

### 2. ~~ContentObserver Handler Leak - ContactsContentObserver~~ FIXED

**Location:** `services/contacts/ContactsContentObserver.kt` (Lines 5-6, 72)

**Status:** FIXED - Handler stored as field, cleaned up with `removeCallbacksAndMessages(null)` in `stopObserving()`.

---

### 3. ~~ContentObserver Handler Leak - SmsContentObserver~~ FIXED

**Location:** `services/sms/SmsContentObserver.kt` (Lines 6-7, 79)

**Status:** FIXED - Handler stored as field, cleaned up with `removeCallbacksAndMessages(null)` in `stopObserving()`.

---

### 4. SoundPool Not Released on App Lifecycle End

**Location:** `services/sound/SoundManager.kt` (Lines 76-111, 276-281)

**Issue:**
```kotlin
@Singleton
class SoundManager @Inject constructor(...) {
    private var soundPool: SoundPool? = null

    init {
        initializeSoundPool()
    }

    fun release() {
        soundPool?.release()
        soundPool = null
    }
    // But release() is never called automatically!
}
```

**Problem:**
- SoundPool holds native audio resources
- No lifecycle observer to trigger cleanup
- Resources leak until app process dies

**Fix:**
```kotlin
@Singleton
class SoundManager @Inject constructor(
    @ApplicationContext private val context: Context
) : DefaultLifecycleObserver {

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        initializeSoundPool()
    }

    override fun onStop(owner: LifecycleOwner) {
        release()  // Release when app backgrounds
    }
}
```

---

### 5. ~~Repeated getSystemService Calls~~ FIXED

**Location:** `services/foreground/SocketForegroundService.kt` (Lines 180-184)

**Status:** FIXED - NotificationManager cached as lazy field, avoiding repeated `getSystemService()` calls.

---

## Summary Table

| Issue | Severity | File | Status |
|-------|----------|------|--------|
| Handler not cleaned in onDestroy | MEDIUM | NavigationListenerService.kt | ✅ FIXED |
| ContentObserver Handler leak | MEDIUM | ContactsContentObserver.kt | ✅ FIXED |
| ContentObserver Handler leak | MEDIUM | SmsContentObserver.kt | ✅ FIXED |
| SoundPool not released | MEDIUM | SoundManager.kt | Resource Leak |
| Repeated getSystemService | LOW | SocketForegroundService.kt | ✅ FIXED |

---

## Correctly Handled Patterns

The following patterns are implemented correctly:

- Cursor management with `.use {}` blocks
- Intent extra null safety in MainActivity
- ContentObserver unregistration
- ExoPlayer pool management with explicit release
- Foreground service notification setup
- BroadcastReceiver pendingResult finishing
