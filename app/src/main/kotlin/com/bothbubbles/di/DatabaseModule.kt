package com.bothbubbles.di

import android.content.Context
import androidx.room.Room
import com.bothbubbles.data.local.db.BothBubblesDatabase
import com.bothbubbles.data.local.db.DatabaseMigrations
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.AutoRespondedSenderDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.IMessageCacheDao
import com.bothbubbles.data.local.db.dao.LinkPreviewDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.PendingAttachmentDao
import com.bothbubbles.data.local.db.dao.PendingMessageDao
import com.bothbubbles.data.local.db.dao.QuickReplyTemplateDao
import com.bothbubbles.data.local.db.dao.ScheduledMessageDao
import com.bothbubbles.data.local.db.dao.SeenMessageDao
import com.bothbubbles.data.local.db.dao.SyncRangeDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.dao.VerifiedCounterpartCheckDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the Room database and all Data Access Objects (DAOs).
 *
 * This module is responsible for all local data persistence dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Provides the Room database instance.
     *
     * Migration strategy:
     * - All migrations are defined in [BothBubblesDatabase.ALL_MIGRATIONS]
     * - On downgrade (rare), data is cleared as a safety measure
     * - If a migration is missing, the app will crash - this is intentional
     *   to catch migration issues during development rather than silently
     *   destroying user data in production
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): BothBubblesDatabase {
        return Room.databaseBuilder(
            context,
            BothBubblesDatabase::class.java,
            BothBubblesDatabase.DATABASE_NAME
        )
            .addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)
            // Only destroy data on downgrade (e.g., rolling back to older app version)
            // Missing migrations will crash - this is intentional to catch issues early
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides
    @Singleton
    fun provideChatDao(database: BothBubblesDatabase): ChatDao {
        return database.chatDao()
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: BothBubblesDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    @Singleton
    fun provideHandleDao(database: BothBubblesDatabase): HandleDao {
        return database.handleDao()
    }

    @Provides
    @Singleton
    fun provideAttachmentDao(database: BothBubblesDatabase): AttachmentDao {
        return database.attachmentDao()
    }

    @Provides
    @Singleton
    fun provideLinkPreviewDao(database: BothBubblesDatabase): LinkPreviewDao {
        return database.linkPreviewDao()
    }

    @Provides
    @Singleton
    fun provideQuickReplyTemplateDao(database: BothBubblesDatabase): QuickReplyTemplateDao {
        return database.quickReplyTemplateDao()
    }

    @Provides
    @Singleton
    fun provideScheduledMessageDao(database: BothBubblesDatabase): ScheduledMessageDao {
        return database.scheduledMessageDao()
    }

    @Provides
    @Singleton
    fun provideUnifiedChatGroupDao(database: BothBubblesDatabase): UnifiedChatGroupDao {
        return database.unifiedChatGroupDao()
    }

    @Provides
    @Singleton
    fun provideSeenMessageDao(database: BothBubblesDatabase): SeenMessageDao {
        return database.seenMessageDao()
    }

    @Provides
    @Singleton
    fun providePendingMessageDao(database: BothBubblesDatabase): PendingMessageDao {
        return database.pendingMessageDao()
    }

    @Provides
    @Singleton
    fun providePendingAttachmentDao(database: BothBubblesDatabase): PendingAttachmentDao {
        return database.pendingAttachmentDao()
    }

    @Provides
    @Singleton
    fun provideIMessageCacheDao(database: BothBubblesDatabase): IMessageCacheDao {
        return database.iMessageCacheDao()
    }

    @Provides
    @Singleton
    fun provideSyncRangeDao(database: BothBubblesDatabase): SyncRangeDao {
        return database.syncRangeDao()
    }

    @Provides
    @Singleton
    fun provideAutoRespondedSenderDao(database: BothBubblesDatabase): AutoRespondedSenderDao {
        return database.autoRespondedSenderDao()
    }

    @Provides
    @Singleton
    fun provideVerifiedCounterpartCheckDao(database: BothBubblesDatabase): VerifiedCounterpartCheckDao {
        return database.verifiedCounterpartCheckDao()
    }
}
