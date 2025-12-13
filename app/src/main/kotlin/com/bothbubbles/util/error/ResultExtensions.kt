package com.bothbubbles.util.error

/**
 * Extensions for working with Result and AppError types.
 */

/**
 * Maps a Result to include structured error information.
 */
inline fun <T> Result<T>.mapToAppError(): Result<T> {
    return this.recoverCatching { e ->
        throw when (e) {
            is AppError -> e
            else -> ErrorMapper.mapException(e)
        }
    }
}

/**
 * Executes a suspend block and returns Result with mapped errors.
 */
suspend inline fun <T> safeCall(crossinline block: suspend () -> T): Result<T> {
    return runCatching { block() }.mapToAppError()
}

/**
 * Gets the AppError from a failed Result, or null if successful.
 */
fun <T> Result<T>.appError(): AppError? {
    return exceptionOrNull()?.let {
        when (it) {
            is AppError -> it
            else -> ErrorMapper.mapException(it)
        }
    }
}

/**
 * Handles a Result by invoking success or error callbacks.
 */
inline fun <T> Result<T>.handle(
    onSuccess: (T) -> Unit,
    onError: (AppError) -> Unit
) {
    fold(
        onSuccess = onSuccess,
        onFailure = { e ->
            onError(
                when (e) {
                    is AppError -> e
                    else -> ErrorMapper.mapException(e)
                }
            )
        }
    )
}
