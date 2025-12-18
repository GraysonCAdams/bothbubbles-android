# Moshi ApiResponse<Unit> Converter Issue

**Status**: Known issue, low priority (doesn't affect functionality)
**Date Identified**: 2024-12-17

## Symptom

```
W ChatRepository: java.lang.IllegalArgumentException: Unable to create converter for com.bothbubbles.core.network.api.dto.ApiResponse<kotlin.Unit>
```

This warning appears when calling API endpoints that return `Response<ApiResponse<Unit>>`:
- `markChatRead`
- `markChatUnread`
- `deleteChat`
- `unsendMessage`
- `deleteMessage`
- `leaveFaceTime`
- `ping`

## Root Cause

Moshi cannot deserialize Kotlin's `Unit` type because:
1. `Unit` is a singleton object, not a data class
2. Moshi requires a no-arg constructor or a `@JsonClass` adapter
3. There's no built-in adapter for `Unit`

## Impact

**Low** - The API calls still succeed because:
1. The local database is updated first (optimistic update)
2. The server receives and processes the request
3. Only the response parsing fails
4. The exception is caught and logged as a warning

## Potential Fixes

### Option 1: Change API return types (Recommended)

For endpoints that don't return meaningful data, change from:
```kotlin
suspend fun markChatRead(@Path("guid") guid: String): Response<ApiResponse<Unit>>
```

To:
```kotlin
suspend fun markChatRead(@Path("guid") guid: String): Response<Unit>
```

This tells Retrofit to not parse the response body at all.

### Option 2: Add UnitJsonAdapter to Moshi

```kotlin
object UnitJsonAdapter {
    @FromJson
    fun fromJson(reader: JsonReader): Unit {
        reader.skipValue()
        return Unit
    }

    @ToJson
    fun toJson(writer: JsonWriter, value: Unit) {
        writer.nullValue()
    }
}

// In NetworkModule.kt
Moshi.Builder()
    .add(UnitJsonAdapter)
    .build()
```

### Option 3: Use a placeholder type

Create an empty data class:
```kotlin
@JsonClass(generateAdapter = true)
data class EmptyResponse(val placeholder: String? = null)
```

And use `ApiResponse<EmptyResponse>` instead of `ApiResponse<Unit>`.

## Files Involved

- `core/network/src/main/kotlin/com/bothbubbles/core/network/api/BothBubblesApi.kt` - API interface definitions
- `core/network/src/main/kotlin/com/bothbubbles/core/network/di/NetworkModule.kt` - Moshi configuration
- `app/src/main/kotlin/com/bothbubbles/data/repository/ChatRepository.kt` - Where errors are caught

## Debugging

The warning is now caught specifically as `IllegalArgumentException` in ChatRepository with a descriptive message pointing to this issue.
