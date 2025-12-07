package com.bluebubbles.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.bluebubbles.data.local.db.BlueBubblesDatabase
import com.bluebubbles.data.local.db.dao.AttachmentDao
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.HandleDao
import com.bluebubbles.data.local.db.dao.LinkPreviewDao
import com.bluebubbles.data.local.db.dao.MessageDao
import com.bluebubbles.data.local.db.dao.QuickReplyTemplateDao
import com.bluebubbles.data.local.db.dao.ScheduledMessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides the Room database instance.
     *
     * Migration strategy:
     * - All migrations are defined in [BlueBubblesDatabase.ALL_MIGRATIONS]
     * - On downgrade (rare), data is cleared as a safety measure
     * - If a migration is missing, the app will crash - this is intentional
     *   to catch migration issues during development rather than silently
     *   destroying user data in production
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): BlueBubblesDatabase {
        return Room.databaseBuilder(
            context,
            BlueBubblesDatabase::class.java,
            BlueBubblesDatabase.DATABASE_NAME
        )
            .addMigrations(*BlueBubblesDatabase.ALL_MIGRATIONS)
            // Only destroy data on downgrade (e.g., rolling back to older app version)
            // Missing migrations will crash - this is intentional to catch issues early
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides
    @Singleton
    fun provideChatDao(database: BlueBubblesDatabase): ChatDao {
        return database.chatDao()
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: BlueBubblesDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    @Singleton
    fun provideHandleDao(database: BlueBubblesDatabase): HandleDao {
        return database.handleDao()
    }

    @Provides
    @Singleton
    fun provideAttachmentDao(database: BlueBubblesDatabase): AttachmentDao {
        return database.attachmentDao()
    }

    @Provides
    @Singleton
    fun provideLinkPreviewDao(database: BlueBubblesDatabase): LinkPreviewDao {
        return database.linkPreviewDao()
    }

    @Provides
    @Singleton
    fun provideQuickReplyTemplateDao(database: BlueBubblesDatabase): QuickReplyTemplateDao {
        return database.quickReplyTemplateDao()
    }

    @Provides
    @Singleton
    fun provideScheduledMessageDao(database: BlueBubblesDatabase): ScheduledMessageDao {
        return database.scheduledMessageDao()
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }
}
