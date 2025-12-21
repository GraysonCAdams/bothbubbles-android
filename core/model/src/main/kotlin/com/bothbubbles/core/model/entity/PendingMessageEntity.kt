package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a message that is queued for sending.
 *
 * Messages are persisted here immediately when the user taps send, before any network
 * request is made. This ensures messages survive app kills and device reboots.
 *
 * WorkManager is used to guarantee delivery when network is available.
 */
@Entity(
    tableName = "pending_messages",
    indices = [
        Index(value = ["local_id"], unique = true),
        Index(value = ["chat_guid"]),
        Index(value = ["sync_status"]),
        Index(value = ["created_at"])
    ]
)
data class PendingMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Local UUID for tracking this message through the send process.
     * Used for UI updates and deduplication.
     */
    @ColumnInfo(name = "local_id")
    val localId: String,

    @ColumnInfo(name = "chat_guid")
    val chatGuid: String,

    @ColumnInfo(name = "text")
    val text: String?,

    @ColumnInfo(name = "subject")
    val subject: String? = null,

    @ColumnInfo(name = "reply_to_guid")
    val replyToGuid: String? = null,

    @ColumnInfo(name = "effect_id")
    val effectId: String? = null,

    /**
     * Delivery mode: AUTO, IMESSAGE, LOCAL_SMS, LOCAL_MMS
     */
    @ColumnInfo(name = "delivery_mode")
    val deliveryMode: String = "AUTO",

    /**
     * Current sync status: PENDING, SENDING, SENT, FAILED
     */
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = PendingSyncStatus.PENDING.name,

    /**
     * Server-assigned GUID after successful send
     */
    @ColumnInfo(name = "server_guid")
    val serverGuid: String? = null,

    /**
     * Error message if send failed
     */
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    /**
     * Number of send attempts
     */
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    /**
     * WorkManager request ID for cancellation
     */
    @ColumnInfo(name = "work_request_id")
    val workRequestId: String? = null,

    /**
     * When the message was queued
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Last send attempt timestamp
     */
    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null,

    /**
     * Attributed body JSON for mentions.
     * Only set for messages with mentions.
     * Format: {"string": "...", "runs": [...]}
     */
    @ColumnInfo(name = "attributed_body_json")
    val attributedBodyJson: String? = null,

    /**
     * Local ID of the message that must complete (SENT status) before this message can be sent.
     * Used to ensure messages in the same chat are delivered in order.
     *
     * When a message is queued while another message in the same chat is PENDING or SENDING,
     * this field is set to that message's localId. The MessageSendWorker will wait for the
     * dependency to reach SENT status before attempting to send this message.
     *
     * If the dependency fails (reaches FAILED status), this message is also marked as FAILED
     * via cascade failure logic.
     */
    @ColumnInfo(name = "depends_on_local_id")
    val dependsOnLocalId: String? = null,

    /**
     * Groups related messages that were composed together (e.g., text + attachments).
     *
     * When a user sends a message with both text and attachments, they are split into
     * separate messages (like native iMessage). This ID links them for:
     * - Visual grouping in the UI (tighter spacing, connected bubbles)
     * - Understanding which messages were sent as a unit
     *
     * Format: "batch-{UUID}" or null for single messages.
     */
    @ColumnInfo(name = "split_batch_id")
    val splitBatchId: String? = null
)

/**
 * Sync status for pending messages.
 */
enum class PendingSyncStatus {
    /** Queued, waiting for network */
    PENDING,
    /** Currently being sent */
    SENDING,
    /** Successfully sent, serverGuid populated */
    SENT,
    /** Failed after max retries */
    FAILED
}
