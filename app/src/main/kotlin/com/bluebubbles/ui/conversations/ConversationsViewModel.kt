package com.bluebubbles.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.local.db.dao.MessageDao
import com.bluebubbles.data.local.db.entity.ChatEntity
import com.bluebubbles.data.repository.ChatRepository
import com.bluebubbles.services.socket.ConnectionState
import com.bluebubbles.services.socket.SocketEvent
import com.bluebubbles.services.socket.SocketService
import com.bluebubbles.services.sync.SyncService
import com.bluebubbles.services.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val messageDao: MessageDao,
    private val socketService: SocketService,
    private val syncService: SyncService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _typingChats = MutableStateFlow<Set<String>>(emptySet())

    init {
        loadConversations()
        observeConnectionState()
        observeTypingIndicators()
        observeSyncState()
    }

    private fun loadConversations() {
        viewModelScope.launch {
            combine(
                chatRepository.observeAllChats(),
                _searchQuery,
                _typingChats
            ) { chats, query, typingChats ->
                val filtered = if (query.isBlank()) {
                    chats
                } else {
                    chats.filter { chat ->
                        chat.displayName?.contains(query, ignoreCase = true) == true ||
                        chat.chatIdentifier?.contains(query, ignoreCase = true) == true
                    }
                }
                // Sort: pinned first, then by last message time
                filtered
                    .sortedWith(
                        compareByDescending<ChatEntity> { it.isPinned }
                            .thenByDescending { it.lastMessageDate ?: 0L }
                    )
                    .map { it.toUiModel(typingChats) }
            }.collect { conversations ->
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        conversations = conversations
                    )
                }
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            socketService.connectionState.collect { state ->
                _uiState.update {
                    it.copy(isConnected = state == ConnectionState.CONNECTED)
                }
            }
        }
    }

    private fun observeTypingIndicators() {
        viewModelScope.launch {
            socketService.events
                .filterIsInstance<SocketEvent.TypingIndicator>()
                .collect { event ->
                    _typingChats.update { chats ->
                        if (event.isTyping) {
                            chats + event.chatGuid
                        } else {
                            chats - event.chatGuid
                        }
                    }
                }
        }
    }

    private fun observeSyncState() {
        viewModelScope.launch {
            syncService.syncState.collect { state ->
                _uiState.update {
                    it.copy(
                        isSyncing = state is SyncState.Syncing,
                        syncProgress = if (state is SyncState.Syncing) state.progress else null
                    )
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            syncService.performIncrementalSync()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun togglePin(chatGuid: String) {
        viewModelScope.launch {
            val chat = chatRepository.getChat(chatGuid)
            chat?.let {
                chatRepository.setPinned(chatGuid, !it.isPinned)
            }
        }
    }

    fun toggleMute(chatGuid: String) {
        viewModelScope.launch {
            val chat = chatRepository.getChat(chatGuid)
            chat?.let {
                val currentlyMuted = it.muteType != null
                chatRepository.setMuted(chatGuid, !currentlyMuted)
            }
        }
    }

    fun archiveChat(chatGuid: String) {
        viewModelScope.launch {
            chatRepository.setArchived(chatGuid, true)
        }
    }

    fun deleteChat(chatGuid: String) {
        viewModelScope.launch {
            chatRepository.deleteChat(chatGuid)
        }
    }

    fun markAsRead(chatGuid: String) {
        viewModelScope.launch {
            chatRepository.markChatAsRead(chatGuid)
        }
    }

    private suspend fun ChatEntity.toUiModel(typingChats: Set<String>): ConversationUiModel {
        val lastMessage = messageDao.getLatestMessageForChat(guid)
        val messageText = lastMessage?.text ?: lastMessageText ?: ""
        val isFromMe = lastMessage?.isFromMe ?: false

        // Determine message type from attachments or content
        val messageType = when {
            lastMessage?.hasAttachments == true -> {
                // Check attachment mime type if available
                val mimeType = lastMessage.associatedMessageType ?: ""
                when {
                    mimeType.startsWith("image/") -> MessageType.IMAGE
                    mimeType.startsWith("video/") -> MessageType.VIDEO
                    mimeType.startsWith("audio/") -> MessageType.AUDIO
                    mimeType.isNotEmpty() -> MessageType.ATTACHMENT
                    else -> MessageType.TEXT
                }
            }
            messageText.contains("http://") || messageText.contains("https://") -> MessageType.LINK
            else -> MessageType.TEXT
        }

        return ConversationUiModel(
            guid = guid,
            displayName = displayName ?: chatIdentifier ?: "Unknown",
            avatarPath = null, // TODO: Get from handles
            lastMessageText = messageText,
            lastMessageTime = formatRelativeTime(lastMessage?.dateCreated ?: lastMessageDate ?: 0L),
            lastMessageTimestamp = lastMessage?.dateCreated ?: lastMessageDate ?: 0L,
            unreadCount = unreadCount,
            isPinned = isPinned,
            isMuted = muteType != null,
            isGroup = isGroup,
            isTyping = guid in typingChats,
            isFromMe = isFromMe,
            hasDraft = false, // TODO: Implement draft storage
            draftText = null,
            lastMessageType = messageType,
            participantNames = emptyList() // TODO: Get from handles
        )
    }

    private fun formatRelativeTime(timestamp: Long): String {
        if (timestamp == 0L) return ""

        val now = System.currentTimeMillis()
        val diff = now - timestamp

        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 6 -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
            days > 0 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "now"
        }
    }
}

data class ConversationsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isSyncing: Boolean = false,
    val syncProgress: Float? = null,
    val isConnected: Boolean = false,
    val conversations: List<ConversationUiModel> = emptyList(),
    val searchQuery: String = "",
    val error: String? = null
)

data class ConversationUiModel(
    val guid: String,
    val displayName: String,
    val avatarPath: String?,
    val lastMessageText: String,
    val lastMessageTime: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val isGroup: Boolean,
    val isTyping: Boolean,
    val isFromMe: Boolean = false,
    val hasDraft: Boolean = false,
    val draftText: String? = null,
    val lastMessageType: MessageType = MessageType.TEXT,
    val participantNames: List<String> = emptyList()
)

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    LINK,
    ATTACHMENT
}
