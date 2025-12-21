package com.bothbubbles.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.AutoRespondedSenderDao
import com.bothbubbles.data.local.db.dao.AutoShareContactDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.ChatParticipantDao
import com.bothbubbles.data.local.db.dao.ChatQueryDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.IMessageCacheDao
import com.bothbubbles.data.local.db.dao.Life360Dao
import com.bothbubbles.data.local.db.dao.LinkPreviewDao
import com.bothbubbles.data.local.db.dao.PopularChatsDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.PendingAttachmentDao
import com.bothbubbles.data.local.db.dao.PendingMessageDao
import com.bothbubbles.data.local.db.dao.PendingReadStatusDao
import com.bothbubbles.data.local.db.dao.QuickReplyTemplateDao
import com.bothbubbles.data.local.db.dao.ScheduledMessageDao
import com.bothbubbles.data.local.db.dao.SeenMessageDao
import com.bothbubbles.data.local.db.dao.SyncRangeDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.dao.TombstoneDao
import com.bothbubbles.data.local.db.dao.VerifiedCounterpartCheckDao
import com.bothbubbles.data.local.db.dao.MessageEditHistoryDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.AutoRespondedSenderEntity
import com.bothbubbles.data.local.db.entity.AutoShareContactEntity
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.ChatHandleCrossRef
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.IMessageAvailabilityCacheEntity
import com.bothbubbles.data.local.db.entity.LinkPreviewEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.PendingAttachmentEntity
import com.bothbubbles.data.local.db.entity.PendingMessageEntity
import com.bothbubbles.data.local.db.entity.PendingReadStatusEntity
import com.bothbubbles.data.local.db.entity.QuickReplyTemplateEntity
import com.bothbubbles.data.local.db.entity.ScheduledMessageEntity
import com.bothbubbles.data.local.db.entity.SeenMessageEntity
import com.bothbubbles.data.local.db.entity.SyncRangeEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatMember
import com.bothbubbles.data.local.db.entity.TombstoneEntity
import com.bothbubbles.data.local.db.entity.VerifiedCounterpartCheckEntity
import com.bothbubbles.data.local.db.entity.MessageEditHistoryEntity
import com.bothbubbles.core.model.entity.Life360MemberEntity

/**
 * Room database for BothBubbles.
 *
 * Database migrations are defined in [DatabaseMigrations].
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
        SeenMessageEntity::class,
        PendingMessageEntity::class,
        PendingAttachmentEntity::class,
        PendingReadStatusEntity::class,
        IMessageAvailabilityCacheEntity::class,
        SyncRangeEntity::class,
        AutoRespondedSenderEntity::class,
        VerifiedCounterpartCheckEntity::class,
        AutoShareContactEntity::class,
        TombstoneEntity::class,
        Life360MemberEntity::class,
        MessageEditHistoryEntity::class
    ],
    version = 49,
    exportSchema = true
)
abstract class BothBubblesDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun chatParticipantDao(): ChatParticipantDao
    abstract fun messageDao(): MessageDao
    abstract fun handleDao(): HandleDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun linkPreviewDao(): LinkPreviewDao
    abstract fun quickReplyTemplateDao(): QuickReplyTemplateDao
    abstract fun scheduledMessageDao(): ScheduledMessageDao
    abstract fun unifiedChatGroupDao(): UnifiedChatGroupDao
    abstract fun seenMessageDao(): SeenMessageDao
    abstract fun pendingMessageDao(): PendingMessageDao
    abstract fun pendingAttachmentDao(): PendingAttachmentDao
    abstract fun pendingReadStatusDao(): PendingReadStatusDao
    abstract fun iMessageCacheDao(): IMessageCacheDao
    abstract fun syncRangeDao(): SyncRangeDao
    abstract fun autoRespondedSenderDao(): AutoRespondedSenderDao
    abstract fun verifiedCounterpartCheckDao(): VerifiedCounterpartCheckDao
    abstract fun chatQueryDao(): ChatQueryDao
    abstract fun autoShareContactDao(): AutoShareContactDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun life360Dao(): Life360Dao
    abstract fun popularChatsDao(): PopularChatsDao
    abstract fun messageEditHistoryDao(): MessageEditHistoryDao

    companion object {
        const val DATABASE_NAME = "bothbubbles.db"
    }
}
