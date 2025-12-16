package com.bothbubbles.ui.chat.delegates

import androidx.compose.runtime.Immutable
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.ui.chat.state.SearchState
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.util.text.TextNormalization
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Delegate responsible for in-chat message search functionality.
 *
 * Implements a hybrid search strategy:
 * 1. Instant local search: Searches currently loaded messages in memory
 * 2. Async database search: Searches full conversation history via FTS5/LIKE
 *
 * Features:
 * - Diacritic-insensitive matching (e.g., "cafe" matches "café")
 * - Expanded search scope: message text, subject, and attachment filenames
 * - Search results with snippets for the "View All" bottom sheet
 *
 * Uses AssistedInject to receive runtime parameters at construction time,
 * eliminating the need for a separate initialize() call.
 */
class ChatSearchDelegate @AssistedInject constructor(
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    @Assisted private val scope: CoroutineScope,
    @Assisted private val chatGuids: List<String>
) {

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope, chatGuids: List<String>): ChatSearchDelegate
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
        const val DATABASE_SEARCH_DELAY_MS = 300L
        const val MAX_SNIPPET_LENGTH = 100
        const val DATABASE_SEARCH_LIMIT = 100
    }

    private var searchJob: Job? = null
    private var databaseSearchJob: Job? = null

    // Phase 4: messageListDelegate reference removed - ViewModel provides messages for search

    // ============================================================================
    // CONSOLIDATED SEARCH STATE
    // Single StateFlow containing all search-related state for reduced recompositions.
    // ChatScreen collects this directly instead of individual flows.
    // ============================================================================
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    // Phase 4: setMessageListDelegate() removed - ViewModel provides messages via updateSearchQuery(query, messages)

    /**
     * Activate search mode.
     */
    fun activateSearch() {
        _state.update {
            SearchState(
                isActive = true,
                query = "",
                matchIndices = emptyList(),
                currentMatchIndex = -1,
                isSearchingDatabase = false,
                databaseResults = emptyList(),
                showResultsSheet = false
            )
        }
    }

    /**
     * Close search mode completely.
     */
    fun closeSearch() {
        searchJob?.cancel()
        databaseSearchJob?.cancel()
        _state.update { SearchState() }
    }

    /**
     * Update search query and perform hybrid search.
     * Phase 4: ViewModel provides messages for search - no internal delegate lookup.
     *
     * @param query The search query
     * @param messages Currently loaded messages in memory (provided by ViewModel)
     */
    fun updateSearchQuery(query: String, messages: List<MessageUiModel>) {
        updateSearchQueryInternal(query, messages, chatGuids)
    }

    /**
     * Internal implementation of search query update.
     */
    private fun updateSearchQueryInternal(
        query: String,
        messages: List<MessageUiModel>,
        searchChatGuids: List<String>
    ) {
        // Cancel previous search jobs
        searchJob?.cancel()
        databaseSearchJob?.cancel()

        // Update query immediately for responsive UI
        _state.update { it.copy(query = query) }

        if (query.isBlank()) {
            _state.update { it.copy(
                matchIndices = emptyList(),
                currentMatchIndex = -1,
                databaseResults = emptyList(),
                isSearchingDatabase = false
            )}
            return
        }

        // 1. Instant local search with normalization
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)

            val normalizedQuery = TextNormalization.normalizeForSearch(query)
            val matchIndices = messages.mapIndexedNotNull { index, message ->
                if (matchesQuery(message, normalizedQuery)) index else null
            }

            _state.update { it.copy(
                matchIndices = matchIndices,
                currentMatchIndex = if (matchIndices.isNotEmpty()) 0 else -1
            )}

            // 2. Async database search (with additional delay to prioritize local results)
            if (searchChatGuids.isNotEmpty()) {
                launchDatabaseSearch(query, searchChatGuids, messages)
            }
        }
    }

    /**
     * Check if a message matches the normalized query.
     * Searches text, subject, and attachment filenames.
     */
    private fun matchesQuery(message: MessageUiModel, normalizedQuery: String): Boolean {
        // Check message text
        if (TextNormalization.containsNormalized(message.text, normalizedQuery)) {
            return true
        }

        // Check subject
        if (TextNormalization.containsNormalized(message.subject, normalizedQuery)) {
            return true
        }

        // Check attachment filenames
        return message.attachments.any { attachment ->
            TextNormalization.containsNormalized(attachment.transferName, normalizedQuery)
        }
    }

    /**
     * Launch async database search.
     */
    private fun launchDatabaseSearch(
        query: String,
        chatGuids: List<String>,
        loadedMessages: List<MessageUiModel>
    ) {
        databaseSearchJob?.cancel()
        databaseSearchJob = scope.launch {
            delay(DATABASE_SEARCH_DELAY_MS) // Give local search time to show first

            _state.update { it.copy(isSearchingDatabase = true) }

            try {
                val results = withContext(Dispatchers.IO) {
                    searchDatabase(query, chatGuids, loadedMessages)
                }
                _state.update { it.copy(databaseResults = results) }
            } catch (e: Exception) {
                // Log error but don't crash - database search is optional
                _state.update { it.copy(databaseResults = emptyList()) }
            } finally {
                _state.update { it.copy(isSearchingDatabase = false) }
            }
        }
    }

    /**
     * Search the database for matching messages.
     * Combines FTS5 text search with attachment filename search.
     */
    private suspend fun searchDatabase(
        query: String,
        chatGuids: List<String>,
        loadedMessages: List<MessageUiModel>
    ): List<SearchResult> {
        val loadedGuids = loadedMessages.mapTo(HashSet()) { it.guid }
        val results = mutableListOf<SearchResult>()

        // Search message text/subject using FTS5 (with LIKE fallback)
        val messageResults = try {
            // Escape FTS5 special characters and add prefix wildcard
            val ftsQuery = prepareFtsQuery(query)
            messageDao.searchMessagesInChatsFts(ftsQuery, chatGuids, DATABASE_SEARCH_LIMIT)
        } catch (e: Exception) {
            // Fallback to LIKE-based search
            messageDao.searchMessagesInChatsLike(query, chatGuids, DATABASE_SEARCH_LIMIT)
        }

        for (message in messageResults) {
            val matchType = when {
                TextNormalization.containsNormalized(message.text, query) -> MatchType.TEXT
                TextNormalization.containsNormalized(message.subject, query) -> MatchType.SUBJECT
                else -> MatchType.TEXT // Default
            }

            val snippet = createSnippet(
                text = message.text ?: message.subject ?: "",
                query = query
            )

            results.add(
                SearchResult(
                    messageGuid = message.guid,
                    chatGuid = message.chatGuid,
                    snippet = snippet,
                    timestamp = message.dateCreated,
                    senderName = null, // Would need to resolve from handle
                    matchType = matchType,
                    isLoadedInMemory = message.guid in loadedGuids
                )
            )
        }

        // Search attachment filenames
        val attachmentResults = attachmentDao.searchAttachmentsByName(query, chatGuids, 50)
        for (attachment in attachmentResults) {
            // Avoid duplicates if message already in results
            if (results.none { it.messageGuid == attachment.messageGuid }) {
                results.add(
                    SearchResult(
                        messageGuid = attachment.messageGuid,
                        chatGuid = "", // Would need to look up
                        snippet = attachment.transferName ?: "Attachment",
                        timestamp = 0L, // Would need to look up message timestamp
                        senderName = null,
                        matchType = MatchType.ATTACHMENT_NAME,
                        isLoadedInMemory = attachment.messageGuid in loadedGuids
                    )
                )
            }
        }

        // Sort by timestamp descending
        return results.sortedByDescending { it.timestamp }
    }

    /**
     * Prepare query for FTS5 MATCH syntax.
     * Escapes special characters and adds prefix matching.
     */
    private fun prepareFtsQuery(query: String): String {
        // Escape FTS5 special characters: " * - OR AND NOT ( )
        val escaped = query
            .replace("\"", "\"\"")
            .replace("*", "")
            .replace("-", " ")

        // Add prefix wildcard for partial matching
        return "\"$escaped\"*"
    }

    /**
     * Create a snippet of text around the query match.
     */
    private fun createSnippet(text: String, query: String): String {
        if (text.length <= MAX_SNIPPET_LENGTH) return text

        val normalizedText = TextNormalization.normalizeForSearch(text)
        val normalizedQuery = TextNormalization.normalizeForSearch(query)
        val matchIndex = normalizedText.indexOf(normalizedQuery)

        if (matchIndex == -1) {
            return text.take(MAX_SNIPPET_LENGTH) + "..."
        }

        // Center the snippet around the match
        val snippetStart = maxOf(0, matchIndex - MAX_SNIPPET_LENGTH / 3)
        val snippetEnd = minOf(text.length, snippetStart + MAX_SNIPPET_LENGTH)

        val snippet = text.substring(snippetStart, snippetEnd)
        return when {
            snippetStart > 0 && snippetEnd < text.length -> "...$snippet..."
            snippetStart > 0 -> "...$snippet"
            snippetEnd < text.length -> "$snippet..."
            else -> snippet
        }
    }

    /**
     * Navigate to previous search match.
     */
    fun navigateSearchUp() {
        val currentState = _state.value
        if (currentState.matchIndices.isEmpty()) return

        val currentIndex = currentState.currentMatchIndex
        val newIndex = if (currentIndex <= 0) {
            currentState.matchIndices.size - 1
        } else {
            currentIndex - 1
        }
        _state.update { it.copy(currentMatchIndex = newIndex) }
    }

    /**
     * Navigate to next search match.
     */
    fun navigateSearchDown() {
        val currentState = _state.value
        if (currentState.matchIndices.isEmpty()) return

        val currentIndex = currentState.currentMatchIndex
        val newIndex = if (currentIndex >= currentState.matchIndices.size - 1) {
            0
        } else {
            currentIndex + 1
        }
        _state.update { it.copy(currentMatchIndex = newIndex) }
    }

    /**
     * Show the search results bottom sheet.
     */
    fun showResultsSheet() {
        _state.update { it.copy(showResultsSheet = true) }
    }

    /**
     * Hide the search results bottom sheet.
     */
    fun hideResultsSheet() {
        _state.update { it.copy(showResultsSheet = false) }
    }

    /**
     * Type of search match.
     */
    enum class MatchType {
        TEXT,            // Match found in message text
        SUBJECT,         // Match found in message subject
        ATTACHMENT_NAME  // Match found in attachment filename
    }

    /**
     * Result from database search with snippet for display in results list.
     */
    @Immutable
    data class SearchResult(
        val messageGuid: String,
        val chatGuid: String,
        val snippet: String,
        val timestamp: Long,
        val senderName: String?,
        val matchType: MatchType,
        val isLoadedInMemory: Boolean
    )
}
