# Utilities

## Purpose

Shared utility classes used across the application. Contains helpers for common operations like avatar generation, phone number formatting, network configuration, and error handling.

## Files

| File | Description |
|------|-------------|
| `AvatarGenerator.kt` | Generate avatar images with initials |
| `BlurhashDecoder.kt` | Decode blurhash placeholders for images |
| `EmojiUtils.kt` | Emoji detection and manipulation |
| `GifProcessor.kt` | GIF processing utilities |
| `MessageDeduplicator.kt` | Deduplicate messages from multiple sources |
| `NetworkConfig.kt` | Network configuration helpers |
| `PerformanceProfiler.kt` | Performance profiling utilities |
| `PhoneNumberFormatter.kt` | Phone number formatting and normalization |
| `RetryHelper.kt` | Retry logic with exponential backoff |

## Architecture

```
util/
├── error/           - Error handling framework
├── parsing/         - Parsing utilities (dates, URLs, phones)
└── text/            - Text processing utilities
```

## Required Patterns

### Retry Helper

```kotlin
suspend fun <T> withRetry(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 1000,
    maxDelayMs: Long = 10000,
    factor: Double = 2.0,
    block: suspend () -> T
): T {
    var currentDelay = initialDelayMs
    repeat(maxAttempts - 1) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
        }
    }
    return block() // Last attempt throws exception
}
```

### Phone Number Formatter

```kotlin
object PhoneNumberFormatter {
    private lateinit var phoneUtil: PhoneNumberUtil

    fun init(context: Context) {
        phoneUtil = PhoneNumberUtil.createInstance(context)
    }

    fun normalize(number: String, defaultRegion: String = "US"): String? {
        return try {
            val parsed = phoneUtil.parse(number, defaultRegion)
            phoneUtil.format(parsed, PhoneNumberFormat.E164)
        } catch (e: Exception) {
            null
        }
    }

    fun format(number: String, defaultRegion: String = "US"): String {
        return try {
            val parsed = phoneUtil.parse(number, defaultRegion)
            phoneUtil.format(parsed, PhoneNumberFormat.NATIONAL)
        } catch (e: Exception) {
            number
        }
    }
}
```

### Performance Profiler

```kotlin
object PerformanceProfiler {
    private val traces = mutableMapOf<String, Long>()

    fun start(name: String): String {
        val id = "$name-${System.nanoTime()}"
        traces[id] = System.currentTimeMillis()
        return id
    }

    fun end(id: String) {
        val startTime = traces.remove(id) ?: return
        val duration = System.currentTimeMillis() - startTime
        Log.d("PerfProfiler", "$id: ${duration}ms")
    }
}
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `error/` | Error handling framework (AppError, safeCall, Result extensions) |
| `parsing/` | Parsing utilities (dates, URLs, phone numbers) |
| `text/` | Text processing utilities |

## Best Practices

1. Keep utilities stateless where possible
2. Use object for singletons
3. Document public APIs
4. Handle null/invalid input gracefully
5. Avoid Android dependencies in pure utilities
