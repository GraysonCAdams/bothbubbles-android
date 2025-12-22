package com.bothbubbles.ui.settings.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.core.model.entity.UnifiedChatEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BubbleChatSelectorViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val unifiedChatDao: UnifiedChatDao,
    private val chatDao: ChatDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(BubbleChatSelectorUiState())
    val uiState: StateFlow<BubbleChatSelectorUiState> = _uiState.asStateFlow()

    init {
        observeConversations()
    }

    private fun observeConversations() {
        viewModelScope.launch {
            combine(
                unifiedChatDao.observeActiveChats(),
                chatDao.observeGroupChats(),
                settingsDataStore.selectedBubbleChats
            ) { unifiedChats, groupChats, selectedChats ->
                Triple(unifiedChats, groupChats, selectedChats)
            }.collect { (unifiedChats, groupChats, selectedChats) ->
                val conversations = buildConversationList(unifiedChats, groupChats, selectedChats)
                _uiState.update {
                    it.copy(
                        conversations = conversations,
                        selectedChatGuids = selectedChats,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun buildConversationList(
        unifiedChats: List<UnifiedChatEntity>,
        groupChats: List<ChatEntity>,
        selectedChats: Set<String>
    ): List<SelectableConversation> {
        val conversations = mutableListOf<SelectableConversation>()

        // Add unified chats (1:1 chats)
        for (chat in unifiedChats) {
            conversations.add(
                SelectableConversation(
                    chatGuid = chat.sourceId,
                    displayName = chat.displayName ?: chat.normalizedAddress,
                    isGroup = false,
                    isSelected = selectedChats.contains(chat.sourceId),
                    latestMessageDate = chat.latestMessageDate ?: 0L
                )
            )
        }

        // Add group chats
        for (chat in groupChats) {
            conversations.add(
                SelectableConversation(
                    chatGuid = chat.guid,
                    displayName = chat.displayName ?: chat.chatIdentifier ?: "Group",
                    isGroup = true,
                    isSelected = selectedChats.contains(chat.guid),
                    latestMessageDate = chat.latestMessageDate ?: 0L
                )
            )
        }

        // Sort by latest message date (most recent first)
        return conversations.sortedByDescending { it.latestMessageDate }
    }

    fun toggleConversation(chatGuid: String) {
        viewModelScope.launch {
            val currentSelected = _uiState.value.selectedChatGuids
            if (currentSelected.contains(chatGuid)) {
                settingsDataStore.removeSelectedBubbleChat(chatGuid)
            } else {
                settingsDataStore.addSelectedBubbleChat(chatGuid)
            }
        }
    }

    fun selectAll() {
        viewModelScope.launch {
            val allGuids = _uiState.value.conversations.map { it.chatGuid }.toSet()
            settingsDataStore.setSelectedBubbleChats(allGuids)
        }
    }

    fun deselectAll() {
        viewModelScope.launch {
            settingsDataStore.setSelectedBubbleChats(emptySet())
        }
    }
}

data class BubbleChatSelectorUiState(
    val conversations: List<SelectableConversation> = emptyList(),
    val selectedChatGuids: Set<String> = emptySet(),
    val isLoading: Boolean = true
)

data class SelectableConversation(
    val chatGuid: String,
    val displayName: String,
    val isGroup: Boolean,
    val isSelected: Boolean,
    val latestMessageDate: Long
)
