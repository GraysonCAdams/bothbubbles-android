package com.bothbubbles.ui.components.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.bothbubbles.util.error.AppError

@Composable
fun ErrorSnackbar(
    error: AppError?,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    if (error != null) {
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(error) {
            val result = snackbarHostState.showSnackbar(
                message = error.userMessage,
                actionLabel = if (error.isRetryable && onRetry != null) "Retry" else null,
                duration = SnackbarDuration.Long
            )

            when (result) {
                SnackbarResult.ActionPerformed -> onRetry?.invoke()
                SnackbarResult.Dismissed -> onDismiss()
            }
        }

        SnackbarHost(hostState = snackbarHostState)
    }
}
