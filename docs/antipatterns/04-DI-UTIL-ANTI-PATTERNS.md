# DI & Utilities Anti-Patterns

**Layers:**
- `app/src/main/kotlin/com/bothbubbles/di/`
- `app/src/main/kotlin/com/bothbubbles/util/`

---

## Major Issues

### 1. AvatarGenerator God Object

**Location:** `util/AvatarGenerator.kt` (758 lines!)

**Issue:**
```kotlin
object AvatarGenerator {
    // 15+ responsibilities in one object:
    fun getAvatarColorInt(name: String): Int
    fun getInitials(name: String): String
    fun isPhoneNumber(name: String): Boolean
    fun isShortCodeOrAlphanumericSender(name: String): Boolean
    fun generateBitmap(...)
    fun generateIconCompat(...)
    fun loadContactPhotoBitmap(...)
    fun loadContactPhotoAlternative(...)
    fun centerCropToSquare(...)
    fun createCircularBitmap(...)
    fun generateGroupCollageBitmap(...)
    fun generateGroupCollageBitmapWithPhotos(...)
    // ... 10+ more methods
}
```

**Why Problematic:**
- 758 lines is FAR beyond maintainable
- Single Responsibility Principle violated
- Handles: colors, names, phone detection, bitmaps, photos, cropping, collages
- Hard to test individual concerns
- Changes to one feature risk breaking others

**Fix:**
Split into focused utilities:
```kotlin
object AvatarColorizer { /* color logic */ }
object AvatarNameAnalyzer { /* phone/shortcode detection */ }
object AvatarBitmapGenerator { /* bitmap creation */ }
object ContactPhotoLoader { /* photo loading */ }
object GroupAvatarGenerator { /* group collage logic */ }
```

---

## Medium Severity Issues

### 2. ~~Missing @Singleton on Dispatcher Providers~~ **FIXED 2024-12-20**

**Location:** `di/CoroutinesModule.kt` (Lines 42-51)

**Issue:**
```kotlin
@Provides
@IoDispatcher
fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO  // No @Singleton!

@Provides
@MainDispatcher
fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main  // No @Singleton!

@Provides
@DefaultDispatcher
fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default  // No @Singleton!
```

**Why Problematic:**
- Each injection creates a new wrapper reference
- Inconsistent with `@ApplicationScope` which IS `@Singleton`
- Minor performance overhead
- Makes intent unclear

**Fix Applied:**
```kotlin
@Provides
@Singleton
@IoDispatcher
fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

@Provides
@Singleton
@MainDispatcher
fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

@Provides
@Singleton
@DefaultDispatcher
fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
```

**Status:** FIXED on 2024-12-20 - All dispatcher providers now properly scoped as @Singleton

---

### 3. Mutable Global State in PerformanceProfiler

**Location:** `util/PerformanceProfiler.kt` (Lines 13-24)

**Issue:**
```kotlin
object PerformanceProfiler {
    var enabled = true  // MUTABLE STATE!

    private val activeTimers = ConcurrentHashMap<String, Long>()
    private val stats = ConcurrentHashMap<String, OperationStats>()
    private val _logs = MutableStateFlow<List<PerfLog>>(emptyList())
}
```

**Why Problematic:**
- Utilities should be stateless
- Global mutable state is hard to test
- Can't create isolated test instances
- Should be a service, not utility

**Fix:**
```kotlin
@Singleton
class PerformanceProfiler @Inject constructor() {
    var enabled = true
    private val activeTimers = ConcurrentHashMap<String, Long>()
    // ...
}
```

---

### 4. Service in Wrong Package (MessageDeduplicator)

**Location:** `util/MessageDeduplicator.kt` (Lines 26-141)

**Issue:**
```kotlin
@Singleton
class MessageDeduplicator @Inject constructor(
    private val seenMessageDao: SeenMessageDao,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    // Has @Inject, is @Singleton, maintains state
    // This is a SERVICE, not a utility!
}
```

**Why Problematic:**
- Wrong package location
- Utilities shouldn't have `@Inject` or be singletons
- Misleading to developers expecting stateless utilities
- Violates package conventions

