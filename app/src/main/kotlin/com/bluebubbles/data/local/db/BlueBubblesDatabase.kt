package com.bluebubbles.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bluebubbles.data.local.db.dao.AttachmentDao
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.HandleDao
import com.bluebubbles.data.local.db.dao.MessageDao
import com.bluebubbles.data.local.db.entity.AttachmentEntity
import com.bluebubbles.data.local.db.entity.ChatEntity
import com.bluebubbles.data.local.db.entity.ChatHandleCrossRef
import com.bluebubbles.data.local.db.entity.HandleEntity
import com.bluebubbles.data.local.db.entity.MessageEntity

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
        ChatHandleCrossRef::class
    ],
    version = 4,
    exportSchema = true
)
abstract class BlueBubblesDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun handleDao(): HandleDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        const val DATABASE_NAME = "bluebubbles.db"

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
         * List of all migrations for use with databaseBuilder.
         *
         * IMPORTANT: Always add new migrations to this array!
         * Order matters - migrations are applied sequentially.
         */
        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4
        )
    }
}
