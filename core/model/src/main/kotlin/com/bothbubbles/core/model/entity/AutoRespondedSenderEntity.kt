package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks sender addresses that have received an auto-response.
 *
 * Keyed by sender address (not chat GUID) so that:
 * - Record persists even if user deletes chat history
 * - First message from sender triggers auto-response
 * - Subsequent messages from same sender (even in new chat) do NOT trigger
 */
@Entity(
    tableName = "auto_responded_senders",
    indices = [Index(value = ["sender_address"], unique = true)]
)
data class AutoRespondedSenderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "sender_address")
    val senderAddress: String,

    @ColumnInfo(name = "responded_at")
    val respondedAt: Long = System.currentTimeMillis()
)
