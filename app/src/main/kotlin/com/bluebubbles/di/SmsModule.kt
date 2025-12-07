package com.bluebubbles.di

import android.content.Context
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.HandleDao
import com.bluebubbles.data.local.db.dao.MessageDao
import com.bluebubbles.data.repository.SmsRepository
import com.bluebubbles.services.notifications.NotificationService
import com.bluebubbles.services.sms.*
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

    @Provides
    @Singleton
    fun provideMmsSendService(
        @ApplicationContext context: Context,
        messageDao: MessageDao,
        smsPermissionHelper: SmsPermissionHelper
    ): MmsSendService {
        return MmsSendService(context, messageDao, smsPermissionHelper)
    }

    @Provides
    @Singleton
    fun provideSmsContentObserver(
        @ApplicationContext context: Context,
        smsContentProvider: SmsContentProvider,
        chatDao: ChatDao,
        messageDao: MessageDao,
        notificationService: NotificationService
    ): SmsContentObserver {
        return SmsContentObserver(
            context,
            smsContentProvider,
            chatDao,
            messageDao,
            notificationService
        )
    }

    @Provides
    @Singleton
    fun provideSmsRepository(
        @ApplicationContext context: Context,
        chatDao: ChatDao,
        handleDao: HandleDao,
        messageDao: MessageDao,
        smsContentProvider: SmsContentProvider,
        smsSendService: SmsSendService,
        mmsSendService: MmsSendService,
        smsContentObserver: SmsContentObserver
    ): SmsRepository {
        return SmsRepository(
            context,
            chatDao,
            handleDao,
            messageDao,
            smsContentProvider,
            smsSendService,
            mmsSendService,
            smsContentObserver
        )
    }
}
