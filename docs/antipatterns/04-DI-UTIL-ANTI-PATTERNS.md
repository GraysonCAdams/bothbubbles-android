# DI & Utilities Anti-Patterns

**Layers:**
- `app/src/main/kotlin/com/bothbubbles/di/`
- `app/src/main/kotlin/com/bothbubbles/util/`

---

## Major Issues

### 1. God Module with Repetitive DAO Providers

**Location:** `di/DatabaseModule.kt` (Lines 69-182)

**Issue:**
```kotlin
@Provides
@Singleton
fun provideChatDao(database: BothBubblesDatabase): ChatDao {
    return database.chatDao()
}

@Provides
@Singleton
fun provideMessageDao(database: BothBubblesDatabase): MessageDao {
    return database.messageDao()
}

// ... repeated 16 more times (18 total!)
```

**Why Problematic:**
- 18 nearly identical boilerplate methods
- Violates DRY principle
- Room DAOs are already singletons via database
- `@Singleton` is redundant (database is singleton)
- Makes module hard to maintain

**Fix Options:**

Option 1 - Remove individual providers, inject database directly:
```kotlin
@Singleton
class MessageRepository @Inject constructor(
    private val database: BothBubblesDatabase
) {
    private val messageDao = database.messageDao()
}
```

Option 2 - Factory class:
```kotlin
@Singleton
class DaoFactory @Inject constructor(database: BothBubblesDatabase) {
    val chatDao = database.chatDao()
    val messageDao = database.messageDao()
    // ...
}
```

---

### 2. AvatarGenerator God Object

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

### 3. Missing @Singleton on Dispatcher Providers

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

**Fix:**
```kotlin
@Provides
@Singleton
@IoDispatcher
fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
```

---

### 4. Mutable Global State in PerformanceProfiler

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

### 5. Service in Wrong Package (MessageDeduplicator)

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

### 6. Mutable State in HapticUtils

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

### 7. AudioHapticSync Should Be Service

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

### 8. PhoneNumberFormatter Requires init()

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

### 9. BlurhashDecoder Race Condition

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

### 10. Redundant @Singleton on DAO Providers

**Location:** `di/DatabaseModule.kt` (All DAO providers)

**Issue:**
All 18 DAO providers have `@Singleton`, but Room DAOs are already singletons when accessed through a singleton database.

**Why Problematic:**
- Redundant annotations add noise
- Creates confusion about what's actually singleton
- Not harmful, but not necessary

**Fix:**
Either remove `@Singleton` or add comment explaining why it's there.

---

## Summary Table

| Issue | Severity | File | Lines | Category |
|-------|----------|------|-------|----------|
| God Module (18 DAOs) | MAJOR | DatabaseModule.kt | 69-182 | Organization |
| God Object (758 lines) | MAJOR | AvatarGenerator.kt | All | Class Design |
| Missing @Singleton | MEDIUM | CoroutinesModule.kt | 42-51 | Scope |
| Mutable Global State | MEDIUM | PerformanceProfiler.kt | 13-24 | State |
| Wrong Package | MEDIUM | MessageDeduplicator.kt | 26-141 | Organization |
| Mutable Global State | MEDIUM | HapticUtils.kt | 52-61 | State |
| Should Be Service | MEDIUM | AudioHapticSync.kt | 46-284 | Organization |
| Requires init() | LOW | PhoneNumberFormatter.kt | 17-35 | API Design |
| Race Condition | LOW | BlurhashDecoder.kt | 24 | Thread Safety |
| Redundant @Singleton | LOW | DatabaseModule.kt | Various | Style |

---

## Positive Findings

- `ServiceModule.kt` has excellent interface bindings for testability
- Proper use of Hilt annotations overall
- `CoroutinesModule` properly provides `@ApplicationScope` as singleton
- `AuthCredentialsProviderImpl` correctly uses suspend functions
- Good qualifier annotations (`@IoDispatcher`, `@MainDispatcher`, etc.)
