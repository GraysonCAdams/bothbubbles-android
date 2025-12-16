package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks addresses that have been verified for counterpart chat existence.
 *
 * When a unified group has only one chat member (e.g., SMS only), we check
 * the server for a counterpart (e.g., iMessage). If the server returns 404,
 * we record that here to avoid repeated checks for Android-only contacts.
 *
 * This table survives unified group rebuilds (unlike storing on the group itself),
 * ensuring we don't spam the server with redundant checks after resyncs.
 */
@Entity(
    tableName = "verified_counterpart_checks",
    indices = [Index(value = ["normalized_address"], unique = true)]
)
data class VerifiedCounterpartCheckEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * The normalized phone number or email address that was checked.
     * This matches the identifier used in unified groups.
     */
    @ColumnInfo(name = "normalized_address")
    val normalizedAddress: String,

    /**
     * Whether a counterpart chat exists on the server.
     * - true: Server has counterpart (we fetched and linked it)
     * - false: Server returned 404 (no counterpart, e.g., Android user)
     */
    @ColumnInfo(name = "has_counterpart")
    val hasCounterpart: Boolean,

    /**
     * Timestamp when this check was performed.
     * Can be used to invalidate old checks (e.g., after 30 days)
     * in case a contact switched to/from iPhone.
     */
    @ColumnInfo(name = "verified_at")
    val verifiedAt: Long = System.currentTimeMillis()
)
