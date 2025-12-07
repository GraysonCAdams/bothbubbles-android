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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android Auto screen displaying messages in a conversation.
 * Shows recent messages and provides actions to reply or mark as read.
 */
class ConversationDetailScreen(
    carContext: CarContext,
    private val chat: ChatEntity,
    private val messageDao: MessageDao,
    private val handleDao: HandleDao,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val syncService: SyncService? = null,
    private val onRefresh: () -> Unit
) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedMessages: List<MessageEntity> = emptyList()

    @Volatile
    private var isLoadingMessages: Boolean = true

    private val dateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    init {
        refreshMessages()
    }

    private fun refreshMessages() {
        scope.launch {
            try {
                isLoadingMessages = true
                val messages = messageDao.observeMessagesForChat(
                    chatGuid = chat.guid,
                    limit = MAX_MESSAGES,
                    offset = 0
                ).first()
                cachedMessages = messages
                isLoadingMessages = false

                // If no messages found, trigger priority sync to load them immediately
                if (messages.isEmpty() && syncService != null) {
                    android.util.Log.i(TAG, "No messages found, triggering priority sync for ${chat.guid}")
                    syncService.prioritizeChatSync(chat.guid)
                    // Re-fetch after a short delay to pick up synced messages
                    kotlinx.coroutines.delay(2000)
                    val refreshedMessages = messageDao.observeMessagesForChat(
                        chatGuid = chat.guid,
                        limit = MAX_MESSAGES,
                        offset = 0
                    ).first()
                    cachedMessages = refreshedMessages
                }

                invalidate()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to refresh messages", e)
                isLoadingMessages = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val displayTitle = chat.displayName ?: "Conversation"

        val itemListBuilder = ItemList.Builder()

        // Add messages (newest first, but displayed oldest first for reading order)
        val messagesToShow = cachedMessages.reversed()
        for (message in messagesToShow) {
            val row = buildMessageRow(message)
            if (row != null) {
                itemListBuilder.addItem(row)
            }
        }

        if (cachedMessages.isEmpty()) {
            itemListBuilder.setNoItemsMessage(
                if (isLoadingMessages) "Loading messages..." else "No messages yet"
            )
        }

        // Create action strip with reply and mark as read
        val replyAction = Action.Builder()
            .setTitle("Reply")
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_send)
                ).build()
            )
            .setOnClickListener {
                // Push to voice reply screen
                screenManager.push(
                    VoiceReplyScreen(
                        carContext = carContext,
                        chat = chat,
                        messageRepository = messageRepository,
                        onMessageSent = {
                            refreshMessages()
                            onRefresh()
                        }
                    )
                )
            }
            .build()

        val markReadAction = Action.Builder()
            .setTitle("Mark Read")
            .setOnClickListener {
                scope.launch {
                    try {
                        chatRepository.markChatAsRead(chat.guid)
                        CarToast.makeText(carContext, "Marked as read", CarToast.LENGTH_SHORT).show()
                        onRefresh()
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to mark as read", e)
                    }
                }
            }
            .build()

        return ListTemplate.Builder()
            .setTitle(displayTitle)
            .setHeaderAction(Action.BACK)
            .setSingleList(itemListBuilder.build())
            .addAction(replyAction)
            .addAction(markReadAction)
            .build()
    }

    private fun buildMessageRow(message: MessageEntity): Row? {
        val text = message.text ?: return null
        if (text.isBlank()) return null

        val senderName = if (message.isFromMe) {
            "You"
        } else {
            getSenderName(message)
        }

        val time = dateFormat.format(Date(message.dateCreated))

        return try {
            Row.Builder()
                .setTitle("$senderName - $time")
                .addText(text)
                .build()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to build message row", e)
            null
        }
    }

    private fun getSenderName(message: MessageEntity): String {
        val handleId = message.handleId ?: return ""
        val handle = runBlocking(Dispatchers.IO) {
            handleDao.getHandleById(handleId)
        }
        return handle?.cachedDisplayName
            ?: handle?.inferredName
            ?: handle?.formattedAddress
            ?: handle?.address?.let { PhoneNumberFormatter.format(it) }
            ?: ""
    }

    companion object {
        private const val TAG = "ConversationDetailScreen"
        private const val MAX_MESSAGES = 10
    }
}
