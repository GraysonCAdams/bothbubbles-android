package com.bothbubbles.ui.settings.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BubbleChatSelectorViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val unifiedChatGroupDao: UnifiedChatGroupDao,
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
                unifiedChatGroupDao.observeActiveGroups(),
                chatDao.observeActiveGroupChats(),
                settingsDataStore.selectedBubbleChats
            ) { unifiedGroups, groupChats, selectedChats ->
                Triple(unifiedGroups, groupChats, selectedChats)
            }.collect { (unifiedGroups, groupChats, selectedChats) ->
                val conversations = buildConversationList(unifiedGroups, groupChats, selectedChats)
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
        unifiedGroups: List<UnifiedChatGroupEntity>,
        groupChats: List<ChatEntity>,
        selectedChats: Set<String>
    ): List<SelectableConversation> {
        val conversations = mutableListOf<SelectableConversation>()

        // Add unified groups (1:1 chats)
        for (group in unifiedGroups) {
            conversations.add(
                SelectableConversation(
                    chatGuid = group.primaryChatGuid,
                    displayName = group.displayName ?: group.identifier,
                    isGroup = false,
                    isSelected = selectedChats.contains(group.primaryChatGuid),
                    latestMessageDate = group.latestMessageDate ?: 0L
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
                    latestMessageDate = chat.lastMessageDate ?: 0L
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
