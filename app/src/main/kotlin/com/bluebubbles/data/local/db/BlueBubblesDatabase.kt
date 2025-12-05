package com.bluebubbles.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.bluebubbles.data.local.db.dao.AttachmentDao
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.HandleDao
import com.bluebubbles.data.local.db.dao.MessageDao
import com.bluebubbles.data.local.db.entity.AttachmentEntity
import com.bluebubbles.data.local.db.entity.ChatEntity
import com.bluebubbles.data.local.db.entity.ChatHandleCrossRef
import com.bluebubbles.data.local.db.entity.HandleEntity
import com.bluebubbles.data.local.db.entity.MessageEntity

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        HandleEntity::class,
        AttachmentEntity::class,
        ChatHandleCrossRef::class
    ],
    version = 1,
    exportSchema = false
)
abstract class BlueBubblesDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun handleDao(): HandleDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        const val DATABASE_NAME = "bluebubbles.db"
    }
}
