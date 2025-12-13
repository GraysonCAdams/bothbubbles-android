package com.bothbubbles.di

import com.bothbubbles.services.messaging.IncomingMessageHandler
import com.bothbubbles.services.messaging.IncomingMessageProcessor
import com.bothbubbles.services.messaging.MessageSender
import com.bothbubbles.services.messaging.MessageSendingService
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.notifications.Notifier
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.socket.SocketService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that binds service interfaces to their concrete implementations.
 *
 * This enables dependency inversion - consumers depend on interfaces rather than
 * concrete classes, making them easier to test with mock/fake implementations.
 *
 * Example usage in tests:
 * ```kotlin
 * class FakeMessageSender : MessageSender { ... }
 *
 * val viewModel = ChatViewModel(
 *     messageSender = FakeMessageSender(),
 *     ...
 * )
 * ```
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {

    /**
     * Binds [MessageSendingService] to the [MessageSender] interface.
     * Use MessageSender in ViewModels and other consumers for testability.
     */
    @Binds
    @Singleton
    abstract fun bindMessageSender(
        messageSendingService: MessageSendingService
    ): MessageSender

    /**
     * Binds [SocketService] to the [SocketConnection] interface.
     * Use SocketConnection in consumers for testability.
     */
    @Binds
    @Singleton
    abstract fun bindSocketConnection(
        socketService: SocketService
    ): SocketConnection

    /**
     * Binds [IncomingMessageHandler] to the [IncomingMessageProcessor] interface.
     * Use IncomingMessageProcessor in consumers for testability.
     */
    @Binds
    @Singleton
    abstract fun bindIncomingMessageProcessor(
        incomingMessageHandler: IncomingMessageHandler
    ): IncomingMessageProcessor

    /**
     * Binds [NotificationService] to the [Notifier] interface.
     * Use Notifier in consumers for testability.
     */
    @Binds
    @Singleton
    abstract fun bindNotifier(
        notificationService: NotificationService
    ): Notifier
}
