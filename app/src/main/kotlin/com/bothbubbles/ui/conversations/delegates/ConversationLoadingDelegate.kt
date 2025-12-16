package com.bothbubbles.ui.conversations.delegates

import android.app.Application
import android.util.Log
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.LinkPreviewRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.UnifiedChatGroupRepository
import com.bothbubbles.ui.conversations.ConversationUiModel
import com.bothbubbles.ui.conversations.toUiModel
import com.bothbubbles.util.PerformanceProfiler
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PAGE_SIZE = 25
private const val INITIAL_LOAD_TARGET = 100 // Target number of conversations for instant display on boot

/**
 * Delegate responsible for loading and paginating conversations.
 * Handles initial load, pagination, and refresh logic.
 *
 * Phase 8: Uses AssistedInject for lifecycle-safe construction.
 * No more lateinit or initialize() - scope is provided at construction time.
 */
class ConversationLoadingDelegate @AssistedInject constructor(
    private val application: Application,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val attachmentRepository: AttachmentRepository,
    private val linkPreviewRepository: LinkPreviewRepository,
    private val unifiedChatGroupRepository: UnifiedChatGroupRepository,
    private val unifiedGroupMappingDelegateFactory: UnifiedGroupMappingDelegate.Factory,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): ConversationLoadingDelegate
    }

    companion object {
        private const val TAG = "ConversationLoadingDelegate"
    }

    // Create UnifiedGroupMappingDelegate via factory (Phase 8: delegate composition)
    private val unifiedGroupMappingDelegate: UnifiedGroupMappingDelegate =
        unifiedGroupMappingDelegateFactory.create(scope)

    // Loading state
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _canLoadMore = MutableStateFlow(true)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Load the first page of conversations on startup.
     * Uses paginated queries to avoid loading all conversations at once.
     */
    suspend fun loadInitialConversations(typingChats: Set<String>): LoadResult {
        val loadId = PerformanceProfiler.start("ConversationList.loadInitial")
        _isLoading.value = true

        // Clean up invalid display names before loading (runs once, fast, idempotent)
        chatRepository.cleanupInvalidDisplayNames()

        try {
            // Load up to INITIAL_LOAD_TARGET from each source to guarantee 100 total
            val queryId1 = PerformanceProfiler.start("DB.getUnifiedGroups")
            val unifiedGroups = unifiedChatGroupRepository.getActiveGroupsPaginated(INITIAL_LOAD_TARGET, 0)
            PerformanceProfiler.end(queryId1, "${unifiedGroups.size} groups")

            val queryId2 = PerformanceProfiler.start("DB.getGroupChats")
            val groupChats = chatRepository.getGroupChatsPaginated(INITIAL_LOAD_TARGET, 0)
            PerformanceProfiler.end(queryId2, "${groupChats.size} chats")

            val queryId3 = PerformanceProfiler.start("DB.getNonGroupChats")
            val nonGroupChats = chatRepository.getNonGroupChatsPaginated(INITIAL_LOAD_TARGET, 0)
            PerformanceProfiler.end(queryId3, "${nonGroupChats.size} chats")

            val buildId = PerformanceProfiler.start("ConversationList.build")
            val allConversations = buildConversationList(
                unifiedGroups = unifiedGroups,
                groupChats = groupChats,
                nonGroupChats = nonGroupChats,
                typingChats = typingChats
            )
            // Take only the first INITIAL_LOAD_TARGET for initial display
            val conversations = allConversations.take(INITIAL_LOAD_TARGET)
            PerformanceProfiler.end(buildId, "${conversations.size} items")

            // Check if more data exists beyond what we're showing
            val totalUnified = unifiedChatGroupRepository.getActiveGroupCount()
            val totalGroupChats = chatRepository.getGroupChatCount()
            val totalNonGroup = chatRepository.getNonGroupChatCount()
            val totalCount = totalUnified + totalGroupChats + totalNonGroup
            // More exists if we have more combined items than we're showing, or if DB has more
            val hasMore = allConversations.size > conversations.size || totalCount > allConversations.size

            _isLoading.value = false
            _canLoadMore.value = hasMore
            _currentPage.value = 0

            PerformanceProfiler.end(loadId, "${conversations.size} conversations")
            return LoadResult.Success(conversations, hasMore, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load conversations", e)
            _isLoading.value = false
            _error.value = e.message
            PerformanceProfiler.end(loadId, "ERROR: ${e.message}")
            return LoadResult.Error(e.message)
        }
    }

    /**
     * Refresh all currently loaded pages to pick up data changes.
     * Called when sync updates data or when new messages arrive.
     */
    suspend fun refreshAllLoadedPages(typingChats: Set<String>, searchQuery: String): List<ConversationUiModel> {
        val refreshId = PerformanceProfiler.start("ConversationList.refresh")
        val currentPage = _currentPage.value
        val totalLoaded = (currentPage + 1) * PAGE_SIZE

        try {
            // Re-fetch all loaded unified groups
            val unifiedGroups = unifiedChatGroupRepository.getActiveGroupsPaginated(totalLoaded, 0)

            // Re-fetch all loaded group chats
            val groupChats = chatRepository.getGroupChatsPaginated(totalLoaded, 0)

            // Re-fetch all loaded non-group chats
            val nonGroupChats = chatRepository.getNonGroupChatsPaginated(totalLoaded, 0)

            val conversations = buildConversationList(
                unifiedGroups = unifiedGroups,
                groupChats = groupChats,
                nonGroupChats = nonGroupChats,
                typingChats = typingChats
            )

            // Apply search filter if active
            val filtered = if (searchQuery.isBlank()) {
                conversations
            } else {
                conversations.filter { conv ->
                    conv.displayName.contains(searchQuery, ignoreCase = true) ||
                    conv.address.contains(searchQuery, ignoreCase = true)
                }
            }

            // Check if more data exists
            val totalUnified = unifiedChatGroupRepository.getActiveGroupCount()
            val totalGroupChats = chatRepository.getGroupChatCount()
            val totalNonGroup = chatRepository.getNonGroupChatCount()
            val loadedCount = unifiedGroups.size + groupChats.size + nonGroupChats.size
            val totalCount = totalUnified + totalGroupChats + totalNonGroup
            val hasMore = loadedCount < totalCount

            _canLoadMore.value = hasMore

            PerformanceProfiler.end(refreshId, "${filtered.size} items")
            return filtered
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh conversations", e)
            PerformanceProfiler.end(refreshId, "ERROR")
            return emptyList()
        }
    }

    /**
     * Load more conversations when user scrolls near the bottom.
     */
    suspend fun loadMoreConversations(
        currentConversations: List<ConversationUiModel>,
        typingChats: Set<String>
    ): LoadResult {
        if (_isLoadingMore.value || !_canLoadMore.value) return LoadResult.AlreadyLoading

        _isLoadingMore.value = true

        try {
            val nextPage = _currentPage.value + 1
            val offset = nextPage * PAGE_SIZE

            // Load next page of unified groups
            val moreUnifiedGroups = unifiedChatGroupRepository.getActiveGroupsPaginated(PAGE_SIZE, offset)

            // Load next page of group chats
            val moreGroupChats = chatRepository.getGroupChatsPaginated(PAGE_SIZE, offset)

            // Load next page of non-group chats
            val moreNonGroupChats = chatRepository.getNonGroupChatsPaginated(PAGE_SIZE, offset)

            val newConversations = buildConversationList(
                unifiedGroups = moreUnifiedGroups,
                groupChats = moreGroupChats,
                nonGroupChats = moreNonGroupChats,
                typingChats = typingChats
            )

            // Merge with existing, deduplicate, and sort
            val existingGuids = currentConversations.map { it.guid }.toSet()
            val uniqueNew = newConversations.filter { it.guid !in existingGuids }

            val merged = (currentConversations + uniqueNew)
                .distinctBy { it.guid }
                .sortedWith(
                    compareByDescending<ConversationUiModel> { it.isPinned }
                        .thenByDescending { it.lastMessageTimestamp }
                )

            _isLoadingMore.value = false
            _currentPage.value = nextPage
            _canLoadMore.value = newConversations.isNotEmpty()

            return LoadResult.Success(merged, newConversations.isNotEmpty(), nextPage)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load more conversations", e)
            _isLoadingMore.value = false
            return LoadResult.Error(e.message)
        }
    }

    /**
     * Build conversation list from paginated entities.
     * Converts unified groups, group chats, and orphan 1:1 chats to UI models.
     *
     * OPTIMIZATION: Uses batched queries to avoid N+1 problem:
     * 1. Collect all chat GUIDs upfront
     * 2. Batch fetch all latest messages in one query
     * 3. Pass pre-fetched data to UI model builders
     */
    private suspend fun buildConversationList(
        unifiedGroups: List<UnifiedChatGroupEntity>,
        groupChats: List<ChatEntity>,
        nonGroupChats: List<ChatEntity>,
        typingChats: Set<String>
    ): List<ConversationUiModel> {
        val conversations = mutableListOf<ConversationUiModel>()
        val handledChatGuids = mutableSetOf<String>()

        // OPTIMIZATION: Batch fetch all chat GUIDs in a single query
        val batchPrepId = PerformanceProfiler.start("BatchPrep.collectGuids")
        val groupIds = unifiedGroups.map { it.id }
        val allMembers = if (groupIds.isNotEmpty()) {
            unifiedChatGroupRepository.getChatGuidsForGroups(groupIds)
        } else {
            emptyList()
        }

        // Build maps from batch results
        val groupIdToGuids = allMembers.groupBy { it.groupId }.mapValues { entry ->
            entry.value.map { it.chatGuid }
        }
        val allGroupChatGuids = allMembers.map { it.chatGuid }

        // Track handled GUIDs
        handledChatGuids.addAll(allGroupChatGuids)
        unifiedGroups.forEach { handledChatGuids.add(it.primaryChatGuid) }
        PerformanceProfiler.end(batchPrepId, "${allGroupChatGuids.size} guids")

        // OPTIMIZATION: Batch fetch all chats in a single query
        val batchChatsId = PerformanceProfiler.start("DB.batchGetChats")
        val allChatsMap = if (allGroupChatGuids.isNotEmpty()) {
            chatRepository.getChatsByGuids(allGroupChatGuids).associateBy { it.guid }
        } else {
            emptyMap()
        }
        PerformanceProfiler.end(batchChatsId, "${allChatsMap.size} chats")

        // OPTIMIZATION: Batch fetch all latest messages in a single query
        val batchMsgId = PerformanceProfiler.start("DB.batchGetLatestMessages")
        val latestMessagesMap = if (allGroupChatGuids.isNotEmpty()) {
            messageRepository.getLatestMessagesForChats(allGroupChatGuids)
                .associateBy { it.chatGuid }
        } else {
            emptyMap()
        }
        PerformanceProfiler.end(batchMsgId, "${latestMessagesMap.size} messages")

        // OPTIMIZATION: Batch fetch all participants in a single query
        val batchParticipantsId = PerformanceProfiler.start("DB.batchGetParticipants")
        val participantsByChatMap = chatRepository.getParticipantsGroupedByChat(allGroupChatGuids)
        PerformanceProfiler.end(batchParticipantsId, "${participantsByChatMap.values.sumOf { it.size }} participants")

        // Process unified chat groups with pre-fetched data
        for (group in unifiedGroups) {
            val chatGuids = groupIdToGuids[group.id] ?: continue

            val uiModel = unifiedGroupMappingDelegate.unifiedGroupToUiModel(
                group = group,
                chatGuids = chatGuids,
                typingChats = typingChats,
                latestMessagesMap = latestMessagesMap,
                chatsMap = allChatsMap,
                participantsMap = participantsByChatMap
            )
            if (uiModel != null) {
                conversations.add(uiModel)
            }
        }

        // Add group chats (not unified - they stay separate)
        for (chat in groupChats) {
            if (chat.guid !in handledChatGuids) {
                conversations.add(chat.toUiModelWithContext(typingChats))
                handledChatGuids.add(chat.guid)
            }
        }

        // Add orphan 1:1 chats not in unified groups
        for (chat in nonGroupChats) {
            if (chat.guid !in handledChatGuids && !chat.isGroup && chat.dateDeleted == null && !chat.isArchived) {
                conversations.add(chat.toUiModelWithContext(typingChats))
                handledChatGuids.add(chat.guid)
            }
        }

        // Sort: pinned first, then by last message time
        return conversations
            .distinctBy { it.guid }
            .sortedWith(
                compareByDescending<ConversationUiModel> { it.isPinned }
                    .thenByDescending { it.lastMessageTimestamp }
            )
    }

    private suspend fun ChatEntity.toUiModelWithContext(typingChats: Set<String>): ConversationUiModel {
        // Delegate to the extension function in ConversationMappers.kt
        return this.toUiModel(
            typingChats = typingChats,
            messageRepository = messageRepository,
            chatRepository = chatRepository,
            attachmentRepository = attachmentRepository,
            linkPreviewRepository = linkPreviewRepository,
            application = application
        )
    }

    /**
     * Result of a load operation.
     */
    sealed class LoadResult {
        data class Success(
            val conversations: List<ConversationUiModel>,
            val hasMore: Boolean,
            val currentPage: Int
        ) : LoadResult()

        data class Error(val message: String?) : LoadResult()
        object AlreadyLoading : LoadResult()
    }
}
