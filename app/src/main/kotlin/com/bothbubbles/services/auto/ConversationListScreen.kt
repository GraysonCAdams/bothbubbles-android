package com.bothbubbles.services.auto

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.bothbubbles.R
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Android Auto screen displaying the list of recent conversations.
 * Users can browse conversations and tap to view details or reply.
 */
class ConversationListScreen(
    carContext: CarContext,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val handleDao: HandleDao,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val syncService: SyncService? = null
) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cache for conversations to avoid blocking the main thread
    @Volatile
    private var cachedConversations: List<ChatEntity> = emptyList()

    @Volatile
    private var cachedMessages: Map<String, MessageEntity?> = emptyMap()

    init {
        refreshData()
    }

    private fun refreshData() {
        scope.launch {
            try {
                // Get recent active chats (limit to 10 for Android Auto performance)
                val chats = chatDao.getActiveChats().first()

                // Sort: unread+pinned first, then unread, then pinned, then by date
                val sortedChats = chats.sortedWith(
                    compareByDescending<ChatEntity> { it.hasUnreadMessage && it.isPinned }
                        .thenByDescending { it.hasUnreadMessage }
                        .thenByDescending { it.isPinned }
                        .thenByDescending { it.lastMessageDate ?: 0L }
                ).take(MAX_CONVERSATIONS)

                cachedConversations = sortedChats

                // Pre-fetch latest message for each chat
                val messagesMap = mutableMapOf<String, MessageEntity?>()
                for (chat in sortedChats) {
                    val messages = messageDao.observeMessagesForChat(
                        chatGuid = chat.guid,
                        limit = 1,
                        offset = 0
                    ).first()
                    messagesMap[chat.guid] = messages.firstOrNull()
                }
                cachedMessages = messagesMap

                // Trigger UI refresh
                invalidate()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to refresh conversation data", e)
            }
        }
    }

    override fun onGetTemplate(): Template {
        val itemListBuilder = ItemList.Builder()

        for (chat in cachedConversations) {
            val row = buildConversationRow(chat)
            if (row != null) {
                itemListBuilder.addItem(row)
            }
        }

        if (cachedConversations.isEmpty()) {
            itemListBuilder.setNoItemsMessage("No conversations yet")
        }

        return ListTemplate.Builder()
            .setTitle("Messages")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(itemListBuilder.build())
            .build()
    }

    private fun buildConversationRow(chat: ChatEntity): Row? {
        val latestMessage = cachedMessages[chat.guid]
        val displayTitle = chat.displayName ?: getParticipantNames(chat)
        val messagePreview = latestMessage?.text?.take(50) ?: "No messages"

        return try {
            Row.Builder()
                .setTitle(displayTitle)
                .addText(messagePreview)
                .setImage(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, R.mipmap.ic_launcher)
                    ).build()
                )
                .setOnClickListener {
                    // Navigate to conversation detail screen
                    screenManager.push(
                        ConversationDetailScreen(
                            carContext = carContext,
                            chat = chat,
                            messageDao = messageDao,
                            handleDao = handleDao,
                            chatRepository = chatRepository,
                            messageRepository = messageRepository,
                            syncService = syncService,
                            onRefresh = { refreshData() }
                        )
                    )
                }
                .build()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to build row for ${chat.guid}", e)
            null
        }
    }

    private fun getParticipantNames(chat: ChatEntity): String {
        return runBlocking(Dispatchers.IO) {
            val participants = chatDao.getParticipantsForChat(chat.guid)
            when {
                participants.isEmpty() -> chat.chatIdentifier?.let { PhoneNumberFormatter.format(it) } ?: ""
                participants.size == 1 -> {
                    participants.first().cachedDisplayName
                        ?: participants.first().formattedAddress
                        ?: participants.first().address
                }
                else -> {
                    participants.take(3).joinToString(", ") { handle ->
                        handle.cachedDisplayName
                            ?: handle.formattedAddress?.take(10)
                            ?: handle.address.take(10)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ConversationListScreen"
        private const val MAX_CONVERSATIONS = 10
    }
}
