package com.bothbubbles.services.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import com.bothbubbles.R
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.prefs.FeaturePreferences
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.sync.SyncService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Android Auto CarAppService entry point.
 * Provides messaging functionality in the car's infotainment display.
 *
 * Features:
 * - View recent conversations
 * - Read message history with TTS
 * - Privacy mode support
 *
 * NOTE: Composing and replying to messages is handled via Android Auto's
 * notification-based messaging (MessagingStyle notifications). This is the
 * required pattern per Android Auto guidelines.
 *
 * See: https://developer.android.com/training/cars/communication/templated-messaging
 */
@AndroidEntryPoint
class BothBubblesCarAppService : CarAppService() {

    @Inject
    lateinit var chatDao: ChatDao

    @Inject
    lateinit var messageDao: MessageDao

    @Inject
    lateinit var handleDao: HandleDao

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var syncService: SyncService

    @Inject
    lateinit var socketConnection: SocketConnection

    @Inject
    lateinit var featurePreferences: FeaturePreferences

    @Inject
    lateinit var attachmentRepository: AttachmentRepository

    @Inject
    lateinit var attachmentDao: AttachmentDao

    override fun createHostValidator(): HostValidator {
        // Allow all hosts in debug builds, only known Google hosts in release
        // See: https://developer.android.com/training/cars/testing#create-host
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(R.array.hosts_allowlist)
                .build()
        }
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        return BothBubblesAutoSession(
            chatDao = chatDao,
            messageDao = messageDao,
            handleDao = handleDao,
            chatRepository = chatRepository,
            syncService = syncService,
            socketConnection = socketConnection,
            featurePreferences = featurePreferences,
            attachmentRepository = attachmentRepository,
            attachmentDao = attachmentDao
        )
    }
}

/**
 * Android Auto session that manages the conversation UI lifecycle.
 *
 * Creates MessagingRootScreen for viewing conversations and message history.
 * Reply functionality is handled through notification-based messaging.
 */
class BothBubblesAutoSession(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val handleDao: HandleDao,
    private val chatRepository: ChatRepository,
    private val syncService: SyncService,
    private val socketConnection: SocketConnection,
    private val featurePreferences: FeaturePreferences,
    private val attachmentRepository: AttachmentRepository,
    private val attachmentDao: AttachmentDao
) : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return MessagingRootScreen(
            carContext = carContext,
            chatDao = chatDao,
            messageDao = messageDao,
            handleDao = handleDao,
            chatRepository = chatRepository,
            syncService = syncService,
            socketConnection = socketConnection,
            featurePreferences = featurePreferences,
            attachmentRepository = attachmentRepository,
            attachmentDao = attachmentDao
        )
    }
}
