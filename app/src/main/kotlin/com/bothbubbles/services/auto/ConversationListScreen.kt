package com.bothbubbles.services.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ConversationItem
import androidx.car.app.model.CarMessage
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Template
import androidx.car.app.model.ConversationCallback
import androidx.car.app.model.ConversationCallbackDelegate
import androidx.core.graphics.drawable.IconCompat
import com.bothbubbles.R
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Android Auto screen displaying the list of recent conversations.
 * Users can browse conversations, hear messages read aloud, and reply via voice.
 */
class ConversationListScreen(
    carContext: CarContext,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val handleDao: HandleDao,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository
) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cache for conversations to avoid blocking the main thread
    @Volatile
    private var cachedConversations: List<ChatEntity> = emptyList()

    @Volatile
    private var cachedMessages: Map<String, List<MessageEntity>> = emptyMap()

    init {
        // Load initial data
        refreshData()
    }

    private fun refreshData() {
        scope.launch {
            try {
                // Get recent active chats (limit to 10 for Android Auto performance)
                val chats = chatDao.getActiveChats().first().take(MAX_CONVERSATIONS)
                cachedConversations = chats

                // Pre-fetch recent messages for each chat
                val messagesMap = mutableMapOf<String, List<MessageEntity>>()
                for (chat in chats) {
                    val messages = messageDao.observeMessagesForChat(
                        chatGuid = chat.guid,
                        limit = MAX_MESSAGES_PER_CONVERSATION,
                        offset = 0
                    ).first()
                    messagesMap[chat.guid] = messages
                }
                cachedMessages = messagesMap

                // Trigger UI refresh
                invalidate()
            } catch (e: Exception) {
                // Log error but don't crash
                android.util.Log.e(TAG, "Failed to refresh conversation data", e)
            }
        }
    }

    override fun onGetTemplate(): Template {
        val itemListBuilder = ItemList.Builder()

        // Use cached data to build the list
        for (chat in cachedConversations) {
            val conversationItem = buildConversationItem(chat)
            if (conversationItem != null) {
                itemListBuilder.addItem(conversationItem)
            }
        }

        // Show empty state if no conversations
        if (cachedConversations.isEmpty()) {
            itemListBuilder.setNoItemsMessage("No conversations yet")
        }

        return ListTemplate.Builder()
            .setTitle("Messages")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(itemListBuilder.build())
            .build()
    }

    private fun buildConversationItem(chat: ChatEntity): ConversationItem? {
        val messages = cachedMessages[chat.guid] ?: return null
        if (messages.isEmpty()) return null

        // Build CarMessage list from recent messages
        val carMessages = messages.mapNotNull { message ->
            buildCarMessage(chat, message)
        }

        if (carMessages.isEmpty()) return null

        // Get display title - either custom name or participant names
        val displayTitle = chat.displayName ?: getParticipantNames(chat.guid)

        // Create sender for the conversation
        val sender = androidx.car.app.model.CarText.Builder(displayTitle).build()

        return try {
            ConversationItem.Builder()
                .setId(chat.guid)
                .setTitle(androidx.car.app.model.CarText.Builder(displayTitle).build())
                .setIcon(createConversationIcon(chat))
                .setMessages(carMessages)
                .setGroupConversation(chat.isGroup)
                .setConversationCallback(createConversationCallback(chat))
                .build()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to build conversation item for ${chat.guid}", e)
            null
        }
    }

    private fun buildCarMessage(chat: ChatEntity, message: MessageEntity): CarMessage? {
        val text = message.text ?: return null
        if (text.isBlank()) return null

        // Determine sender
        val senderName = if (message.isFromMe) {
            "Me"
        } else {
            getSenderName(chat, message)
        }

        val sender = androidx.car.app.model.CarText.Builder(senderName).build()

        return try {
            CarMessage.Builder()
                .setSender(sender)
                .setBody(androidx.car.app.model.CarText.Builder(text).build())
                .setReceivedTimeEpochMillis(message.dateCreated)
                .setRead(message.dateRead != null || message.isFromMe)
                .build()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to build car message", e)
            null
        }
    }

    private fun getSenderName(chat: ChatEntity, message: MessageEntity): String {
        // For non-group chats, use the chat display name
        if (!chat.isGroup) {
            return chat.displayName ?: getParticipantNames(chat.guid)
        }

        // For group chats, try to get the sender's handle
        val handleId = message.handleId
        if (handleId != null) {
            val handle = runBlocking(Dispatchers.IO) {
                handleDao.getHandleById(handleId)
            }
            if (handle != null) {
                return handle.cachedDisplayName
                    ?: handle.inferredName
                    ?: handle.formattedAddress
                    ?: handle.address
            }
        }

        return "Unknown"
    }

    private fun getParticipantNames(chatGuid: String): String {
        return runBlocking(Dispatchers.IO) {
            val participants = chatDao.getParticipantsForChat(chatGuid)
            if (participants.isEmpty()) {
                "Unknown"
            } else if (participants.size == 1) {
                participants.first().cachedDisplayName
                    ?: participants.first().formattedAddress
                    ?: participants.first().address
            } else {
                participants.take(3).joinToString(", ") { handle ->
                    handle.cachedDisplayName
                        ?: handle.formattedAddress?.take(10)
                        ?: handle.address.take(10)
                }
            }
        }
    }

    private fun createConversationIcon(chat: ChatEntity): CarIcon {
        return CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.mipmap.ic_launcher)
        ).build()
    }

    private fun createConversationCallback(chat: ChatEntity): ConversationCallbackDelegate {
        return object : ConversationCallbackDelegate {
            override fun sendMessage(message: CharSequence, callback: ConversationCallback) {
                scope.launch {
                    try {
                        messageRepository.sendMessage(
                            chatGuid = chat.guid,
                            text = message.toString()
                        )
                        // Refresh data after sending
                        refreshData()
                        callback.onSuccess()
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to send message", e)
                        callback.onFailure("Failed to send message")
                    }
                }
            }

            override fun markAsRead(callback: ConversationCallback) {
                scope.launch {
                    try {
                        chatRepository.markChatAsRead(chat.guid)
                        refreshData()
                        callback.onSuccess()
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to mark as read", e)
                        callback.onFailure("Failed to mark as read")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ConversationListScreen"
        private const val MAX_CONVERSATIONS = 10
        private const val MAX_MESSAGES_PER_CONVERSATION = 5
    }
}
