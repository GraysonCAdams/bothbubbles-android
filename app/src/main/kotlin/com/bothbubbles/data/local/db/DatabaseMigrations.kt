package com.bothbubbles.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import timber.log.Timber

/**
 * Contains all Room database migrations for BothBubblesDatabase.
 *
 * ## Migration Strategy
 *
 * When adding new columns or changing the schema:
 * 1. Increment the database version number in [BothBubblesDatabase]
 * 2. Create a new migration object (e.g., MIGRATION_34_35)
 * 3. Add the migration to [ALL_MIGRATIONS] array
 * 4. Test the migration with both fresh installs and upgrades
 *
 * ## Column Defaults
 *
 * When adding nullable columns: Use `ALTER TABLE ... ADD COLUMN ... DEFAULT NULL`
 * When adding non-null columns: Provide a sensible DEFAULT value
 *
 * ## Schema Export
 *
 * Schema JSON files are exported to `app/schemas/` for migration testing.
 * These should be committed to version control.
 */
object DatabaseMigrations {

    /**
     * Migration from version 1 to 2: Add is_starred column to chats table
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN is_starred INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_is_starred ON chats(is_starred)")
        }
    }

    /**
     * Migration from version 2 to 3: Add per-chat notification settings and metadata columns
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Get existing columns to avoid adding duplicates
            val existingColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(chats)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    existingColumns.add(cursor.getString(nameIndex))
                }
            }

            fun addColumnIfNotExists(sql: String, columnName: String) {
                if (columnName !in existingColumns) {
                    db.execSQL(sql)
                }
            }

            // Per-chat notification settings
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN notifications_enabled INTEGER NOT NULL DEFAULT 1", "notifications_enabled")
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN notification_priority TEXT NOT NULL DEFAULT 'default'", "notification_priority")
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN bubble_enabled INTEGER NOT NULL DEFAULT 0", "bubble_enabled")
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN pop_on_screen INTEGER NOT NULL DEFAULT 1", "pop_on_screen")
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN lock_screen_visibility TEXT NOT NULL DEFAULT 'all'", "lock_screen_visibility")
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN show_notification_dot INTEGER NOT NULL DEFAULT 1", "show_notification_dot")
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN vibration_enabled INTEGER NOT NULL DEFAULT 1", "vibration_enabled")
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN custom_notification_sound TEXT DEFAULT NULL", "custom_notification_sound")

            // Privacy/behavior settings
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN auto_send_read_receipts INTEGER DEFAULT NULL", "auto_send_read_receipts")
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN auto_send_typing_indicators INTEGER DEFAULT NULL", "auto_send_typing_indicators")
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN lock_chat_name INTEGER NOT NULL DEFAULT 0", "lock_chat_name")
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN lock_chat_icon INTEGER NOT NULL DEFAULT 0", "lock_chat_icon")

            // Draft/state
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN text_field_text TEXT DEFAULT NULL", "text_field_text")

            // Timestamps
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN latest_message_date INTEGER DEFAULT NULL", "latest_message_date")
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN date_created INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}", "date_created")
            addColumnIfNotExists("ALTER TABLE chats ADD COLUMN date_deleted INTEGER DEFAULT NULL", "date_deleted")

            // Index for latest_message_date (used for sorting)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_latest_message_date ON chats(latest_message_date)")
        }
    }

    /**
     * Migration from version 3 to 4: Add inferred_name column to handles table
     * for storing names detected from self-introduction messages (e.g., "Hey it's John")
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE handles ADD COLUMN inferred_name TEXT DEFAULT NULL")
        }
    }

    /**
     * Migration from version 4 to 5: Add link_previews table for caching URL metadata
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS link_previews (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    url TEXT NOT NULL,
                    url_hash TEXT NOT NULL,
                    domain TEXT NOT NULL,
                    title TEXT DEFAULT NULL,
                    description TEXT DEFAULT NULL,
                    image_url TEXT DEFAULT NULL,
                    favicon_url TEXT DEFAULT NULL,
                    site_name TEXT DEFAULT NULL,
                    content_type TEXT DEFAULT NULL,
                    video_url TEXT DEFAULT NULL,
                    video_duration INTEGER DEFAULT NULL,
                    fetch_status TEXT NOT NULL DEFAULT 'PENDING',
                    created_at INTEGER NOT NULL,
                    last_accessed INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_link_previews_url_hash ON link_previews(url_hash)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_link_previews_last_accessed ON link_previews(last_accessed)")
        }
    }

    /**
     * Migration from version 5 to 6: Add oEmbed-specific columns to link_previews table
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE link_previews ADD COLUMN embed_html TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE link_previews ADD COLUMN author_name TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE link_previews ADD COLUMN author_url TEXT DEFAULT NULL")
        }
    }

    /**
     * Migration from version 6 to 7: Add spam detection columns to chats and handles tables
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Spam columns for chats table
            db.execSQL("ALTER TABLE chats ADD COLUMN is_spam INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chats ADD COLUMN spam_score INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chats ADD COLUMN spam_reported_to_carrier INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_is_spam ON chats(is_spam)")

            // Spam columns for handles table
            db.execSQL("ALTER TABLE handles ADD COLUMN spam_report_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE handles ADD COLUMN is_whitelisted INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Migration from version 7 to 8: Remove duplicate MMS messages that match existing SMS messages.
     * Some phones record the same message as both SMS and MMS with different IDs, causing duplicates.
     * This migration finds MMS messages that have a matching SMS message (same chat, text, sender,
     * and timestamp within 10 seconds) and removes the MMS duplicates.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Delete MMS messages that have a matching SMS message with:
            // - Same chat_guid
            // - Same text content
            // - Same is_from_me value
            // - Timestamp within 10 seconds (10000ms)
            db.execSQL("""
                DELETE FROM messages
                WHERE guid LIKE 'mms-%'
                AND id IN (
                    SELECT mms.id
                    FROM messages mms
                    INNER JOIN messages sms ON
                        sms.guid LIKE 'sms-%'
                        AND sms.chat_guid = mms.chat_guid
                        AND sms.text = mms.text
                        AND sms.is_from_me = mms.is_from_me
                        AND ABS(sms.date_created - mms.date_created) <= 10000
                    WHERE mms.guid LIKE 'mms-%'
                )
            """.trimIndent())
        }
    }

    /**
     * Migration from version 8 to 9: Add quick_reply_templates table for user-saved reply templates
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS quick_reply_templates (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    text TEXT NOT NULL,
                    usage_count INTEGER NOT NULL DEFAULT 0,
                    last_used_at INTEGER DEFAULT NULL,
                    created_at INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()},
                    display_order INTEGER NOT NULL DEFAULT 0,
                    is_favorite INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_quick_reply_templates_usage_count ON quick_reply_templates(usage_count)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_quick_reply_templates_is_favorite ON quick_reply_templates(is_favorite)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_quick_reply_templates_display_order ON quick_reply_templates(display_order)")
        }
    }

    /**
     * Migration from version 9 to 10: Add message categorization columns to chats table
     * for ML-based transaction/delivery/promotion/reminder classification
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN category TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE chats ADD COLUMN category_confidence INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chats ADD COLUMN category_last_updated INTEGER DEFAULT NULL")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_category ON chats(category)")
        }
    }

    /**
     * Migration from version 10 to 11: Add snooze_until column to chats table
     * for temporarily suppressing notifications for a conversation.
     * Value is epoch timestamp when snooze expires, -1 for indefinite, or null if not snoozed.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN snooze_until INTEGER DEFAULT NULL")
        }
    }

    /**
     * Migration from version 11 to 12: Add scheduled_messages table
     * for client-side scheduled message sending via WorkManager.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS scheduled_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    chat_guid TEXT NOT NULL,
                    text TEXT,
                    attachment_uris TEXT,
                    scheduled_at INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    work_request_id TEXT,
                    error_message TEXT
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_messages_chat_guid ON scheduled_messages(chat_guid)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_messages_scheduled_at ON scheduled_messages(scheduled_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_messages_status ON scheduled_messages(status)")
        }
    }

    /**
     * Migration from version 12 to 13: Persist SMS fallback metadata on chats
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN is_sms_fallback INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chats ADD COLUMN fallback_reason TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE chats ADD COLUMN fallback_updated_at INTEGER DEFAULT NULL")
        }
    }

    /**
     * Migration from version 13 to 14: Add sms_error_message column to messages table
     * for storing user-friendly error messages when SMS/MMS sending fails.
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sms_error_message TEXT DEFAULT NULL")
        }
    }

    /**
     * Migration from version 14 to 15: Add unified_chat_groups and unified_chat_members tables
     * for linking related chats (iMessage + SMS) for the same contact into unified conversations.
     */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create unified_chat_groups table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS unified_chat_groups (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    identifier TEXT NOT NULL,
                    primary_chat_guid TEXT NOT NULL,
                    display_name TEXT DEFAULT NULL,
                    latest_message_date INTEGER DEFAULT NULL,
                    latest_message_text TEXT DEFAULT NULL,
                    unread_count INTEGER NOT NULL DEFAULT 0,
                    is_pinned INTEGER NOT NULL DEFAULT 0,
                    pin_index INTEGER DEFAULT NULL,
                    is_archived INTEGER NOT NULL DEFAULT 0,
                    is_starred INTEGER NOT NULL DEFAULT 0,
                    mute_type TEXT DEFAULT NULL,
                    snooze_until INTEGER DEFAULT NULL,
                    created_at INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}
                )
            """.trimIndent())

            // Create unique index on identifier
            db.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS index_unified_chat_groups_identifier
                ON unified_chat_groups(identifier)
            """.trimIndent())

            // Create index on latest_message_date for sorting
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS index_unified_chat_groups_latest_message_date
                ON unified_chat_groups(latest_message_date)
            """.trimIndent())

            // Create unified_chat_members junction table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS unified_chat_members (
                    group_id INTEGER NOT NULL,
                    chat_guid TEXT NOT NULL,
                    PRIMARY KEY (group_id, chat_guid),
                    FOREIGN KEY (group_id) REFERENCES unified_chat_groups(id) ON DELETE CASCADE
                )
            """.trimIndent())

            // Create index on group_id
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS index_unified_chat_members_group_id
                ON unified_chat_members(group_id)
            """.trimIndent())

            // Create unique index on chat_guid (each chat can only belong to one group)
            db.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS index_unified_chat_members_chat_guid
                ON unified_chat_members(chat_guid)
            """.trimIndent())
        }
    }

    /**
     * Migration from version 15 to 16: Clean up corrupted unified_chat_groups
     * where primaryChatGuid incorrectly points to an RCS/email chat for phone-based groups.
     * This was caused by a bug in phonesMatch() where empty strings matched all phones.
     */
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Due to bugs in unified group creation, wipe all unified groups and let them rebuild.
            // Issues fixed:
            // 1. phonesMatch() matched empty strings to all phones (RCS/email chats matched everything)
            // 2. Empty identifiers caused all non-phone chats to join the same group
            // 3. Email addresses were used as identifiers instead of being skipped
            db.execSQL("DELETE FROM unified_chat_members")
            db.execSQL("DELETE FROM unified_chat_groups")
        }
    }

    /**
     * Migration from version 16 to 17: Add sender_address column to messages table.
     * This stores the actual phone number/email of the sender for group chat messages,
     * enabling proper sender identification when handle IDs use internal identifiers.
     */
    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sender_address TEXT DEFAULT NULL")
        }
    }

    /**
     * Migration from version 17 to 18: Fix message_source for SMS messages.
     * Messages in SMS chats (chat_guid starting with 'sms;-;') were incorrectly
     * labeled as IMESSAGE. This updates them to SERVER_SMS.
     */
    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Fix messages in SMS chats that were incorrectly labeled as IMESSAGE
            db.execSQL("""
                UPDATE messages
                SET message_source = 'SERVER_SMS'
                WHERE chat_guid LIKE 'sms;-;%'
                AND message_source = 'IMESSAGE'
            """.trimIndent())
        }
    }

    /**
     * Migration from version 18 to 19: Add seen_messages table for persistent
     * message deduplication. This prevents duplicate notifications across app restarts
     * when the same message arrives via both FCM and Socket.IO.
     */
    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS seen_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    message_guid TEXT NOT NULL,
                    seen_at INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_seen_messages_message_guid ON seen_messages(message_guid)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_seen_messages_seen_at ON seen_messages(seen_at)")
        }
    }

    /**
     * Migration from version 19 to 20: Add index on thread_originator_guid for
     * efficient reply/thread lookups when displaying reply indicators in chat.
     */
    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_thread_originator_guid ON messages(thread_originator_guid)")
        }
    }

    /**
     * Migration from version 20 to 21: Add performance indexes.
     * - message_source index for filtering by message type (iMessage vs SMS)
     * - Composite (chat_guid, date_deleted) index for efficient soft-delete queries
     * - is_archived index for archived chat queries
     */
    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Index on message_source for queries filtering by message type
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_message_source ON messages(message_source)")
            // Composite index for efficient soft-delete filtering
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chat_guid_date_deleted ON messages(chat_guid, date_deleted)")
            // Index on is_archived for archived chat queries
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_is_archived ON chats(is_archived)")
        }
    }

    /**
     * Migration from version 21 to 22: Add cached message preview fields to unified_chat_groups.
     * These denormalized fields eliminate N+1 queries when displaying the conversation list.
     */
    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE unified_chat_groups ADD COLUMN latest_message_guid TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE unified_chat_groups ADD COLUMN latest_message_is_from_me INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE unified_chat_groups ADD COLUMN latest_message_has_attachments INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE unified_chat_groups ADD COLUMN latest_message_source TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE unified_chat_groups ADD COLUMN latest_message_date_delivered INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE unified_chat_groups ADD COLUMN latest_message_date_read INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE unified_chat_groups ADD COLUMN latest_message_error INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add thumbnail_path column for cached attachment thumbnails
            db.execSQL("ALTER TABLE attachments ADD COLUMN thumbnail_path TEXT DEFAULT NULL")
        }
    }

    /**
     * Migration from version 23 to 24: Add pending_messages and pending_attachments tables
     * for offline-first message sending with WorkManager.
     */
    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create pending_messages table for offline message queue
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS pending_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    local_id TEXT NOT NULL,
                    chat_guid TEXT NOT NULL,
                    text TEXT,
                    subject TEXT,
                    reply_to_guid TEXT,
                    effect_id TEXT,
                    delivery_mode TEXT NOT NULL DEFAULT 'AUTO',
                    sync_status TEXT NOT NULL DEFAULT 'PENDING',
                    server_guid TEXT,
                    error_message TEXT,
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    work_request_id TEXT,
                    created_at INTEGER NOT NULL,
                    last_attempt_at INTEGER
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_messages_local_id ON pending_messages(local_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_messages_chat_guid ON pending_messages(chat_guid)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_messages_sync_status ON pending_messages(sync_status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_messages_created_at ON pending_messages(created_at)")

            // Create pending_attachments table for attachment persistence
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS pending_attachments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    local_id TEXT NOT NULL,
                    pending_message_id INTEGER NOT NULL,
                    original_uri TEXT NOT NULL,
                    persisted_path TEXT NOT NULL,
                    file_name TEXT NOT NULL,
                    mime_type TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    upload_progress REAL NOT NULL DEFAULT 0,
                    order_index INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (pending_message_id) REFERENCES pending_messages(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_attachments_local_id ON pending_attachments(local_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_attachments_pending_message_id ON pending_attachments(pending_message_id)")
        }
    }

    /**
     * Migration from version 24 to 25: Add imessage_availability_cache table
     * for caching iMessage availability check results per contact.
     */
    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS imessage_availability_cache (
                    normalized_address TEXT PRIMARY KEY NOT NULL,
                    check_result TEXT NOT NULL,
                    checked_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    session_id TEXT NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_imessage_availability_cache_check_result ON imessage_availability_cache(check_result)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_imessage_availability_cache_expires_at ON imessage_availability_cache(expires_at)")
        }
    }

    /**
     * Migration from version 25 to 26: Add sync_ranges table for tracking
     * which message timestamp ranges have been synced from the server.
     * This enables efficient sparse pagination without redundant API calls.
     */
    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS sync_ranges (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    chat_guid TEXT NOT NULL,
                    start_timestamp INTEGER NOT NULL,
                    end_timestamp INTEGER NOT NULL,
                    synced_at INTEGER NOT NULL,
                    sync_source TEXT NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_ranges_chat_guid ON sync_ranges(chat_guid)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_ranges_chat_guid_timestamps ON sync_ranges(chat_guid, start_timestamp, end_timestamp)")
        }
    }

    /**
     * Migration from version 26 to 27: Add transfer state columns to attachments table.
     * Enables "snappy" attachment rendering where:
     * - Outbound attachments display immediately from local file while uploading
     * - Inbound attachments show blurhash placeholders while downloading
     */
    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add transfer_state column with default PENDING
            db.execSQL("ALTER TABLE attachments ADD COLUMN transfer_state TEXT NOT NULL DEFAULT 'PENDING'")

            // Add transfer_progress column
            db.execSQL("ALTER TABLE attachments ADD COLUMN transfer_progress REAL NOT NULL DEFAULT 0")

            // Mark existing attachments with local_path as DOWNLOADED (backwards compatibility)
            db.execSQL("UPDATE attachments SET transfer_state = 'DOWNLOADED' WHERE local_path IS NOT NULL")

            // Create index for efficient state queries (e.g., finding pending downloads)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_transfer_state ON attachments(transfer_state)")
        }
    }

    /**
     * Migration from version 27 to 28: Add auto_responded_senders table
     * for tracking which sender addresses have received an auto-response.
     * Keyed by sender address (not chat GUID) so it persists even if chat is deleted.
     */
    val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS auto_responded_senders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sender_address TEXT NOT NULL,
                    responded_at INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_auto_responded_senders_sender_address ON auto_responded_senders(sender_address)")
        }
    }

    /**
     * Migration from version 28 to 29: Add per-chat send mode preference columns.
     * Allows users to manually set preferred messaging service (iMessage vs SMS)
     * for individual chats, persisted across app restarts.
     */
    val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add preferred_send_mode column (nullable, values: "imessage", "sms", or null for automatic)
            db.execSQL("ALTER TABLE chats ADD COLUMN preferred_send_mode TEXT DEFAULT NULL")
            // Add send_mode_manually_set flag (non-null boolean, default false)
            db.execSQL("ALTER TABLE chats ADD COLUMN send_mode_manually_set INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Migration from version 29 to 30: Add is_reaction column to messages table.
     * This denormalized column enables efficient filtering of reactions without
     * complex pattern matching in SQL queries. Backfills existing data.
     *
     * @see com.bothbubbles.data.local.db.entity.ReactionClassifier for detection logic
     */
    val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add is_reaction column with default 0 (false)
            db.execSQL("ALTER TABLE messages ADD COLUMN is_reaction INTEGER NOT NULL DEFAULT 0")

            // Backfill existing messages: set is_reaction = 1 for reactions
            // This matches ReactionClassifier.IS_REACTION_SQL logic
            db.execSQL("""
                UPDATE messages SET is_reaction = 1
                WHERE associated_message_guid IS NOT NULL
                AND associated_message_type IS NOT NULL
                AND (
                    associated_message_type LIKE '%200%'
                    OR associated_message_type LIKE '%300%'
                    OR associated_message_type LIKE '%reaction%'
                    OR associated_message_type LIKE '%tapback%'
                    OR associated_message_type IN ('love', 'like', 'dislike', 'laugh', 'emphasize', 'question', '-love', '-like', '-dislike', '-laugh', '-emphasize', '-question')
                )
            """.trimIndent())

            // Create index for efficient queries filtering by reaction status
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_is_reaction ON messages(is_reaction)")
        }
    }

    /**
     * Migration from version 30 to 31: Add FTS5 full-text search index for messages.
     * This replaces O(n) LIKE '%query%' searches with O(log n) FTS5 MATCH queries.
     * For 100K+ messages, this provides 50-100x performance improvement.
     *
     * The FTS5 table is an "external content" table - it indexes text/subject from
     * the messages table but doesn't store duplicate data. Triggers keep it in sync.
     *
     * NOTE: FTS5 is not available on all Android devices (depends on system SQLite).
     * If FTS5 is unavailable, we skip creating it and fall back to LIKE-based search.
     */
    val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // FTS5 may not be available on all Android devices - wrap in try-catch
            // and fall back to LIKE-based search if unavailable
            try {
                // Create FTS5 virtual table with external content
                // The content= and content_rowid= options link it to the messages table
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                        text,
                        subject,
                        content=messages,
                        content_rowid=id,
                        tokenize='unicode61 remove_diacritics 2'
                    )
                """.trimIndent())

                // Populate FTS5 index with existing message content
                db.execSQL("""
                    INSERT INTO message_fts(rowid, text, subject)
                    SELECT id, text, subject FROM messages
                    WHERE text IS NOT NULL OR subject IS NOT NULL
                """.trimIndent())

                // Trigger to keep FTS5 in sync on INSERT
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
                        INSERT INTO message_fts(rowid, text, subject)
                        VALUES (NEW.id, NEW.text, NEW.subject);
                    END
                """.trimIndent())

                // Trigger to keep FTS5 in sync on DELETE
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
                        INSERT INTO message_fts(message_fts, rowid, text, subject)
                        VALUES ('delete', OLD.id, OLD.text, OLD.subject);
                    END
                """.trimIndent())

                // Trigger to keep FTS5 in sync on UPDATE
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
                        INSERT INTO message_fts(message_fts, rowid, text, subject)
                        VALUES ('delete', OLD.id, OLD.text, OLD.subject);
                        INSERT INTO message_fts(rowid, text, subject)
                        VALUES (NEW.id, NEW.text, NEW.subject);
                    END
                """.trimIndent())

                Timber.i("FTS5 full-text search enabled")
            } catch (e: Exception) {
                // FTS5 not available on this device - fall back to LIKE-based search
                Timber.w("FTS5 not available, falling back to LIKE-based search: ${e.message}")
            }
        }
    }

    /**
     * Migration from version 31 to 32: Add attachment error tracking columns.
     * These columns enable clear error states with retry functionality for
     * attachment downloads and uploads.
     *
     * New columns:
     * - error_type: Type of error (maps to AttachmentErrorState sealed class)
     * - error_message: User-friendly error description
     * - retry_count: Number of retry attempts for exponential backoff
     */
    val MIGRATION_31_32 = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add error_type column for categorizing failures
            db.execSQL("ALTER TABLE attachments ADD COLUMN error_type TEXT DEFAULT NULL")

            // Add error_message column for user-friendly messages
            db.execSQL("ALTER TABLE attachments ADD COLUMN error_message TEXT DEFAULT NULL")

            // Add retry_count column for exponential backoff tracking
            db.execSQL("ALTER TABLE attachments ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")

            // Create index on error_type for querying failed attachments
            db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_error_type ON attachments(error_type)")
        }
    }

    /**
     * Migration from version 32 to 33: Add error tracking to pending attachments.
     * This allows surfacing specific upload errors in the UI for pending messages.
     */
    val MIGRATION_32_33 = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add error_type column to pending_attachments
            db.execSQL("ALTER TABLE pending_attachments ADD COLUMN error_type TEXT DEFAULT NULL")

            // Add error_message column to pending_attachments
            db.execSQL("ALTER TABLE pending_attachments ADD COLUMN error_message TEXT DEFAULT NULL")
        }
    }

    /**
     * Migration from version 33 to 34: Add quality and caption to pending attachments.
     *
     * New columns:
     * - quality: Image compression quality setting (AUTO, STANDARD, HIGH, ORIGINAL)
     * - caption: Optional text caption to display with the attachment
     */
    val MIGRATION_33_34 = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add quality column with default STANDARD
            db.execSQL("ALTER TABLE pending_attachments ADD COLUMN quality TEXT NOT NULL DEFAULT 'STANDARD'")

            // Add caption column (nullable)
            db.execSQL("ALTER TABLE pending_attachments ADD COLUMN caption TEXT DEFAULT NULL")
        }
    }

    /**
     * Migration from version 34 to 35: Add verified_counterpart_checks table
     * for caching counterpart chat existence checks.
     *
     * This enables efficient "lazy repair" of unified groups:
     * - When opening a conversation with only SMS, check if iMessage exists on server
     * - Cache 404 results to avoid repeated checks for Android contacts
     * - Survives unified group rebuilds (unlike storing on the group itself)
     */
    val MIGRATION_34_35 = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS verified_counterpart_checks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    normalized_address TEXT NOT NULL,
                    has_counterpart INTEGER NOT NULL,
                    verified_at INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_verified_counterpart_checks_normalized_address ON verified_counterpart_checks(normalized_address)")
        }
    }

    /**
     * Migration from version 35 to 36: Add auto_share_rules and auto_share_recipients tables
     * for automatic ETA sharing when navigating to saved destinations.
     *
     * Features:
     * - Users can define destination rules with keywords for matching
     * - Each rule can have multiple recipients to auto-share ETA with
     * - Tracks consecutive trigger days for privacy reminders
     */
    val MIGRATION_35_36 = object : Migration(35, 36) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create auto_share_rules table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS auto_share_rules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    destination_name TEXT NOT NULL,
                    keywords TEXT NOT NULL,
                    location_type TEXT NOT NULL DEFAULT 'custom',
                    enabled INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()},
                    last_triggered_at INTEGER DEFAULT NULL,
                    consecutive_trigger_days INTEGER NOT NULL DEFAULT 0,
                    last_trigger_date INTEGER DEFAULT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_auto_share_rules_enabled ON auto_share_rules(enabled)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_auto_share_rules_destination_name ON auto_share_rules(destination_name)")

            // Create auto_share_recipients table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS auto_share_recipients (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    rule_id INTEGER NOT NULL,
                    chat_guid TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    added_at INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()},
                    FOREIGN KEY (rule_id) REFERENCES auto_share_rules(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_auto_share_recipients_rule_id ON auto_share_recipients(rule_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_auto_share_recipients_chat_guid ON auto_share_recipients(chat_guid)")
        }
    }

    /**
     * Migration from version 36 to 37: Simplify auto-share from destination-based rules
     * to contact-based auto-sharing.
     *
     * Google Maps (and other navigation apps) don't expose the destination in notifications,
     * so destination-based matching was never functional. This migration:
     * 1. Creates a simpler auto_share_contacts table (max 5 contacts)
     * 2. Drops the old auto_share_rules and auto_share_recipients tables
     *
     * The minimum ETA threshold is now a global setting in SettingsDataStore.
     */
    val MIGRATION_36_37 = object : Migration(36, 37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create new simplified auto_share_contacts table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS auto_share_contacts (
                    chat_guid TEXT PRIMARY KEY NOT NULL,
                    display_name TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_auto_share_contacts_enabled ON auto_share_contacts(enabled)")

            // Drop old tables (recipients first due to foreign key)
            db.execSQL("DROP TABLE IF EXISTS auto_share_recipients")
            db.execSQL("DROP TABLE IF EXISTS auto_share_rules")
        }
    }

    /**
     * Migration from version 37 to 38: Add tombstones table for tracking deleted items.
     *
     * When messages or chats are hard-deleted, their GUIDs are recorded here to prevent
     * them from being resurrected during sync operations. This enables:
     * - Safe repair syncs without bringing back deleted content
     * - Consistent deletion behavior across sync operations
     *
     * Tombstones older than 90 days can be safely pruned as old deleted items
     * are unlikely to appear in server sync queries.
     */
    val MIGRATION_37_38 = object : Migration(37, 38) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS tombstones (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    guid TEXT NOT NULL,
                    type TEXT NOT NULL,
                    deleted_at INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tombstones_guid ON tombstones(guid)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tombstones_type ON tombstones(type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tombstones_deleted_at ON tombstones(deleted_at)")
        }
    }

    /**
     * Migration from version 38 to 39: Add attributed_body_json column to pending_messages.
     *
     * This column stores the JSON representation of the attributedBody for messages
     * with mentions. The format follows Apple's NSAttributedString structure:
     * {"string": "...", "runs": [{"range": [start, length], "attributes": {...}}]}
     *
     * Only populated for messages with mentions in group chats.
     */
    val MIGRATION_38_39 = object : Migration(38, 39) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pending_messages ADD COLUMN attributed_body_json TEXT DEFAULT NULL")
        }
    }

    /**
     * Migration from version 39 to 40: Add life360_members table
     * for caching Life360 circle member location data.
     *
     * Features:
     * - Stores member info and location from Life360 API
     * - Optional mapping to BothBubbles handles (contacts)
     * - Foreign key to handles table with SET NULL on delete
     */
    val MIGRATION_39_40 = object : Migration(39, 40) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS life360_members (
                    member_id TEXT PRIMARY KEY NOT NULL,
                    circle_id TEXT NOT NULL,
                    first_name TEXT NOT NULL,
                    last_name TEXT DEFAULT NULL,
                    avatar_url TEXT DEFAULT NULL,
                    phone_number TEXT DEFAULT NULL,
                    latitude REAL DEFAULT NULL,
                    longitude REAL DEFAULT NULL,
                    accuracy_meters INTEGER DEFAULT NULL,
                    address TEXT DEFAULT NULL,
                    place_name TEXT DEFAULT NULL,
                    battery INTEGER DEFAULT NULL,
                    is_driving INTEGER DEFAULT NULL,
                    is_charging INTEGER DEFAULT NULL,
                    location_timestamp INTEGER DEFAULT NULL,
                    last_updated INTEGER NOT NULL,
                    no_location_reason TEXT DEFAULT NULL,
                    mapped_handle_id INTEGER DEFAULT NULL,
                    FOREIGN KEY (mapped_handle_id) REFERENCES handles(id) ON DELETE SET NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_life360_members_mapped_handle_id ON life360_members(mapped_handle_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_life360_members_circle_id ON life360_members(circle_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_life360_members_phone_number ON life360_members(phone_number)")
        }
    }

    /**
     * Migration from version 40 to 41: Add short_address column to life360_members.
     *
     * The Life360 API returns address1 (street) and address2 (city, state) separately.
     * Previously we combined them into the address field. Now we store address2 in
     * short_address for use in the chat subtitle, which should show "City, State"
     * instead of the full street address.
     */
    val MIGRATION_40_41 = object : Migration(40, 41) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE life360_members ADD COLUMN short_address TEXT DEFAULT NULL")
        }
    }

    /**
     * Migration from version 41 to 42: Add auto_link_disabled column to life360_members.
     *
     * When a user manually unlinks a Life360 member from a contact in the settings,
     * this flag is set to true to prevent autoMapContacts() from re-linking them
     * on the next sync. The flag is cleared when the user manually links to a contact.
     */
    val MIGRATION_41_42 = object : Migration(41, 42) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE life360_members ADD COLUMN auto_link_disabled INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Migration from version 42 to 43: Add linked_address column to life360_members.
     *
     * Changes Life360 contact linking from handle ID-based to address-based.
     * This fixes the issue where a contact with multiple handles (iMessage + SMS)
     * would only show location in chats using the specific handle ID that was linked.
     *
     * The migration:
     * 1. Adds linked_address column
     * 2. Populates it from existing mapped_handle_id by looking up the handle's address
     * 3. Keeps mapped_handle_id for backwards compatibility (will be removed in future)
     */
    val MIGRATION_42_43 = object : Migration(42, 43) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add linked_address column
            db.execSQL("ALTER TABLE life360_members ADD COLUMN linked_address TEXT DEFAULT NULL")

            // Add index for address-based lookups
            db.execSQL("CREATE INDEX IF NOT EXISTS index_life360_members_linked_address ON life360_members(linked_address)")

            // Migrate existing mappings: copy address from handles table
            db.execSQL("""
                UPDATE life360_members
                SET linked_address = (
                    SELECT h.address FROM handles h
                    WHERE h.id = life360_members.mapped_handle_id
                )
                WHERE mapped_handle_id IS NOT NULL
            """)
        }
    }

    /**
     * Migration from version 43 to 44: Remove unused per-chat notification settings columns.
     *
     * These columns were part of a custom notification settings UI that duplicated
     * Android's native notification channel functionality. We now use per-conversation
     * notification channels instead, allowing users to customize sound/vibration/importance
     * via Android Settings > Apps > BothBubbles > Notifications.
     *
     * Removed columns:
     * - custom_notification_sound
     * - notification_priority
     * - bubble_enabled
     * - pop_on_screen
     * - lock_screen_visibility
     * - show_notification_dot
     * - vibration_enabled
     *
     * Kept columns:
     * - notifications_enabled (app-level mute toggle)
     * - snooze_until (app-level snooze functionality)
     *
     * SQLite doesn't support DROP COLUMN on older versions, so we recreate the table.
     */
    val MIGRATION_43_44 = object : Migration(43, 44) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create new table without the removed columns
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS chats_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    guid TEXT NOT NULL,
                    chat_identifier TEXT DEFAULT NULL,
                    display_name TEXT DEFAULT NULL,
                    is_pinned INTEGER NOT NULL DEFAULT 0,
                    pin_index INTEGER DEFAULT NULL,
                    is_archived INTEGER NOT NULL DEFAULT 0,
                    is_starred INTEGER NOT NULL DEFAULT 0,
                    mute_type TEXT DEFAULT NULL,
                    mute_args TEXT DEFAULT NULL,
                    has_unread_message INTEGER NOT NULL DEFAULT 0,
                    unread_count INTEGER NOT NULL DEFAULT 0,
                    style INTEGER DEFAULT NULL,
                    is_group INTEGER NOT NULL DEFAULT 0,
                    last_message_text TEXT DEFAULT NULL,
                    last_message_date INTEGER DEFAULT NULL,
                    custom_avatar_path TEXT DEFAULT NULL,
                    notifications_enabled INTEGER NOT NULL DEFAULT 1,
                    snooze_until INTEGER DEFAULT NULL,
                    auto_send_read_receipts INTEGER DEFAULT NULL,
                    auto_send_typing_indicators INTEGER DEFAULT NULL,
                    text_field_text TEXT DEFAULT NULL,
                    lock_chat_name INTEGER NOT NULL DEFAULT 0,
                    lock_chat_icon INTEGER NOT NULL DEFAULT 0,
                    latest_message_date INTEGER DEFAULT NULL,
                    date_created INTEGER NOT NULL DEFAULT 0,
                    date_deleted INTEGER DEFAULT NULL,
                    is_sms_fallback INTEGER NOT NULL DEFAULT 0,
                    fallback_reason TEXT DEFAULT NULL,
                    fallback_updated_at INTEGER DEFAULT NULL,
                    is_spam INTEGER NOT NULL DEFAULT 0,
                    spam_score INTEGER NOT NULL DEFAULT 0,
                    spam_reported_to_carrier INTEGER NOT NULL DEFAULT 0,
                    category TEXT DEFAULT NULL,
                    category_confidence INTEGER NOT NULL DEFAULT 0,
                    category_last_updated INTEGER DEFAULT NULL,
                    preferred_send_mode TEXT DEFAULT NULL,
                    send_mode_manually_set INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())

            // Copy data from old table (excluding removed columns)
            db.execSQL("""
                INSERT INTO chats_new (
                    id, guid, chat_identifier, display_name, is_pinned, pin_index,
                    is_archived, is_starred, mute_type, mute_args, has_unread_message,
                    unread_count, style, is_group, last_message_text, last_message_date,
                    custom_avatar_path, notifications_enabled, snooze_until,
                    auto_send_read_receipts, auto_send_typing_indicators, text_field_text,
                    lock_chat_name, lock_chat_icon, latest_message_date, date_created,
                    date_deleted, is_sms_fallback, fallback_reason, fallback_updated_at,
                    is_spam, spam_score, spam_reported_to_carrier, category,
                    category_confidence, category_last_updated, preferred_send_mode,
                    send_mode_manually_set
                )
                SELECT
                    id, guid, chat_identifier, display_name, is_pinned, pin_index,
                    is_archived, is_starred, mute_type, mute_args, has_unread_message,
                    unread_count, style, is_group, last_message_text, last_message_date,
                    custom_avatar_path, notifications_enabled, snooze_until,
                    auto_send_read_receipts, auto_send_typing_indicators, text_field_text,
                    lock_chat_name, lock_chat_icon, latest_message_date, date_created,
                    date_deleted, is_sms_fallback, fallback_reason, fallback_updated_at,
                    is_spam, spam_score, spam_reported_to_carrier, category,
                    category_confidence, category_last_updated, preferred_send_mode,
                    send_mode_manually_set
                FROM chats
            """.trimIndent())

            // Drop old table
            db.execSQL("DROP TABLE chats")

            // Rename new table
            db.execSQL("ALTER TABLE chats_new RENAME TO chats")

            // Recreate indexes
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_chats_guid ON chats(guid)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_is_pinned ON chats(is_pinned)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_is_starred ON chats(is_starred)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_latest_message_date ON chats(latest_message_date)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_is_spam ON chats(is_spam)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_category ON chats(category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_is_archived ON chats(is_archived)")
        }
    }

    /**
     * Migration from version 44 to 45: Add pending_read_status table for reliable
     * read status synchronization to the BlueBubbles server.
     *
     * Read status changes are now queued in this table and processed by WorkManager,
     * ensuring reliable delivery even when network is unavailable or the app is killed.
     *
     * Features:
     * - Deduplication by chat_guid (unique index) - only latest status matters
     * - Retry tracking with exponential backoff
     * - Error message storage for debugging
     */
    val MIGRATION_44_45 = object : Migration(44, 45) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS pending_read_status (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    chat_guid TEXT NOT NULL,
                    is_read INTEGER NOT NULL DEFAULT 1,
                    sync_status TEXT NOT NULL DEFAULT 'PENDING',
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    error_message TEXT DEFAULT NULL,
                    created_at INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()},
                    last_attempt_at INTEGER DEFAULT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_read_status_chat_guid ON pending_read_status(chat_guid)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_read_status_sync_status ON pending_read_status(sync_status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_read_status_created_at ON pending_read_status(created_at)")
        }
    }

    /**
     * Migration from version 45 to 46: Add depends_on_local_id column to pending_messages.
     *
     * This column enables message ordering within a chat:
     * - When a message is queued while another message is PENDING/SENDING, it depends on that message
     * - The MessageSendWorker waits for the dependency to reach SENT status before sending
     * - If the dependency fails, all dependent messages are cascade-failed
     *
     * This prevents messages from arriving out of order and enables grouped failure notifications.
     */
    val MIGRATION_45_46 = object : Migration(45, 46) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pending_messages ADD COLUMN depends_on_local_id TEXT DEFAULT NULL")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_messages_depends_on_local_id ON pending_messages(depends_on_local_id)")
        }
    }

    /**
     * Migration from version 46 to 47: Add server group photo columns to chats.
     *
     * iMessage groups can have custom group photos set by participants. The BlueBubbles
     * server exposes this via the groupPhotoGuid field, which is an attachment GUID.
     *
     * New columns:
     * - server_group_photo_guid: The attachment GUID from the server (used to detect changes)
     * - server_group_photo_path: Local path to the downloaded group photo
     *
     * These are separate from custom_avatar_path which is user-set on the Android device.
     * Priority: custom_avatar_path > server_group_photo_path > participant collage
     */
    val MIGRATION_46_47 = object : Migration(46, 47) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN server_group_photo_guid TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE chats ADD COLUMN server_group_photo_path TEXT DEFAULT NULL")
        }
    }

    /**
     * Migration from version 47 to 48: Add split_batch_id column to messages and pending_messages.
     *
     * When sending a message with both text and attachments, they are now split into separate
     * messages (matching native iMessage behavior). This column links related messages for:
     * - Visual grouping in the UI (tighter spacing, connected bubbles)
     * - Understanding which messages were composed together
     * - Independent retry per message (the main fix this enables)
     *
     * Format: "batch-{UUID}" or null for single messages.
     */
    val MIGRATION_47_48 = object : Migration(47, 48) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add split_batch_id to messages table
            db.execSQL("ALTER TABLE messages ADD COLUMN split_batch_id TEXT DEFAULT NULL")

            // Add split_batch_id to pending_messages table
            db.execSQL("ALTER TABLE pending_messages ADD COLUMN split_batch_id TEXT DEFAULT NULL")
        }
    }

    /**
     * Migration from version 48 to 49: Add message_edit_history table for tracking message edits.
     *
     * iMessage supports message editing (iOS 16+). When a message is edited:
     * - The server sends an updated message with new text and dateEdited
     * - We save the previous text in this table before updating the message
     * - The UI can show "Edited" indicator and allow viewing edit history
     *
     * The table uses a foreign key to cascade-delete history when the message is deleted.
     */
    val MIGRATION_48_49 = object : Migration(48, 49) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS message_edit_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    message_guid TEXT NOT NULL,
                    previous_text TEXT DEFAULT NULL,
                    edited_at INTEGER NOT NULL,
                    FOREIGN KEY (message_guid) REFERENCES messages(guid) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_message_edit_history_message_guid ON message_edit_history(message_guid)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_message_edit_history_edited_at ON message_edit_history(edited_at)")
        }
    }

    /**
     * Migration from version 49 to 50: Add unified_chats table and reset data for clean sync.
     *
     * This is a BREAKING migration that clears message/chat data to force a full re-sync.
     * The new architecture requires messages to have unified_chat_id set, which can only
     * be done during message insertion. Rather than complex data migration, we clear
     * existing data and let the normal sync process repopulate everything correctly.
     *
     * The new architecture:
     * - Single UnifiedChatEntity is the source of truth for conversation state
     * - Messages have unified_chat_id directly for native pagination
     * - ChatEntity simplified to protocol-specific channel with unifiedChatId foreign key
     *
     * What happens on app launch after this migration:
     * 1. SyncService detects empty sync_ranges with initialSyncComplete=true
     * 2. Resets sync flags in DataStore (initialSyncComplete, hasCompletedInitialSmsImport)
     * 3. Triggers full re-sync of iMessage (via BlueBubbles) and SMS (if enabled)
     * 4. User sees normal "Syncing messages..." progress UI
     *
     * Data preserved: handles (contacts), quick reply templates, scheduled messages
     * Data cleared: messages, chats, attachments, sync_ranges, link_previews
     */
    val MIGRATION_49_50 = object : Migration(49, 50) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Timber.i("Starting migration 49→50: Unified chat architecture with data reset")

            // Create new unified_chats table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS unified_chats (
                    id TEXT PRIMARY KEY NOT NULL,
                    normalized_address TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    display_name TEXT DEFAULT NULL,
                    custom_avatar_path TEXT DEFAULT NULL,
                    server_group_photo_path TEXT DEFAULT NULL,
                    server_group_photo_guid TEXT DEFAULT NULL,
                    latest_message_date INTEGER DEFAULT NULL,
                    latest_message_text TEXT DEFAULT NULL,
                    latest_message_guid TEXT DEFAULT NULL,
                    latest_message_is_from_me INTEGER NOT NULL DEFAULT 0,
                    latest_message_has_attachments INTEGER NOT NULL DEFAULT 0,
                    latest_message_source TEXT DEFAULT NULL,
                    latest_message_date_delivered INTEGER DEFAULT NULL,
                    latest_message_date_read INTEGER DEFAULT NULL,
                    latest_message_error INTEGER NOT NULL DEFAULT 0,
                    unread_count INTEGER NOT NULL DEFAULT 0,
                    has_unread_message INTEGER NOT NULL DEFAULT 0,
                    is_pinned INTEGER NOT NULL DEFAULT 0,
                    pin_index INTEGER DEFAULT NULL,
                    is_archived INTEGER NOT NULL DEFAULT 0,
                    is_starred INTEGER NOT NULL DEFAULT 0,
                    mute_type TEXT DEFAULT NULL,
                    mute_args TEXT DEFAULT NULL,
                    snooze_until INTEGER DEFAULT NULL,
                    notifications_enabled INTEGER NOT NULL DEFAULT 1,
                    is_spam INTEGER NOT NULL DEFAULT 0,
                    spam_score INTEGER NOT NULL DEFAULT 0,
                    spam_reported_to_carrier INTEGER NOT NULL DEFAULT 0,
                    category TEXT DEFAULT NULL,
                    category_confidence INTEGER NOT NULL DEFAULT 0,
                    category_last_updated INTEGER DEFAULT NULL,
                    is_group INTEGER NOT NULL DEFAULT 0,
                    auto_send_read_receipts INTEGER DEFAULT NULL,
                    auto_send_typing_indicators INTEGER DEFAULT NULL,
                    text_field_text TEXT DEFAULT NULL,
                    lock_chat_name INTEGER NOT NULL DEFAULT 0,
                    lock_chat_icon INTEGER NOT NULL DEFAULT 0,
                    preferred_send_mode TEXT DEFAULT NULL,
                    send_mode_manually_set INTEGER NOT NULL DEFAULT 0,
                    is_sms_fallback INTEGER NOT NULL DEFAULT 0,
                    fallback_reason TEXT DEFAULT NULL,
                    fallback_updated_at INTEGER DEFAULT NULL,
                    created_at INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()},
                    date_deleted INTEGER DEFAULT NULL
                )
            """.trimIndent())

            // Create indexes for the new table
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_unified_chats_normalized_address ON unified_chats(normalized_address)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_unified_chats_latest_message_date ON unified_chats(latest_message_date)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_unified_chats_is_pinned_pin_index ON unified_chats(is_pinned, pin_index)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_unified_chats_is_archived ON unified_chats(is_archived)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_unified_chats_is_spam ON unified_chats(is_spam)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_unified_chats_category ON unified_chats(category)")

            // Add unified_chat_id column to chats table
            db.execSQL("ALTER TABLE chats ADD COLUMN unified_chat_id TEXT DEFAULT NULL")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_unified_chat_id ON chats(unified_chat_id)")

            // Add unified_chat_id column to messages table for native pagination
            db.execSQL("ALTER TABLE messages ADD COLUMN unified_chat_id TEXT DEFAULT NULL")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_unified_chat_id ON messages(unified_chat_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_unified_chat_id_date_created ON messages(unified_chat_id, date_created)")

            // === CLEAR DATA FOR CLEAN RE-SYNC ===
            // Order matters due to foreign key constraints

            // Clear link previews (references messages)
            db.execSQL("DELETE FROM link_previews")
            Timber.d("Cleared link_previews table")

            // Clear attachments (references messages)
            db.execSQL("DELETE FROM attachments")
            Timber.d("Cleared attachments table")

            // Clear messages
            db.execSQL("DELETE FROM messages")
            Timber.d("Cleared messages table")

            // Clear chat-handle junction (references chats)
            db.execSQL("DELETE FROM chat_handle_cross_ref")
            Timber.d("Cleared chat_handle_cross_ref table")

            // Clear chats
            db.execSQL("DELETE FROM chats")
            Timber.d("Cleared chats table")

            // Clear sync ranges (triggers re-sync detection)
            db.execSQL("DELETE FROM sync_ranges")
            Timber.d("Cleared sync_ranges table")

            // Clear seen messages (will be rebuilt)
            db.execSQL("DELETE FROM seen_messages")
            Timber.d("Cleared seen_messages table")

            Timber.i("Migration 49→50 complete: Data cleared, app will re-sync on next launch")
        }
    }

    /**
     * Migration from version 50 to 51: Drop legacy unified chat tables.
     *
     * The old junction-table-based unified chat system (unified_chat_groups + unified_chat_members)
     * is replaced by the new single-entity architecture (unified_chats + unifiedChatId foreign keys).
     *
     * This migration removes:
     * - unified_chat_groups table (replaced by unified_chats)
     * - unified_chat_members junction table (replaced by ChatEntity.unifiedChatId)
     *
     * The new architecture benefits:
     * - Messages have unified_chat_id directly for O(1) conversation lookup
     * - Native database pagination without stream merging
     * - Simpler code without junction table complexity
     */
    val MIGRATION_50_51 = object : Migration(50, 51) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Drop the junction table first (due to foreign key)
            db.execSQL("DROP TABLE IF EXISTS unified_chat_members")

            // Drop the old unified groups table
            db.execSQL("DROP TABLE IF EXISTS unified_chat_groups")

            Timber.i("Dropped legacy unified_chat_groups and unified_chat_members tables")
        }
    }

    /**
     * Migration from version 51 to 52: Trim chats table to protocol-only fields.
     *
     * The unified chat architecture moved conversation-level state to UnifiedChatEntity.
     * ChatEntity now only stores protocol-specific data:
     * - id, guid, unified_chat_id, chat_identifier, display_name
     * - style, is_group, latest_message_date, date_created, date_deleted
     *
     * All other fields (is_pinned, is_archived, mute_type, etc.) are now in UnifiedChatEntity.
     * This migration recreates the chats table with the trimmed schema.
     */
    val MIGRATION_51_52 = object : Migration(51, 52) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Timber.i("Starting migration 51→52: Trim chats table to protocol-only fields")

            // Create new trimmed chats table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS chats_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    guid TEXT NOT NULL,
                    unified_chat_id TEXT DEFAULT NULL,
                    chat_identifier TEXT DEFAULT NULL,
                    display_name TEXT DEFAULT NULL,
                    style INTEGER DEFAULT NULL,
                    is_group INTEGER NOT NULL DEFAULT 0,
                    latest_message_date INTEGER DEFAULT NULL,
                    date_created INTEGER NOT NULL DEFAULT 0,
                    date_deleted INTEGER DEFAULT NULL
                )
            """.trimIndent())

            // Copy data from old table (only the columns we're keeping)
            db.execSQL("""
                INSERT INTO chats_new (
                    id, guid, unified_chat_id, chat_identifier, display_name,
                    style, is_group, latest_message_date, date_created, date_deleted
                )
                SELECT
                    id, guid, unified_chat_id, chat_identifier, display_name,
                    style, is_group, latest_message_date, date_created, date_deleted
                FROM chats
            """.trimIndent())

            // Drop old table
            db.execSQL("DROP TABLE chats")

            // Rename new table
            db.execSQL("ALTER TABLE chats_new RENAME TO chats")

            // Recreate indexes
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_chats_guid ON chats(guid)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_unified_chat_id ON chats(unified_chat_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_latest_message_date ON chats(latest_message_date)")

            Timber.i("Migration 51→52 complete: Chats table trimmed to protocol-only fields")
        }
    }

    /**
     * Migration from version 52 to 53: Clear invalid unified_chat_id from group chats.
     *
     * Group chats were incorrectly being linked to unified chats, which caused them to be
     * displayed with the wrong sender name (first participant's name instead of actual sender).
     * Unified chats are only for merging 1:1 iMessage/SMS conversations.
     *
     * This migration clears the unified_chat_id from any group chats that were incorrectly linked.
     */
    val MIGRATION_52_53 = object : Migration(52, 53) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Clear unified_chat_id from group chats (they should never be linked)
            db.execSQL("UPDATE chats SET unified_chat_id = NULL WHERE is_group = 1 AND unified_chat_id IS NOT NULL")
            Timber.i("Migration 52→53 complete: Cleared invalid unified_chat_id from group chats")
        }
    }

    /**
     * Migration from version 53 to 54: Add contact_calendar_associations table.
     *
     * This table stores which device calendar is associated with which contact.
     * Uses address-based linking (like Life360) so the association works across
     * both iMessage and SMS handles for the same contact.
     */
    val MIGRATION_53_54 = object : Migration(53, 54) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS contact_calendar_associations (
                    linked_address TEXT PRIMARY KEY NOT NULL,
                    calendar_id INTEGER NOT NULL,
                    calendar_display_name TEXT NOT NULL,
                    calendar_color INTEGER DEFAULT NULL,
                    account_name TEXT DEFAULT NULL,
                    created_at INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()},
                    updated_at INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_calendar_associations_calendar_id ON contact_calendar_associations(calendar_id)"
            )
            Timber.i("Migration 53→54 complete: Added contact_calendar_associations table")
        }
    }

    /**
     * Migration 54 → 55: Add group photo fields to chats table.
     *
     * Group photos from the BlueBubbles server were previously stored on unified_chats,
     * but group chats don't use unified chats. This adds the fields directly to ChatEntity
     * so group photo sync can work properly.
     */
    val MIGRATION_54_55 = object : Migration(54, 55) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN server_group_photo_path TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE chats ADD COLUMN server_group_photo_guid TEXT DEFAULT NULL")
            Timber.i("Migration 54→55 complete: Added group photo fields to chats table")
        }
    }

    /**
     * List of all migrations for use with databaseBuilder.
     *
     * IMPORTANT: Always add new migrations to this array!
     * Order matters - migrations are applied sequentially.
     */
    val ALL_MIGRATIONS = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
        MIGRATION_22_23,
        MIGRATION_23_24,
        MIGRATION_24_25,
        MIGRATION_25_26,
        MIGRATION_26_27,
        MIGRATION_27_28,
        MIGRATION_28_29,
        MIGRATION_29_30,
        MIGRATION_30_31,
        MIGRATION_31_32,
        MIGRATION_32_33,
        MIGRATION_33_34,
        MIGRATION_34_35,
        MIGRATION_35_36,
        MIGRATION_36_37,
        MIGRATION_37_38,
        MIGRATION_38_39,
        MIGRATION_39_40,
        MIGRATION_40_41,
        MIGRATION_41_42,
        MIGRATION_42_43,
        MIGRATION_43_44,
        MIGRATION_44_45,
        MIGRATION_45_46,
        MIGRATION_46_47,
        MIGRATION_47_48,
        MIGRATION_48_49,
        MIGRATION_49_50,
        MIGRATION_50_51,
        MIGRATION_51_52,
        MIGRATION_52_53,
        MIGRATION_53_54,
        MIGRATION_54_55
    )
}