**Fix:**
Move to `services/messaging/MessageDeduplicator.kt`

---

### 5. Mutable State in HapticUtils

**Location:** `util/HapticUtils.kt` (Lines 52-61)

**Issue:**
```kotlin
object HapticUtils {
    private val _enabled = AtomicBoolean(true)

    var enabled: Boolean
        get() = _enabled.get()
        set(value) = _enabled.set(value)
}
```

**Why Problematic:**
- Same issue as PerformanceProfiler
- Global mutable state
- Hard to test with different enabled states

**Fix:**
Either make it a service or pass enabled as parameter:
```kotlin
fun onTap(haptic: HapticFeedback, enabled: Boolean = true) {
    if (!enabled) return
    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
}
```

---

### 6. AudioHapticSync Should Be Service

**Location:** `util/AudioHapticSync.kt` (Lines 46-284)

**Issue:**
```kotlin
class AudioHapticSync(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    // 250+ lines of stateful logic
}
```

**Why Problematic:**
- Constructor requires Context - not a utility pattern
- Maintains mutable state (mediaPlayer)
- Should be injectable singleton service

**Fix:**
```kotlin
// Move to services/
@Singleton
class AudioHapticSync @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // ...
}
```

---

## Low Severity Issues

### 7. PhoneNumberFormatter Requires init()

**Location:** `util/PhoneNumberFormatter.kt` (Lines 17-35)

**Issue:**
```kotlin
object PhoneNumberFormatter {
    @Volatile
    private var phoneUtil: PhoneNumberUtil? = null

    fun init(context: Context) {
        if (phoneUtil != null) return
        synchronized(initLock) {
            phoneUtil = PhoneNumberUtil.createInstance(context)
        }
    }
}
```

**Why Problematic:**
- Utilities should be ready immediately
- Developers might forget to call `init()`
- Creates runtime coupling to Context
- Error-prone API

**Fix:**
Make it a properly injected service with automatic initialization.

---

### 8. BlurhashDecoder Race Condition

**Location:** `util/BlurhashDecoder.kt` (Line 24)

**Issue:**
```kotlin
object BlurhashDecoder {
    private val cache = LruCache<String, Bitmap>(50)

    fun decode(...): Bitmap? {
        val cacheKey = "$blurhash:${width}x$height"
        cache.get(cacheKey)?.let { return it }

        // Two threads could both miss cache and decode same blurhash
        val bitmap = BlurHash.decode(blurhash, width, height)
        bitmap?.let { cache.put(cacheKey, it) }
        return bitmap
    }
}
```

**Why Problematic:**
- `get()` and `put()` aren't atomic together
- Two threads could decode same blurhash simultaneously
- Minor issue - just wastes CPU, doesn't break

**Fix:**
Use double-checked locking or synchronized block.

---

## Summary Table

| Issue | Severity | File | Lines | Category |
|-------|----------|------|-------|----------|
| God Object (758 lines) | MAJOR | AvatarGenerator.kt | All | Class Design |
| ~~Missing @Singleton~~ | ~~MEDIUM~~ | ~~CoroutinesModule.kt~~ | ~~42-51~~ | ~~Scope~~ FIXED |
| Mutable Global State | MEDIUM | PerformanceProfiler.kt | 13-24 | State |
| Wrong Package | MEDIUM | MessageDeduplicator.kt | 26-141 | Organization |
| Mutable Global State | MEDIUM | HapticUtils.kt | 52-61 | State |
| Should Be Service | MEDIUM | AudioHapticSync.kt | 46-284 | Organization |
| Requires init() | LOW | PhoneNumberFormatter.kt | 17-35 | API Design |
| Race Condition | LOW | BlurhashDecoder.kt | 24 | Thread Safety |

---

## Positive Findings

- `ServiceModule.kt` has excellent interface bindings for testability
- Proper use of Hilt annotations overall
- `CoroutinesModule` properly provides `@ApplicationScope` as singleton
- `AuthCredentialsProviderImpl` correctly uses suspend functions
- Good qualifier annotations (`@IoDispatcher`, `@MainDispatcher`, etc.)
