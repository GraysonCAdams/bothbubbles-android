package com.bluebubbles.di

import android.content.Context
import androidx.room.Room
import com.bluebubbles.data.local.db.BlueBubblesDatabase
import com.bluebubbles.data.local.db.dao.AttachmentDao
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.HandleDao
import com.bluebubbles.data.local.db.dao.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

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
            .fallbackToDestructiveMigration()
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
}
