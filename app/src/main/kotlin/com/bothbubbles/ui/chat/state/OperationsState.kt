package com.bothbubbles.ui.chat.state

import androidx.compose.runtime.Stable
import com.bothbubbles.util.error.AppError

/**
 * State owned by ChatOperationsDelegate.
 * Contains all chat operations UI state (archive, star, delete, spam, block).
 */
@Stable
data class OperationsState(
    val isArchived: Boolean = false,
    val isStarred: Boolean = false,
    val chatDeleted: Boolean = false,
    val showSubjectField: Boolean = false,
    val isSpam: Boolean = false,
    val isReportedToCarrier: Boolean = false,
    val operationError: AppError? = null
)
