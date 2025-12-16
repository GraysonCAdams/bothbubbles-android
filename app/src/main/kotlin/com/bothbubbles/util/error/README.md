# Error Handling Framework

## Purpose

Consistent error handling using sealed classes and Result extensions.

## Files

| File | Description |
|------|-------------|
| `AppError.kt` | Base sealed classes for error types |
| `AttachmentErrorState.kt` | Attachment-specific error states |
| `ErrorMapper.kt` | Map exceptions to AppError |
| `ResultExtensions.kt` | Extensions for Result handling |

## Architecture

```
AppError (sealed base)
├── NetworkError (sealed)
│   ├── NoConnection
│   ├── Timeout
│   ├── ServerError(code: Int)
│   ├── Unauthorized
│   └── Unknown(message: String)
├── DatabaseError (sealed)
│   ├── QueryFailed
│   ├── InsertFailed
│   └── MigrationFailed
├── MessageError (sealed)
│   ├── SendFailed
│   ├── DeliveryFailed
│   └── AttachmentTooLarge
├── SmsError (sealed)
│   ├── NoDefaultApp
│   ├── PermissionDenied
│   └── CarrierBlocked
└── ValidationError (sealed)
    ├── InvalidInput(field: String, reason: String)
    └── MissingRequired(field: String)
```

## Required Patterns

### Error Definition

```kotlin
// These are sibling sealed classes, NOT nested
sealed class AppError : Exception()

sealed class NetworkError : AppError() {
    object NoConnection : NetworkError()
    object Timeout : NetworkError()
    data class ServerError(val code: Int) : NetworkError()
    object Unauthorized : NetworkError()
    data class Unknown(override val message: String) : NetworkError()
}

sealed class ValidationError : AppError() {
    data class InvalidInput(val field: String, val reason: String) : ValidationError()
    data class MissingRequired(val field: String) : ValidationError()
}
```

### Safe Call Wrapper

```kotlin
suspend fun <T> safeCall(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: IOException) {
        Result.failure(NetworkError.NoConnection)
    } catch (e: SocketTimeoutException) {
        Result.failure(NetworkError.Timeout)
    } catch (e: SQLiteException) {
        Result.failure(DatabaseError.QueryFailed)
    } catch (e: Exception) {
        Result.failure(NetworkError.Unknown(e.message ?: "Unknown error"))
    }
}
```

### Result Extensions

```kotlin
fun <T> Result<T>.handle(
    onSuccess: (T) -> Unit,
    onFailure: (AppError) -> Unit
) {
    fold(
        onSuccess = onSuccess,
        onFailure = { error ->
            when (error) {
                is AppError -> onFailure(error)
                else -> onFailure(NetworkError.Unknown(error.message ?: "Unknown"))
            }
        }
    )
}

fun <T> Result<T>.mapError(transform: (Throwable) -> AppError): Result<T> {
    return fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(transform(it)) }
    )
}
```

### Usage

```kotlin
// CORRECT - Import directly
import com.bothbubbles.util.error.ValidationError

val error = ValidationError.InvalidInput("email", "Invalid format")

// WRONG - Don't use nested path
// val error = AppError.ValidationError.InvalidInput(...)  // This doesn't exist!
```

## Best Practices

1. Use sealed classes for exhaustive when expressions
2. Import error types directly (not nested)
3. Wrap all fallible operations with safeCall
4. Map exceptions to appropriate error types
5. Provide meaningful error messages
