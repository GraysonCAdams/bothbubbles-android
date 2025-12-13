package com.bothbubbles.data.model

import android.net.Uri

/**
 * Data class representing an attachment to be sent, including optional caption.
 */
data class PendingAttachmentInput(
    val uri: Uri,
    val caption: String? = null,
    val mimeType: String? = null,
    val name: String? = null,
    val size: Long? = null
)
