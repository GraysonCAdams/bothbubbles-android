package com.bothbubbles.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.LinkPreviewDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.QuickReplyTemplateDao
import com.bothbubbles.data.local.db.dao.ScheduledMessageDao
import com.bothbubbles.data.local.db.dao.SeenMessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.ChatHandleCrossRef
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.LinkPreviewEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.QuickReplyTemplateEntity
import com.bothbubbles.data.local.db.entity.ScheduledMessageEntity
import com.bothbubbles.data.local.db.entity.SeenMessageEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatMember

/**
 * Room database for BlueBubbles.
 *
 * ## Migration Strategy
 *
 * When adding new columns or changing the schema:
 * 1. Increment the [version] number
 * 2. Create a new migration object (e.g., MIGRATION_3_4)
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
@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        HandleEntity::class,
        AttachmentEntity::class,
        ChatHandleCrossRef::class,
        LinkPreviewEntity::class,
        QuickReplyTemplateEntity::class,
        ScheduledMessageEntity::class,
        UnifiedChatGroupEntity::class,
        UnifiedChatMember::class,
        SeenMessageEntity::class
    ],
    version = 19,
    exportSchema = true
)
abstract class BothBubblesDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun handleDao(): HandleDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun linkPreviewDao(): LinkPreviewDao
    abstract fun quickReplyTemplateDao(): QuickReplyTemplateDao
    abstract fun scheduledMessageDao(): ScheduledMessageDao
    abstract fun unifiedChatGroupDao(): UnifiedChatGroupDao
    abstract fun seenMessageDao(): SeenMessageDao

    companion object {
        const val DATABASE_NAME = "bothbubbles.db"

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
            MIGRATION_18_19
        )
    }
}
