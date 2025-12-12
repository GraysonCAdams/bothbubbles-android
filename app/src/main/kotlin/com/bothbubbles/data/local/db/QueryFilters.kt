package com.bothbubbles.data.local.db

/**
 * Centralized SQL filter constants for Room @Query annotations.
 *
 * These constants ensure consistency across all DAO queries and reduce
 * the risk of forgetting to add filters (like soft-delete) to new queries.
 *
 * Usage in Room @Query:
 * ```kotlin
 * @Query("SELECT * FROM messages WHERE ${QueryFilters.ACTIVE_MESSAGE}")
 * ```
 */
object QueryFilters {

    // ===== Soft-Delete Filters =====

    /**
     * Filter for non-deleted records (soft-delete pattern).
     * Use this in WHERE clauses to exclude soft-deleted items.
     */
    const val NOT_DELETED = "date_deleted IS NULL"

    /**
     * Filter for deleted records only.
     */
    const val IS_DELETED = "date_deleted IS NOT NULL"

    // ===== Message Filters =====

    /**
     * Filter for active (non-deleted) messages.
     * Same as NOT_DELETED but semantically clearer for message queries.
     */
    const val ACTIVE_MESSAGE = NOT_DELETED

    /**
     * Filter for non-reaction messages.
     * Use with is_reaction column for efficient BitSet pagination.
     */
    const val NOT_REACTION = "is_reaction = 0"

    /**
     * Filter for reaction messages only.
     */
    const val IS_REACTION = "is_reaction = 1"

    /**
     * Combined filter for active non-reaction messages.
     * Used in BitSet pagination queries.
     */
    const val ACTIVE_NON_REACTION_MESSAGE = "$NOT_DELETED AND $NOT_REACTION"

    /**
     * Filter for non-draft SMS messages.
     */
    const val NOT_DRAFT_SMS = "(sms_status IS NULL OR sms_status != 'draft')"

    // ===== Chat Filters =====

    /**
     * Filter for active (non-deleted) chats.
     */
    const val ACTIVE_CHAT = NOT_DELETED

    /**
     * Filter for non-archived chats.
     */
    const val NOT_ARCHIVED = "is_archived = 0"

    /**
     * Filter for archived chats only.
     */
    const val IS_ARCHIVED = "is_archived = 1"

    /**
     * Combined filter for active non-archived chats.
     * Used in main conversation list queries.
     */
    const val VISIBLE_CHAT = "$NOT_DELETED AND $NOT_ARCHIVED"

    /**
     * Filter for pinned chats.
     */
    const val IS_PINNED = "is_pinned = 1"

    /**
     * Filter for non-pinned chats.
     */
    const val NOT_PINNED = "is_pinned = 0"

    /**
     * Filter for group chats.
     */
    const val IS_GROUP = "is_group = 1"

    /**
     * Filter for non-group (1:1) chats.
     */
    const val NOT_GROUP = "is_group = 0"

    // ===== Spam Filters =====

    /**
     * Filter for non-spam chats.
     */
    const val NOT_SPAM = "is_spam = 0"

    /**
     * Filter for spam chats only.
     */
    const val IS_SPAM = "is_spam = 1"

    // ===== Message Source Filters =====

    /**
     * Filter for iMessage messages only.
     */
    const val IS_IMESSAGE = "message_source = 'IMESSAGE'"

    /**
     * Filter for local SMS messages only.
     */
    const val IS_LOCAL_SMS = "message_source = 'LOCAL_SMS'"

    /**
     * Filter for local MMS messages only.
     */
    const val IS_LOCAL_MMS = "message_source = 'LOCAL_MMS'"

    /**
     * Filter for server-synced SMS messages only.
     */
    const val IS_SERVER_SMS = "message_source = 'SERVER_SMS'"

    /**
     * Filter for any SMS/MMS message (local or server).
     */
    const val IS_ANY_SMS = "(message_source IN ('LOCAL_SMS', 'LOCAL_MMS', 'SERVER_SMS'))"
}
