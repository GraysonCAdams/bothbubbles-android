# Android-Specific Anti-Patterns

**Scope:** Context leaks, lifecycle, resources, permissions

---

## Medium Severity Issues

### 1. Handler Lifecycle Leak - NavigationListenerService

**Location:** `services/eta/NavigationListenerService.kt` (Lines 8, 66)

**Issue:**
```kotlin
private val handler = Handler(Looper.getMainLooper())
private var pendingPromptRunnable: Runnable? = null

// In onDestroy:
pendingPromptRunnable?.let { handler.removeCallbacks(it) }
// But handler itself not cleaned up!
```

**Problem:**
- Handler created at class level, persists for service lifetime
- `pendingPromptRunnable` can hold reference to Handler
- Only specific runnable removed, not all messages

**Fix:**
```kotlin
override fun onDestroy() {
    super.onDestroy()
    handler.removeCallbacksAndMessages(null)  // Remove ALL
    pendingPromptRunnable = null
}
```

---

### 2. ContentObserver Handler Leak - ContactsContentObserver

**Location:** `services/contacts/ContactsContentObserver.kt` (Lines 5-6, 72)

**Issue:**
```kotlin
fun startObserving() {
    if (_isObserving.value) return

    val handler = Handler(Looper.getMainLooper())  // Local variable!

    observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { ... }
    }
    // handler reference is lost after function returns
}

fun stopObserving() {
    observer?.let { context.contentResolver.unregisterContentObserver(it) }
    observer = null
    // Cannot clean up handler's message queue - reference lost!
}
```

**Problem:**
- New Handler created each `startObserving()` call
- Handler reference not stored, can't clean up message queue
- Multiple calls create handler leaks

**Fix:**
```kotlin
private var handler: Handler? = null

fun startObserving() {
    if (_isObserving.value) return
    handler = handler ?: Handler(Looper.getMainLooper())
    // ...
}

fun stopObserving() {
    handler?.removeCallbacksAndMessages(null)
    observer?.let { context.contentResolver.unregisterContentObserver(it) }
    observer = null
    handler = null
}
```

---

### 3. ContentObserver Handler Leak - SmsContentObserver

**Location:** `services/sms/SmsContentObserver.kt` (Lines 6-7, 79)

**Issue:** Identical to ContactsContentObserver - local Handler created in `startObserving()`.

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

### 5. Repeated getSystemService Calls

**Location:** `services/foreground/SocketForegroundService.kt` (Lines 180-184)

**Issue:**
```kotlin
private fun updateNotification(statusText: String) {
    val notification = createNotification(statusText)
    val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(NOTIFICATION_ID, notification)
}
```

**Problem:**
- `getSystemService()` called on every notification update
- Called frequently during connection state changes
- Wasteful - system services should be cached

**Fix:**
```kotlin
private val notificationManager: NotificationManager by lazy {
    getSystemService(NotificationManager::class.java)
}

private fun updateNotification(statusText: String) {
    val notification = createNotification(statusText)
    notificationManager.notify(NOTIFICATION_ID, notification)
}
```

---

## Summary Table

| Issue | Severity | File | Category |
|-------|----------|------|----------|
| Handler not cleaned in onDestroy | MEDIUM | NavigationListenerService.kt | Lifecycle Leak |
| ContentObserver Handler leak | MEDIUM | ContactsContentObserver.kt | Lifecycle Leak |
| ContentObserver Handler leak | MEDIUM | SmsContentObserver.kt | Lifecycle Leak |
| SoundPool not released | MEDIUM | SoundManager.kt | Resource Leak |
| Repeated getSystemService | LOW | SocketForegroundService.kt | Performance |

---

## Correctly Handled Patterns

The following patterns are implemented correctly:

- Cursor management with `.use {}` blocks
- Intent extra null safety in MainActivity
- ContentObserver unregistration
- ExoPlayer pool management with explicit release
- Foreground service notification setup
- BroadcastReceiver pendingResult finishing
