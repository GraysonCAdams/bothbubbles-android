package com.bothbubbles.services.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.prefs.FeaturePreferences
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.messaging.MessageSendingService
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.sync.SyncService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Android Auto CarAppService entry point.
 * Provides messaging functionality in the car's infotainment display.
 *
 * Uses TabTemplate-based navigation (MessagingRootScreen) for:
 * - Reduced tap count for compose flow (from 2 to 1)
 * - Preserved scroll position when switching tabs
 * - Material Design 3 alignment
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
    lateinit var messageSendingService: MessageSendingService

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
        // Allow all hosts in debug builds, only known hosts in release
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        return BothBubblesAutoSession(
            chatDao = chatDao,
            messageDao = messageDao,
            handleDao = handleDao,
            chatRepository = chatRepository,
            messageSendingService = messageSendingService,
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
 * Creates MessagingRootScreen which uses TabTemplate for improved navigation:
 * - "Chats" tab: Recent conversations with ConversationItem API
 * - "Compose" tab: Contact list for new messages
 */
class BothBubblesAutoSession(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val handleDao: HandleDao,
    private val chatRepository: ChatRepository,
    private val messageSendingService: MessageSendingService,
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
            messageSendingService = messageSendingService,
            syncService = syncService,
            socketConnection = socketConnection,
            featurePreferences = featurePreferences,
            attachmentRepository = attachmentRepository,
            attachmentDao = attachmentDao
        )
    }
}
