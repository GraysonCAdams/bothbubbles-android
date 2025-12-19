package com.bothbubbles.di

import com.bothbubbles.core.data.DeveloperModeTracker
import com.bothbubbles.core.data.ServerConnectionProvider
import com.bothbubbles.core.data.SettingsProvider
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.developer.DeveloperEventLog
import com.bothbubbles.data.repository.PendingMessageRepository
import com.bothbubbles.data.repository.PendingMessageSource
import com.bothbubbles.services.contacts.ContactBlocker
import com.bothbubbles.services.contacts.ContactBlockingService
import com.bothbubbles.services.contacts.VCardExporter
import com.bothbubbles.services.contacts.VCardService
import com.bothbubbles.services.export.SmsRestoreService
import com.bothbubbles.services.export.SmsRestorer
import com.bothbubbles.services.life360.Life360Service
import com.bothbubbles.services.life360.Life360ServiceImpl
import com.bothbubbles.services.messaging.IncomingMessageHandler
import com.bothbubbles.services.messaging.IncomingMessageProcessor
import com.bothbubbles.services.messaging.MessageSender
import com.bothbubbles.services.messaging.MessageSendingService
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.notifications.Notifier
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.services.sound.SoundManager
import com.bothbubbles.services.sound.SoundPlayer
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
     * Binds [SocketService] to the [ServerConnectionProvider] interface.
     * Feature modules depend on ServerConnectionProvider for connection state access.
     */
    @Binds
    @Singleton
    abstract fun bindServerConnectionProvider(
        socketService: SocketService
    ): ServerConnectionProvider

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

    /**
     * Binds [SoundManager] to the [SoundPlayer] interface.
     * Use SoundPlayer in consumers for testability.
     */
    @Binds
    @Singleton
    abstract fun bindSoundPlayer(
        soundManager: SoundManager
    ): SoundPlayer

    /**
     * Binds [PendingMessageRepository] to the [PendingMessageSource] interface.
     * Use PendingMessageSource in ViewModels for testability.
     */
    @Binds
    @Singleton
    abstract fun bindPendingMessageSource(
        pendingMessageRepository: PendingMessageRepository
    ): PendingMessageSource

    /**
     * Binds [VCardService] to the [VCardExporter] interface.
     * Use VCardExporter in consumers for testability.
     */
    @Binds
    @Singleton
    abstract fun bindVCardExporter(
        vCardService: VCardService
    ): VCardExporter

    /**
     * Binds [ContactBlockingService] to the [ContactBlocker] interface.
     * Use ContactBlocker in consumers for testability.
     */
    @Binds
    @Singleton
    abstract fun bindContactBlocker(
        contactBlockingService: ContactBlockingService
    ): ContactBlocker

    /**
     * Binds [SmsRestoreService] to the [SmsRestorer] interface.
     * Use SmsRestorer in consumers for testability.
     */
    @Binds
    @Singleton
    abstract fun bindSmsRestorer(
        smsRestoreService: SmsRestoreService
    ): SmsRestorer

    /**
     * Binds [SettingsDataStore] to the [SettingsProvider] interface.
     * Feature modules depend on SettingsProvider for settings access.
     */
    @Binds
    @Singleton
    abstract fun bindSettingsProvider(
        settingsDataStore: SettingsDataStore
    ): SettingsProvider

    /**
     * Binds [DeveloperEventLog] to the [DeveloperModeTracker] interface.
     * Feature modules depend on DeveloperModeTracker for developer mode control.
     */
    @Binds
    @Singleton
    abstract fun bindDeveloperModeTracker(
        developerEventLog: DeveloperEventLog
    ): DeveloperModeTracker

    /**
     * Binds [Life360ServiceImpl] to the [Life360Service] interface.
     * Use Life360Service in consumers for testability.
     */
    @Binds
    @Singleton
    abstract fun bindLife360Service(
        life360ServiceImpl: Life360ServiceImpl
    ): Life360Service
}
