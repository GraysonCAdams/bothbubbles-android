package com.bothbubbles.services.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Android Auto CarAppService entry point.
 * Provides messaging functionality in the car's infotainment display.
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
    lateinit var messageRepository: MessageRepository

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
            messageRepository = messageRepository
        )
    }
}

/**
 * Android Auto session that manages the conversation UI lifecycle.
 */
class BothBubblesAutoSession(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val handleDao: HandleDao,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository
) : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return ConversationListScreen(
            carContext = carContext,
            chatDao = chatDao,
            messageDao = messageDao,
            handleDao = handleDao,
            chatRepository = chatRepository,
            messageRepository = messageRepository
        )
    }
}
