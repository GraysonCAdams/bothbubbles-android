package com.bothbubbles.di

import com.bothbubbles.services.messaging.sender.IMessageSenderStrategy
import com.bothbubbles.services.messaging.sender.MessageSenderStrategy
import com.bothbubbles.services.messaging.sender.SmsSenderStrategy
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt module that binds message sender strategies into a Set.
 *
 * This enables the Strategy Pattern for message sending:
 * - [SmsSenderStrategy]: Handles LOCAL_SMS and LOCAL_MMS delivery modes
 * - [IMessageSenderStrategy]: Handles IMESSAGE delivery mode
 *
 * To add a new sending strategy:
 * 1. Create a class implementing [MessageSenderStrategy]
 * 2. Add a @Binds @IntoSet method here
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MessageSenderModule {

    @Binds
    @IntoSet
    abstract fun bindSmsSenderStrategy(impl: SmsSenderStrategy): MessageSenderStrategy

    @Binds
    @IntoSet
    abstract fun bindIMessageSenderStrategy(impl: IMessageSenderStrategy): MessageSenderStrategy
}
