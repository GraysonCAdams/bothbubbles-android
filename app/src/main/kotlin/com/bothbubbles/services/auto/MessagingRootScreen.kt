package com.bothbubbles.services.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.prefs.FeaturePreferences
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.messaging.MessageSendingService
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.sync.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Android Auto root screen using TabTemplate for improved navigation.
 *
 * Provides two tabs:
 * - Chats: Shows recent conversations (ConversationListContent)
 * - Compose: Shows contact list for new messages (ComposeContactsContent)
 *
 * Features:
 * - Connection status indicator: Shows "(Offline)" when disconnected
 * - Privacy mode support via featurePreferences
 *
 * This reduces tap count for compose flow from 2 to 1 (key Android Auto UX metric).
 */
class MessagingRootScreen(
    carContext: CarContext,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val handleDao: HandleDao,
    private val chatRepository: ChatRepository,
    private val messageSendingService: MessageSendingService,
    private val syncService: SyncService? = null,
    private val socketConnection: SocketConnection? = null,
    private val featurePreferences: FeaturePreferences? = null,
    private val attachmentRepository: AttachmentRepository? = null,
    private val attachmentDao: AttachmentDao? = null
) : Screen(carContext) {

    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Current active tab
    @Volatile
    private var activeTabId: String = TAB_CHATS

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

    // Content delegates for each tab
    private val chatsContent by lazy {
        ConversationListContent(
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
            attachmentDao = attachmentDao,
            screenManager = screenManager,
            onInvalidate = { invalidate() }
        )
    }

    private val composeContent by lazy {
        ComposeContactsContent(
            carContext = carContext,
            chatDao = chatDao,
            handleDao = handleDao,
            messageSendingService = messageSendingService,
            socketConnection = socketConnection,
            screenManager = screenManager,
            onInvalidate = { invalidate() },
            onMessageSent = { chatsContent.refreshData() }
        )
    }

    override fun onGetTemplate(): Template {
        // Build tab title with offline indicator
        val chatsTabTitle = if (isConnected) "Chats" else "Chats (Offline)"

        val chatsTab = Tab.Builder()
            .setTitle(chatsTabTitle)
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_agenda)
                ).build()
            )
            .setContentId(TAB_CHATS)
            .build()

        val composeTab = Tab.Builder()
            .setTitle("Compose")
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_edit)
                ).build()
            )
            .setContentId(TAB_COMPOSE)
            .build()

        val searchTab = Tab.Builder()
            .setTitle("Search")
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_search)
                ).build()
            )
            .setContentId(TAB_SEARCH)
            .build()

        val activeContent = when (activeTabId) {
            TAB_COMPOSE -> composeContent.buildContent()
            TAB_SEARCH -> buildSearchContent()
            else -> chatsContent.buildContent()
        }

        return TabTemplate.Builder(object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {
                if (tabContentId == TAB_SEARCH) {
                    // Navigate to voice search screen
                    navigateToVoiceSearch()
                } else {
                    activeTabId = tabContentId
                    invalidate()
                }
            }
        })
            .setHeaderAction(Action.APP_ICON)
            .addTab(chatsTab)
            .addTab(composeTab)
            .addTab(searchTab)
            .setTabContents(
                TabContents.Builder(activeContent).build()
            )
            .setActiveTabContentId(activeTabId)
            .build()
    }

    private fun buildSearchContent(): Template {
        // Return placeholder - actual search is handled by VoiceSearchScreen
        return androidx.car.app.model.ListTemplate.Builder()
            .setTitle("Search")
            .setSingleList(
                androidx.car.app.model.ItemList.Builder()
                    .setNoItemsMessage("Tap to search contacts...")
                    .build()
            )
            .build()
    }

    private fun navigateToVoiceSearch() {
        screenManager.push(
            VoiceSearchScreen(
                carContext = carContext,
                chatDao = chatDao,
                handleDao = handleDao,
                messageSendingService = messageSendingService,
                socketConnection = socketConnection,
                onContactSelected = { chat ->
                    screenManager.push(
                        VoiceReplyScreen(
                            carContext = carContext,
                            chat = chat,
                            messageSendingService = messageSendingService,
                            onMessageSent = { chatsContent.refreshData() },
                            socketConnection = socketConnection
                        )
                    )
                }
            )
        )
    }

    /**
     * Checks if the socket connection is currently connected.
     * Used by child screens to determine if messages can be sent immediately.
     */
    fun isSocketConnected(): Boolean = isConnected

    companion object {
        private const val TAB_CHATS = "tab_chats"
        private const val TAB_COMPOSE = "tab_compose"
        private const val TAB_SEARCH = "tab_search"
    }
}
