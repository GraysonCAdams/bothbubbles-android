package com.bothbubbles.di

import android.content.Context
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.sms.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SmsModule {

    @Provides
    @Singleton
    fun provideSmsContentProvider(
        @ApplicationContext context: Context
    ): SmsContentProvider {
        return SmsContentProvider(context)
    }

    @Provides
    @Singleton
    fun provideSmsSendService(
        @ApplicationContext context: Context,
        messageDao: MessageDao,
        smsPermissionHelper: SmsPermissionHelper
    ): SmsSendService {
        return SmsSendService(context, messageDao, smsPermissionHelper)
    }

    // MmsSendService is auto-wired by Hilt (has @Inject constructor)
    // SmsContentObserver is auto-wired by Hilt (has @Inject constructor)

    @Provides
    @Singleton
    fun provideSmsRepository(
        @ApplicationContext context: Context,
        chatDao: ChatDao,
        handleDao: HandleDao,
        messageDao: MessageDao,
        unifiedChatGroupDao: UnifiedChatGroupDao,
        smsContentProvider: SmsContentProvider,
        smsSendService: SmsSendService,
        mmsSendService: MmsSendService,
        smsContentObserver: SmsContentObserver,
        androidContactsService: AndroidContactsService
    ): SmsRepository {
        return SmsRepository(
            context,
            chatDao,
            handleDao,
            messageDao,
            unifiedChatGroupDao,
            smsContentProvider,
            smsSendService,
            mmsSendService,
            smsContentObserver,
            androidContactsService
        )
    }
}
