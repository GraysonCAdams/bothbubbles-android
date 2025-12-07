package com.bothbubbles.services.export

import kotlinx.serialization.Serializable

/**
 * Root container for SMS/MMS backup file.
 * Compatible with common backup formats.
 */
@Serializable
data class SmsBackup(
    val exportDate: Long,
    val deviceModel: String,
    val appVersion: String,
    val backupVersion: Int = 1,
    val smsCount: Int,
    val mmsCount: Int,
    val messages: List<SmsMessageBackup>,
    val mmsMessages: List<MmsMessageBackup>
)

/**
 * Represents a single SMS message in the backup.
 */
@Serializable
data class SmsMessageBackup(
    val address: String,
    val body: String?,
    val date: Long,
    val dateSent: Long,
    val type: Int,  // 1=inbox, 2=sent, 3=draft, 4=outbox, 5=failed, 6=queued
    val read: Boolean,
    val status: Int,  // -1=none, 0=complete, 32=pending, 64=failed
    val threadId: Long? = null,
    val subscriptionId: Int = -1
) {
    companion object {
        const val TYPE_INBOX = 1
        const val TYPE_SENT = 2
        const val TYPE_DRAFT = 3
        const val TYPE_OUTBOX = 4
        const val TYPE_FAILED = 5
        const val TYPE_QUEUED = 6
    }
}

/**
 * Represents a single MMS message in the backup.
 */
@Serializable
data class MmsMessageBackup(
    val addresses: List<MmsAddressBackup>,
    val date: Long,
    val dateSent: Long,
    val messageBox: Int,  // 1=inbox, 2=sent
    val read: Boolean,
    val subject: String? = null,
    val textParts: List<String>,
    val attachments: List<MmsAttachmentBackup>,
    val threadId: Long? = null
) {
    companion object {
        const val MESSAGE_BOX_INBOX = 1
        const val MESSAGE_BOX_SENT = 2
    }
}

/**
 * Represents an MMS address (recipient or sender).
 */
@Serializable
data class MmsAddressBackup(
    val address: String,
    val type: Int  // 137=from, 151=to, 130=cc, 129=bcc
) {
    companion object {
        const val TYPE_FROM = 137
        const val TYPE_TO = 151
        const val TYPE_CC = 130
        const val TYPE_BCC = 129
    }
}

/**
 * Represents an MMS attachment in the backup.
 * For now, we store Base64 encoded data for portability.
 */
@Serializable
data class MmsAttachmentBackup(
    val contentType: String,
    val fileName: String?,
    val data: String  // Base64 encoded
)

/**
 * Progress tracking for backup operations.
 */
sealed class SmsBackupProgress {
    data object Idle : SmsBackupProgress()

    data class Exporting(
        val currentMessage: Int,
        val totalMessages: Int,
        val stage: String
    ) : SmsBackupProgress() {
        val progressFraction: Float
            get() = if (totalMessages > 0) currentMessage.toFloat() / totalMessages else 0f
    }

    data class Saving(val fileName: String) : SmsBackupProgress()

    data class Complete(
        val filePath: String,
        val fileName: String,
        val smsCount: Int,
        val mmsCount: Int
    ) : SmsBackupProgress()

    data class Error(val message: String) : SmsBackupProgress()

    data object Cancelled : SmsBackupProgress()
}

/**
 * Progress tracking for restore operations.
 */
sealed class SmsRestoreProgress {
    data object Idle : SmsRestoreProgress()

    data class Reading(val fileName: String) : SmsRestoreProgress()

    data class Restoring(
        val currentMessage: Int,
        val totalMessages: Int,
        val duplicatesSkipped: Int
    ) : SmsRestoreProgress() {
        val progressFraction: Float
            get() = if (totalMessages > 0) currentMessage.toFloat() / totalMessages else 0f
    }

    data class Complete(
        val smsRestored: Int,
        val mmsRestored: Int,
        val duplicatesSkipped: Int
    ) : SmsRestoreProgress()

    data class Error(val message: String) : SmsRestoreProgress()

    data object Cancelled : SmsRestoreProgress()
}
