package com.bothbubbles.services.auto

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Android Auto screen displaying the list of recent conversations.
 * Users can browse conversations and tap to view details or reply.
 *
 * Shows ALL recent conversations (not just ones with new messages since driving started)
 * sorted by recency with unread and pinned conversations prioritized.
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

    // Screen-local scope that follows screen lifecycle
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cache for conversations to avoid blocking the main thread
    @Volatile
    private var cachedConversations: List<ChatEntity> = emptyList()

    @Volatile
    private var cachedMessages: Map<String, MessageEntity?> = emptyMap()

    // Pagination state
    @Volatile
    private var currentPage = 0

    @Volatile
    private var hasMoreConversations = true

    @Volatile
    private var isLoading = false

    // Caches to avoid blocking calls during UI rendering
    private val senderNameCache = mutableMapOf<Long, String>()
    private val participantNameCache = mutableMapOf<String, String>()

    init {
        // Register lifecycle observer to cancel scope when screen is destroyed
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                screenScope.cancel()
            }
        })
        refreshData()
    }

    private fun refreshData() {
        if (isLoading) return
        isLoading = true

        screenScope.launch {
            try {
                // Get ALL active chats from database - these are conversations the user has
                // regardless of when the last message was received
                val chats = chatDao.getActiveChats().first()

                android.util.Log.d(TAG, "Found ${chats.size} total active chats")

                // Sort: unread+pinned first, then unread, then pinned, then by latest message date
                val sortedChats = chats.sortedWith(
                    compareByDescending<ChatEntity> { it.hasUnreadMessage && it.isPinned }
                        .thenByDescending { it.hasUnreadMessage }
                        .thenByDescending { it.isPinned }
                        .thenByDescending { it.lastMessageDate ?: 0L }
                )

                // Take up to the current page limit
                val displayCount = minOf((currentPage + 1) * PAGE_SIZE, sortedChats.size)
                val chatsToDisplay = sortedChats.take(displayCount)
                hasMoreConversations = displayCount < sortedChats.size

                cachedConversations = chatsToDisplay

                // Pre-fetch latest message for each chat using batch query for efficiency
                val chatGuids = chatsToDisplay.map { it.guid }
                val latestMessages = messageDao.getLatestMessagesForChats(chatGuids)
                val messagesMap = latestMessages.associateBy { it.chatGuid }
                cachedMessages = messagesMap

                // Pre-fetch sender names for all latest messages (batch query for efficiency)
                val handleIds = latestMessages.mapNotNull { it.handleId }.distinct()
                if (handleIds.isNotEmpty()) {
                    val handles = handleDao.getHandlesByIds(handleIds)
                    handles.forEach { handle ->
                        val displayName = handle.cachedDisplayName
                            ?: handle.inferredName
                            ?: handle.formattedAddress?.take(10)
                            ?: ""
                        senderNameCache[handle.id] = displayName
                    }
                }

                // Pre-fetch participant names for all chats
                for (chat in chatsToDisplay) {
                    val participants = chatDao.getParticipantsForChat(chat.guid)
                    val participantNames = when {
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
                    participantNameCache[chat.guid] = participantNames
                }

                android.util.Log.d(TAG, "Displaying ${chatsToDisplay.size} chats, hasMore=$hasMoreConversations")

                isLoading = false
                invalidate()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to refresh conversation data", e)
                isLoading = false
            }
        }
    }

    private fun loadMoreConversations() {
        if (!hasMoreConversations || isLoading) return
        currentPage++
        android.util.Log.d(TAG, "Loading more conversations, page=$currentPage")
        refreshData()
    }

    override fun onGetTemplate(): Template {
        val itemListBuilder = ItemList.Builder()

        for (chat in cachedConversations) {
            val row = buildConversationRow(chat)
            if (row != null) {
                itemListBuilder.addItem(row)
            }
        }

        // Add "Load More" row if there are more conversations
        if (hasMoreConversations && cachedConversations.isNotEmpty()) {
            val loadMoreRow = Row.Builder()
                .setTitle("Load More Conversations...")
                .setOnClickListener { loadMoreConversations() }
                .build()
            itemListBuilder.addItem(loadMoreRow)
        }

        if (cachedConversations.isEmpty()) {
            itemListBuilder.setNoItemsMessage(
                if (isLoading) "Loading conversations..." else "No conversations yet"
            )
        }

        // Create compose action (FAB-style) for new message
        val composeAction = Action.Builder()
            .setTitle("New")
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_edit)
                )
                    .setTint(CarColor.createCustom(0xFF2196F3.toInt(), 0xFF2196F3.toInt())) // Blue tint
                    .build()
            )
            .setOnClickListener {
                // Navigate to compose new message screen
                screenManager.push(
                    ComposeMessageScreen(
                        carContext = carContext,
                        chatDao = chatDao,
                        handleDao = handleDao,
                        messageRepository = messageRepository,
                        onMessageSent = { refreshData() }
                    )
                )
            }
            .build()

        val actionStrip = ActionStrip.Builder()
            .addAction(composeAction)
            .build()

        return ListTemplate.Builder()
            .setTitle("Messages")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actionStrip)
            .setSingleList(itemListBuilder.build())
            .build()
    }

    private fun buildConversationRow(chat: ChatEntity): Row? {
        val latestMessage = cachedMessages[chat.guid]
        val displayTitle = chat.displayName ?: getParticipantNames(chat)

        // Build informative message preview
        val messagePreview = buildMessagePreview(latestMessage, chat)

        // Build unread indicator for title
        val title = if (chat.hasUnreadMessage) {
            "● $displayTitle"
        } else {
            displayTitle
        }

        return try {
            Row.Builder()
                .setTitle(title)
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

    private fun buildMessagePreview(message: MessageEntity?, chat: ChatEntity): String {
        if (message == null) return "No messages"

        val text = message.text?.take(50) ?: ""
        val hasAttachment = message.hasAttachments

        // Build preview with sender prefix
        // Group chats have displayName set (user named it or system generated)
        // 1:1 chats typically don't have displayName (we show contact name instead)
        val isGroupChat = chat.displayName != null
        val senderPrefix = when {
            message.isFromMe -> "You: "
            isGroupChat -> {
                val senderName = getSenderNameForMessage(message)
                if (senderName.isNotBlank()) "$senderName: " else ""
            }
            else -> ""
        }

        val content = when {
            text.isNotBlank() -> text
            hasAttachment -> "📎 Attachment"
            else -> "No content"
        }

        return senderPrefix + content
    }

    private fun getSenderNameForMessage(message: MessageEntity): String {
        val handleId = message.handleId ?: return ""
        // Use pre-fetched cache to avoid blocking calls
        return senderNameCache[handleId] ?: ""
    }

    private fun getParticipantNames(chat: ChatEntity): String {
        // Use pre-fetched cache to avoid blocking calls
        return participantNameCache[chat.guid] ?: ""
    }

    companion object {
        private const val TAG = "ConversationListScreen"
        private const val PAGE_SIZE = 15  // Show 15 conversations per page for better UX
    }
}
