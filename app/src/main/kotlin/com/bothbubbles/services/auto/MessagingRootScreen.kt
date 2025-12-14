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
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.messaging.MessageSendingService
import com.bothbubbles.services.sync.SyncService

/**
 * Android Auto root screen using TabTemplate for improved navigation.
 *
 * Provides two tabs:
 * - Chats: Shows recent conversations (ConversationListContent)
 * - Compose: Shows contact list for new messages (ComposeContactsContent)
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
    private val syncService: SyncService? = null
) : Screen(carContext) {

    // Current active tab
    @Volatile
    private var activeTabId: String = TAB_CHATS

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
            screenManager = screenManager,
            onInvalidate = { invalidate() },
            onMessageSent = { chatsContent.refreshData() }
        )
    }

    override fun onGetTemplate(): Template {
        val chatsTab = Tab.Builder()
            .setTitle("Chats")
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

        val activeContent = when (activeTabId) {
            TAB_COMPOSE -> composeContent.buildContent()
            else -> chatsContent.buildContent()
        }

        return TabTemplate.Builder(object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {
                activeTabId = tabContentId
                invalidate()
            }
        })
            .setHeaderAction(Action.APP_ICON)
            .addTab(chatsTab)
            .addTab(composeTab)
            .setTabContents(
                TabContents.Builder(activeContent).build()
            )
            .setActiveTabContentId(activeTabId)
            .build()
    }

    companion object {
        private const val TAB_CHATS = "tab_chats"
        private const val TAB_COMPOSE = "tab_compose"
    }
}
