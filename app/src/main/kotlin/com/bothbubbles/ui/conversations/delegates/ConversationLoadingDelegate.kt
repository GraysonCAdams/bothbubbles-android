package com.bothbubbles.ui.conversations.delegates

import android.app.Application
import timber.log.Timber
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.core.model.entity.UnifiedChatEntity
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.LinkPreviewRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.UnifiedChatRepository
import com.bothbubbles.ui.conversations.ConversationUiModel
import com.bothbubbles.ui.conversations.toUiModel
import com.bothbubbles.util.PerformanceProfiler
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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
    private val unifiedChatRepository: UnifiedChatRepository,
    private val unifiedGroupMappingDelegateFactory: UnifiedGroupMappingDelegate.Factory,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): ConversationLoadingDelegate
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
     *
     * IMPORTANT: All database operations run on Dispatchers.IO to avoid blocking main thread.
     */
    suspend fun loadInitialConversations(typingChats: Set<String>): LoadResult {
        val threadId = Thread.currentThread().id
        Timber.tag("ConvoDebug").w(">>> loadInitialConversations ENTRY [thread=$threadId]")

        val loadId = PerformanceProfiler.start("ConversationList.loadInitial")
        _isLoading.value = true

        try {
            // Run all database operations on IO dispatcher
            val result = withContext(Dispatchers.IO) {
                // Clean up invalid display names before loading (runs once, fast, idempotent)
                chatRepository.cleanupInvalidDisplayNames()

                // Load up to INITIAL_LOAD_TARGET from each source to guarantee 100 total
                val queryId1 = PerformanceProfiler.start("DB.getUnifiedChats")
                val unifiedChats = unifiedChatRepository.getActiveChats(INITIAL_LOAD_TARGET, 0)
                PerformanceProfiler.end(queryId1, "${unifiedChats.size} unified chats")

                val queryId2 = PerformanceProfiler.start("DB.getGroupChats")
                val groupChats = chatRepository.getGroupChatsPaginated(INITIAL_LOAD_TARGET, 0)
                PerformanceProfiler.end(queryId2, "${groupChats.size} chats")

                val queryId3 = PerformanceProfiler.start("DB.getNonGroupChats")
                val nonGroupChats = chatRepository.getNonGroupChatsPaginated(INITIAL_LOAD_TARGET, 0)
                PerformanceProfiler.end(queryId3, "${nonGroupChats.size} chats")

                // DEBUG: Trace group chat loading issue - looking for "Squad Goals" and "Siblings"
                val threadId = Thread.currentThread().id
                Timber.tag("ConvoDebug").w("=== INITIAL LOAD DEBUG [thread=$threadId] ===")
                Timber.tag("ConvoDebug").d("Unified chats total: ${unifiedChats.size}")

                // Check for group unified chats (sourceId contains ";chat" for group chats)
                val groupUnified = unifiedChats.filter { it.sourceId.contains(";chat") }
                Timber.tag("ConvoDebug").d("Group unified chats: ${groupUnified.size}")
                groupUnified.forEach { uc ->
                    Timber.tag("ConvoDebug").d("  GroupUnified: displayName=${uc.displayName}, sourceId=${uc.sourceId.take(40)}, latestMsgDate=${uc.latestMessageDate}")
                }

                // Look for Squad Goals and Siblings in unified chats by display name
                val targetNames = listOf("Squad Goals", "Siblings", "squad goals", "siblings")
                val targetUnified = unifiedChats.filter { uc ->
                    targetNames.any { name -> uc.displayName?.contains(name, ignoreCase = true) == true }
                }
                if (targetUnified.isNotEmpty()) {
                    Timber.tag("ConvoDebug").d("TARGET FOUND in unified: ${targetUnified.map { "${it.displayName}|${it.sourceId.take(30)}" }}")
                } else {
                    Timber.tag("ConvoDebug").d("TARGET NOT in unified chats (Squad Goals/Siblings)")
                }

                Timber.tag("ConvoDebug").d("Orphan group chats (unified_chat_id IS NULL): ${groupChats.size}")
                // Look for Squad Goals and Siblings in orphan group chats
                val targetOrphans = groupChats.filter { gc ->
                    targetNames.any { name -> gc.displayName?.contains(name, ignoreCase = true) == true }
                }
                if (targetOrphans.isNotEmpty()) {
                    Timber.tag("ConvoDebug").d("TARGET FOUND in orphans: ${targetOrphans.map { "${it.displayName}|${it.guid.take(30)}|unifiedId=${it.unifiedChatId}" }}")
                } else {
                    Timber.tag("ConvoDebug").d("TARGET NOT in orphan group chats")
                }

                // Check chat-to-unified mappings
                val unifiedChatIds = unifiedChats.map { it.id }
                if (unifiedChatIds.isNotEmpty()) {
                    val mapping = chatRepository.getChatGuidsForUnifiedChats(unifiedChatIds)
                    val totalMappedChats = mapping.values.sumOf { it.size }
                    Timber.tag("ConvoDebug").d("Chat-to-unified mappings: $totalMappedChats chats linked to ${mapping.size} unified")

                    // Check specifically for group unified chats that have no mappings
                    val unmappedGroupUnified = groupUnified.filter { uc -> mapping[uc.id].isNullOrEmpty() }
                    if (unmappedGroupUnified.isNotEmpty()) {
                        Timber.tag("ConvoDebug").d("WARNING: ${unmappedGroupUnified.size} group unified chats have NO linked chats!")
                        unmappedGroupUnified.forEach { uc ->
                            Timber.tag("ConvoDebug").d("  Orphaned unified: displayName=${uc.displayName}, id=${uc.id.take(12)}, sourceId=${uc.sourceId.take(40)}")
                        }
                    }
                }

                Timber.tag("ConvoDebug").d("Non-group chats: ${nonGroupChats.size}")
                Timber.tag("ConvoDebug").d("=== END DEBUG ===")

                val buildId = PerformanceProfiler.start("ConversationList.build")
                val allConversations = buildConversationList(
                    unifiedChats = unifiedChats,
                    groupChats = groupChats,
                    nonGroupChats = nonGroupChats,
                    typingChats = typingChats
                )
                // Take only the first INITIAL_LOAD_TARGET for initial display
                val conversations = allConversations.take(INITIAL_LOAD_TARGET)
                PerformanceProfiler.end(buildId, "${conversations.size} items")

                // Check if Squad Goals / Siblings made it into the final list
                val checkThreadId = Thread.currentThread().id
                val targetInFinal = conversations.filter { conv ->
                    conv.displayName.contains("Squad", ignoreCase = true) ||
                    conv.displayName.contains("Siblings", ignoreCase = true)
                }
                if (targetInFinal.isNotEmpty()) {
                    Timber.tag("ConvoDebug").w("[thread=$checkThreadId] TARGET IN FINAL LIST: ${targetInFinal.map { "${it.displayName}|isGroup=${it.isGroup}|guid=${it.guid.take(30)}" }}")
                } else {
                    Timber.tag("ConvoDebug").w("[thread=$checkThreadId] TARGET NOT IN FINAL LIST - total convos: ${conversations.size}, allConvos: ${allConversations.size}")
                    // Check if they're in allConversations but got cut off
                    val targetInAll = allConversations.filter { conv ->
                        conv.displayName.contains("Squad", ignoreCase = true) ||
                        conv.displayName.contains("Siblings", ignoreCase = true)
                    }
                    if (targetInAll.isNotEmpty()) {
                        Timber.tag("ConvoDebug").w("TARGET IN ALL (but cut off): ${targetInAll.map { "${it.displayName}|pos=${allConversations.indexOf(it)}" }}")
                    }
                }

                // Check if more data exists beyond what we're showing
                val totalUnified = unifiedChatRepository.getActiveCount()
                val totalGroupChats = chatRepository.getGroupChatCount()
                val totalNonGroup = chatRepository.getNonGroupChatCount()
                val totalCount = totalUnified + totalGroupChats + totalNonGroup
                // More exists if we have more combined items than we're showing, or if DB has more
                val hasMore = allConversations.size > conversations.size || totalCount > allConversations.size

                Triple(conversations, hasMore, allConversations.size)
            }

            val (conversations, hasMore, _) = result
            _isLoading.value = false
            _canLoadMore.value = hasMore
            _currentPage.value = 0

            PerformanceProfiler.end(loadId, "${conversations.size} conversations")
            return LoadResult.Success(conversations, hasMore, 0)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load conversations")
            _isLoading.value = false
            _error.value = e.message
            PerformanceProfiler.end(loadId, "ERROR: ${e.message}")
            return LoadResult.Error(e.message)
        }
    }

    /**
     * Refresh all currently loaded pages to pick up data changes.
     * Called when sync updates data or when new messages arrive.
     *
     * IMPORTANT: All database operations run on Dispatchers.IO to avoid blocking main thread.
     */
    suspend fun refreshAllLoadedPages(typingChats: Set<String>, searchQuery: String): List<ConversationUiModel> {
        val refreshId = PerformanceProfiler.start("ConversationList.refresh")
        val currentPage = _currentPage.value
        val totalLoaded = (currentPage + 1) * PAGE_SIZE

        try {
            // Run all database operations on IO dispatcher
            val result = withContext(Dispatchers.IO) {
                val threadId = Thread.currentThread().id
                Timber.tag("ConvoDebug").w("=== REFRESH LOAD [thread=$threadId] totalLoaded=$totalLoaded ===")

                // Re-fetch all loaded unified chats
                val unifiedChats = unifiedChatRepository.getActiveChats(totalLoaded, 0)

                // Re-fetch all loaded group chats
                val groupChats = chatRepository.getGroupChatsPaginated(totalLoaded, 0)

                // Re-fetch all loaded non-group chats
                val nonGroupChats = chatRepository.getNonGroupChatsPaginated(totalLoaded, 0)

                val conversations = buildConversationList(
                    unifiedChats = unifiedChats,
                    groupChats = groupChats,
                    nonGroupChats = nonGroupChats,
                    typingChats = typingChats
                )

                // Check if more data exists
                val totalUnified = unifiedChatRepository.getActiveCount()
                val totalGroupChats = chatRepository.getGroupChatCount()
                val totalNonGroup = chatRepository.getNonGroupChatCount()
                val loadedCount = unifiedChats.size + groupChats.size + nonGroupChats.size
                val totalCount = totalUnified + totalGroupChats + totalNonGroup
                val hasMore = loadedCount < totalCount

                Triple(conversations, hasMore, loadedCount)
            }

            val (conversations, hasMore, _) = result

            // Apply search filter if active (can run on main thread - just filtering in-memory list)
            val filtered = if (searchQuery.isBlank()) {
                conversations
            } else {
                conversations.filter { conv ->
                    conv.displayName.contains(searchQuery, ignoreCase = true) ||
                    conv.address.contains(searchQuery, ignoreCase = true)
                }
            }

            _canLoadMore.value = hasMore

            PerformanceProfiler.end(refreshId, "${filtered.size} items")
            return filtered
        } catch (e: Exception) {
            Timber.e(e, "Failed to refresh conversations")
            PerformanceProfiler.end(refreshId, "ERROR")
            return emptyList()
        }
    }

    /**
     * Load more conversations when user scrolls near the bottom.
     *
     * IMPORTANT: All database operations run on Dispatchers.IO to avoid blocking main thread.
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

            // Run all database operations on IO dispatcher
            val newConversations = withContext(Dispatchers.IO) {
                // Load next page of unified chats
                val moreUnifiedChats = unifiedChatRepository.getActiveChats(PAGE_SIZE, offset)

                // Load next page of group chats
                val moreGroupChats = chatRepository.getGroupChatsPaginated(PAGE_SIZE, offset)

                // Load next page of non-group chats
                val moreNonGroupChats = chatRepository.getNonGroupChatsPaginated(PAGE_SIZE, offset)

                buildConversationList(
                    unifiedChats = moreUnifiedChats,
                    groupChats = moreGroupChats,
                    nonGroupChats = moreNonGroupChats,
                    typingChats = typingChats
                )
            }

            // Merge with existing, deduplicate by guid first (in-memory operations, OK on main thread)
            val existingGuids = currentConversations.map { it.guid }.toSet()
            val uniqueNew = newConversations.filter { it.guid !in existingGuids }
            val combined = (currentConversations + uniqueNew).distinctBy { it.guid }

            // Deduplicate by contactKey for 1:1 chats (catches cross-page duplicates)
            val (groupChats, individualChats) = combined.partition {
                it.isGroup || it.contactKey.isBlank()
            }

            val deduplicatedIndividuals = individualChats
                .groupBy { it.contactKey }
                .map { (contactKey, duplicates) ->
                    if (duplicates.size > 1) {
                        Timber.w("loadMoreConversations: Deduplicating ${duplicates.size} chats with contactKey '$contactKey'")
                    }
                    duplicates.firstOrNull { it.isMerged }
                        ?: duplicates.maxByOrNull { it.lastMessageTimestamp }
                        ?: duplicates.first()
                }

            val merged = (groupChats + deduplicatedIndividuals)
                .sortedWith(
                    compareByDescending<ConversationUiModel> { it.isPinned }
                        .thenByDescending { it.lastMessageTimestamp }
                )

            _isLoadingMore.value = false
            _currentPage.value = nextPage
            _canLoadMore.value = newConversations.isNotEmpty()

            return LoadResult.Success(merged, newConversations.isNotEmpty(), nextPage)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load more conversations")
            _isLoadingMore.value = false
            return LoadResult.Error(e.message)
        }
    }

    /**
     * Load all remaining pages at once.
     * Used when a filter is active - filters work on client-side data,
     * so we need all conversations loaded to show all matching items.
     *
     * IMPORTANT: All database operations run on Dispatchers.IO to avoid blocking main thread.
     */
    suspend fun loadAllRemainingPages(
        currentConversations: List<ConversationUiModel>,
        typingChats: Set<String>
    ): LoadResult {
        if (_isLoadingMore.value) return LoadResult.AlreadyLoading
        if (!_canLoadMore.value) return LoadResult.Success(currentConversations, false, _currentPage.value)

        _isLoadingMore.value = true

        try {
            // Run all database operations on IO dispatcher
            val result = withContext(Dispatchers.IO) {
                val allConversations = currentConversations.toMutableList()
                var page = _currentPage.value

                // Load pages until no more data
                while (true) {
                    page++
                    val offset = page * PAGE_SIZE

                    val moreUnifiedChats = unifiedChatRepository.getActiveChats(PAGE_SIZE, offset)
                    val moreGroupChats = chatRepository.getGroupChatsPaginated(PAGE_SIZE, offset)
                    val moreNonGroupChats = chatRepository.getNonGroupChatsPaginated(PAGE_SIZE, offset)

                    val newConversations = buildConversationList(
                        unifiedChats = moreUnifiedChats,
                        groupChats = moreGroupChats,
                        nonGroupChats = moreNonGroupChats,
                        typingChats = typingChats
                    )

                    if (newConversations.isEmpty()) {
                        break // No more data
                    }

                    // Merge with existing
                    val existingGuids = allConversations.map { it.guid }.toSet()
                    val uniqueNew = newConversations.filter { it.guid !in existingGuids }
                    allConversations.addAll(uniqueNew)
                }

                Pair(allConversations.toList(), page)
            }

            val (allConversations, page) = result

            // Sort and deduplicate final result (in-memory operations, OK on main thread)
            val uniqueByGuid = allConversations.distinctBy { it.guid }

            // Deduplicate by contactKey for 1:1 chats (same logic as buildConversationList)
            // This catches cross-page duplicates that guid-only dedup misses
            val (groupChats, individualChats) = uniqueByGuid.partition {
                it.isGroup || it.contactKey.isBlank()
            }

            val deduplicatedIndividuals = individualChats
                .groupBy { it.contactKey }
                .map { (contactKey, duplicates) ->
                    if (duplicates.size > 1) {
                        Timber.w("loadAllRemainingPages: Deduplicating ${duplicates.size} chats with contactKey '$contactKey': ${duplicates.map { it.guid }}")
                    }
                    // Prefer merged unified entries, then most recent
                    duplicates.firstOrNull { it.isMerged }
                        ?: duplicates.maxByOrNull { it.lastMessageTimestamp }
                        ?: duplicates.first()
                }

            val sorted = (groupChats + deduplicatedIndividuals)
                .sortedWith(
                    compareByDescending<ConversationUiModel> { it.isPinned }
                        .thenByDescending { it.lastMessageTimestamp }
                )

            _isLoadingMore.value = false
            _currentPage.value = page
            _canLoadMore.value = false

            Timber.d("loadAllRemainingPages: Loaded all ${sorted.size} conversations (deduplicated from ${uniqueByGuid.size})")
            return LoadResult.Success(sorted, false, page)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load all remaining conversations")
            _isLoadingMore.value = false
            return LoadResult.Error(e.message)
        }
    }

    /**
     * Build conversation list from paginated entities.
     * Converts unified chats, group chats, and orphan 1:1 chats to UI models.
     *
     * OPTIMIZATION: Uses batched queries to avoid N+1 problem:
     * 1. Collect all chat GUIDs upfront via foreign key lookup
     * 2. Batch fetch all chats and participants
     * 3. Pass pre-fetched data to UI model builders
     */
    private suspend fun buildConversationList(
        unifiedChats: List<UnifiedChatEntity>,
        groupChats: List<ChatEntity>,
        nonGroupChats: List<ChatEntity>,
        typingChats: Set<String>
    ): List<ConversationUiModel> {
        val conversations = mutableListOf<ConversationUiModel>()
        val handledChatGuids = mutableSetOf<String>()

        // OPTIMIZATION: Batch fetch all chat GUIDs for unified chats in a single query
        val batchPrepId = PerformanceProfiler.start("BatchPrep.collectGuids")
        val unifiedChatIds = unifiedChats.map { it.id }
        val unifiedChatIdToGuids = if (unifiedChatIds.isNotEmpty()) {
            chatRepository.getChatGuidsForUnifiedChats(unifiedChatIds)
        } else {
            emptyMap()
        }
        val allUnifiedChatGuids = unifiedChatIdToGuids.values.flatten()

        // Track handled GUIDs - include source IDs and all linked chats
        handledChatGuids.addAll(allUnifiedChatGuids)
        unifiedChats.forEach { handledChatGuids.add(it.sourceId) }
        PerformanceProfiler.end(batchPrepId, "${allUnifiedChatGuids.size} guids")

        // OPTIMIZATION: Batch fetch all chats in a single query
        val batchChatsId = PerformanceProfiler.start("DB.batchGetChats")
        val allChatsMap = if (allUnifiedChatGuids.isNotEmpty()) {
            chatRepository.getChatsByGuids(allUnifiedChatGuids).associateBy { it.guid }
        } else {
            emptyMap()
        }
        PerformanceProfiler.end(batchChatsId, "${allChatsMap.size} chats")

        // OPTIMIZATION: Batch fetch all participants in a single query
        val batchParticipantsId = PerformanceProfiler.start("DB.batchGetParticipants")
        val participantsByChatMap = chatRepository.getParticipantsGroupedByChat(allUnifiedChatGuids)
        PerformanceProfiler.end(batchParticipantsId, "${participantsByChatMap.values.sumOf { it.size }} participants")

        Timber.d("buildConversationList: Processing ${unifiedChats.size} unified chats, ${groupChats.size} group chats, ${nonGroupChats.size} non-group chats")

        // OPTIMIZATION: Batch check which non-group chats are linked to ANY unified chat
        // This prevents adding chats as orphans when their unified chat isn't in current page
        val nonGroupChatGuids = nonGroupChats.map { it.guid }
        if (nonGroupChatGuids.isNotEmpty()) {
            val chatsLinkedToUnified = chatRepository.getChatsLinkedToUnifiedChats(nonGroupChatGuids)
            handledChatGuids.addAll(chatsLinkedToUnified)
            Timber.d("buildConversationList: ${chatsLinkedToUnified.size} of ${nonGroupChatGuids.size} non-group chats are linked to unified chats")
        }

        // Process unified chats with pre-fetched data
        for (unifiedChat in unifiedChats) {
            val chatGuids = unifiedChatIdToGuids[unifiedChat.id] ?: listOf(unifiedChat.sourceId)

            // DEBUG: Trace group unified chats specifically
            val isGroupUnified = unifiedChat.sourceId.contains(";chat")
            if (isGroupUnified) {
                val threadId = Thread.currentThread().id
                val guidsFoundInMap = chatGuids.filter { it in allChatsMap.keys }
                val guidsNotInMap = chatGuids.filter { it !in allChatsMap.keys }
                Timber.tag("ConvoDebug").d(
                    "[thread=$threadId] GROUP UNIFIED processing: id=${unifiedChat.id.take(12)}, sourceId=${unifiedChat.sourceId.take(40)}, " +
                    "chatGuids=${chatGuids.size}, inMap=${guidsFoundInMap.size}, notInMap=${guidsNotInMap.size}"
                )
                if (guidsNotInMap.isNotEmpty()) {
                    Timber.tag("ConvoDebug").d("  Missing from chatsMap: $guidsNotInMap")
                }
            }

            val uiModel = unifiedGroupMappingDelegate.unifiedChatToUiModel(
                unifiedChat = unifiedChat,
                chatGuids = chatGuids,
                typingChats = typingChats,
                chatsMap = allChatsMap,
                participantsMap = participantsByChatMap
            )

            // DEBUG: Log if group unified chat returned null uiModel
            if (isGroupUnified && uiModel == null) {
                Timber.tag("ConvoDebug").w("GROUP UNIFIED returned NULL uiModel: sourceId=${unifiedChat.sourceId.take(40)}")
            } else if (isGroupUnified && uiModel != null) {
                Timber.tag("ConvoDebug").d("GROUP UNIFIED success: ${uiModel.displayName}|guid=${uiModel.guid.take(30)}")
            }

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

        // Add orphan 1:1 chats not linked to unified chats
        for (chat in nonGroupChats) {
            if (chat.guid !in handledChatGuids && !chat.isGroup && chat.dateDeleted == null) {
                conversations.add(chat.toUiModelWithContext(typingChats))
                handledChatGuids.add(chat.guid)
            }
        }

        // Sort: pinned first, then by last message time
        val sortedConversations = conversations
            .distinctBy { it.guid }
            .sortedWith(
                compareByDescending<ConversationUiModel> { it.isPinned }
                    .thenByDescending { it.lastMessageTimestamp }
            )

        // For non-group 1:1 chats, deduplicate by contactKey
        // This catches cases where unified chat linking is broken
        // (same contact appears as separate iMessage/SMS chats)
        val (groupConversations, individualChats) = sortedConversations.partition {
            it.isGroup || it.contactKey.isBlank()
        }

        // DEBUG: Check if group chats ended up in individualChats (wrong partition)
        val mispartitionedGroups = individualChats.filter { it.guid.contains(";chat") }
        if (mispartitionedGroups.isNotEmpty()) {
            Timber.tag("ConvoDebug").w(
                "MISPARTITIONED GROUPS: ${mispartitionedGroups.size} chats with ';chat' in guid are in individualChats: " +
                "${mispartitionedGroups.take(5).map { "${it.displayName}|isGroup=${it.isGroup}|key=${it.contactKey}" }}"
            )
        }

        val deduplicatedIndividualChats = individualChats
            .groupBy { it.contactKey }
            .map { (contactKey, duplicates) ->
                // Log if we're deduplicating - this indicates broken unified chat linking
                if (duplicates.size > 1) {
                    Timber.w("Deduplicating ${duplicates.size} chats with same contactKey '$contactKey': ${duplicates.map { it.guid }}")
                }
                // Prefer merged unified entries, then most recent
                duplicates.firstOrNull { it.isMerged }
                    ?: duplicates.maxByOrNull { it.lastMessageTimestamp }
                    ?: duplicates.first()
            }

        return (groupConversations + deduplicatedIndividualChats).sortedWith(
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
