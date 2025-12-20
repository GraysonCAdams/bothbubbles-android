package com.bothbubbles.services.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.prefs.FeaturePreferences
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.sync.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android Auto root screen for viewing conversations.
 *
 * Features:
 * - Connection status indicator: Shows "(Offline)" in title when disconnected
 * - Privacy mode support via featurePreferences
 * - Read messages aloud with TTS
 * - View conversation details and message history
 *
 * NOTE: Composing new messages and replying is handled through Android Auto's
 * notification-based messaging (MessagingStyle notifications). This is the
 * required pattern per Android Auto guidelines. The templated app is for
 * viewing/browsing conversations only.
 *
 * See: https://developer.android.com/training/cars/communication/templated-messaging
 */
class MessagingRootScreen(
    carContext: CarContext,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val handleDao: HandleDao,
    private val chatRepository: ChatRepository,
    private val syncService: SyncService? = null,
    private val socketConnection: SocketConnection? = null,
    private val featurePreferences: FeaturePreferences? = null,
    private val attachmentRepository: AttachmentRepository? = null,
    private val attachmentDao: AttachmentDao? = null
) : Screen(carContext) {

    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Connection state for offline indicator
    @Volatile
    private var isConnected: Boolean = true

    init {
        // Register lifecycle observer
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                screenScope.cancel()
            }
        })

        // Observe connection state changes
        socketConnection?.let { socket ->
            screenScope.launch {
                socket.connectionState.collect { state ->
                    val newConnected = state == ConnectionState.CONNECTED
                    if (newConnected != isConnected) {
                        isConnected = newConnected
                        invalidate()
                    }
                }
            }
        }
    }

    // Content delegate for conversations list
    private val chatsContent by lazy {
        ConversationListContent(
            carContext = carContext,
            chatDao = chatDao,
            messageDao = messageDao,
            handleDao = handleDao,
            chatRepository = chatRepository,
            syncService = syncService,
            featurePreferences = featurePreferences,
            attachmentRepository = attachmentRepository,
            attachmentDao = attachmentDao,
            screenManager = screenManager,
            onInvalidate = { invalidate() },
            isConnected = { isConnected }
        )
    }

    override fun onGetTemplate(): Template {
        return chatsContent.buildContent()
    }

    /**
     * Checks if the socket connection is currently connected.
     * Used by child screens to determine if messages can be sent immediately.
     */
    fun isSocketConnected(): Boolean = isConnected
}
