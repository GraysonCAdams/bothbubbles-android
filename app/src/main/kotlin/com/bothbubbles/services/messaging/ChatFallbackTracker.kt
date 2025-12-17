package com.bothbubbles.services.messaging

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.services.socket.SocketService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reason why a chat entered SMS fallback mode
 */
enum class FallbackReason {
    SERVER_DISCONNECTED,  // BlueBubbles server was disconnected
    IMESSAGE_FAILED,      // iMessage send failed (e.g., recipient not registered)
    USER_REQUESTED        // User manually chose to send as SMS
}

/**
 * Tracks per-chat SMS fallback mode state.
 * When a chat enters fallback mode, messages will be sent via SMS/MMS instead of iMessage.
 *
 * This is an in-memory tracker that resets when the app restarts.
 */
@Singleton
class ChatFallbackTracker @Inject constructor(
    private val socketService: SocketService,
    private val chatDao: ChatDao,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    // In-memory map of chat GUIDs to their fallback state
    private val fallbackChats = ConcurrentHashMap<String, ChatFallbackEntry>()

    // Observable set of chats in fallback mode
    private val _fallbackModeChats = MutableStateFlow<Set<String>>(emptySet())
    val fallbackModeChats: StateFlow<Set<String>> = _fallbackModeChats.asStateFlow()

    // Observable map of chats to their fallback metadata
    private val _fallbackStates = MutableStateFlow<Map<String, ChatFallbackEntry>>(emptyMap())
    val fallbackStates: StateFlow<Map<String, ChatFallbackEntry>> = _fallbackStates.asStateFlow()

    init {
        applicationScope.launch(ioDispatcher) {
            restorePersistedFallbacks()
        }

        // Observe server connection state to restore iMessage mode when reconnected
        applicationScope.launch(ioDispatcher) {
            socketService.connectionState
                .collect { state ->
                    if (state == ConnectionState.CONNECTED) {
                        onServerReconnected()
                    }
                }
        }
    }

    /**
     * Enter SMS fallback mode for a chat
     */
    fun enterFallbackMode(chatGuid: String, reason: FallbackReason) {
        val entry = ChatFallbackEntry(reason, System.currentTimeMillis())
        fallbackChats[chatGuid] = entry
        updateObservable()
        applicationScope.launch(ioDispatcher) {
            chatDao.updateFallbackState(chatGuid, true, reason.name, entry.updatedAt)
        }
        Timber.i("Chat $chatGuid entered SMS fallback mode: $reason")
    }

    /**
     * Exit SMS fallback mode for a chat
     */
    fun exitFallbackMode(chatGuid: String) {
        fallbackChats.remove(chatGuid)
        updateObservable()
        applicationScope.launch(ioDispatcher) {
            chatDao.updateFallbackState(chatGuid, false, null, null)
        }
        Timber.i("Chat $chatGuid exited SMS fallback mode")
    }

    /**
     * Check if a chat is in SMS fallback mode
     */
    fun isInFallbackMode(chatGuid: String): Boolean = fallbackChats.containsKey(chatGuid)

    /**
     * Get the fallback reason for a chat
     */
    fun getFallbackReason(chatGuid: String): FallbackReason? = fallbackChats[chatGuid]?.reason

    /**
     * Called when the BlueBubbles server reconnects.
     * Automatically restores iMessage mode for chats that entered fallback due to server disconnect.
     */
    private fun onServerReconnected() {
        val chatsToRestore = fallbackChats.filter { (_, entry) ->
            entry.reason == FallbackReason.SERVER_DISCONNECTED
        }.keys.toList()

        chatsToRestore.forEach { chatGuid ->
            exitFallbackMode(chatGuid)
        }

        if (chatsToRestore.isNotEmpty()) {
            Timber.i("Server reconnected, restored ${chatsToRestore.size} chats to iMessage mode")
        }
    }

    private suspend fun restorePersistedFallbacks() {
        val persisted = chatDao.getChatsInFallback()
        if (persisted.isEmpty()) return

        persisted.forEach { projection ->
            val reason = projection.reason?.let {
                runCatching { FallbackReason.valueOf(it) }.getOrNull()
            }

            if (reason != null) {
                fallbackChats[projection.guid] = ChatFallbackEntry(
                    reason = reason,
                    updatedAt = projection.updatedAt ?: System.currentTimeMillis()
                )
            }
        }

        updateObservable()
        Timber.i("Restored ${fallbackChats.size} chats in SMS fallback mode")
    }

    private fun updateObservable() {
        val snapshot = fallbackChats.toMap()
        _fallbackModeChats.value = snapshot.keys.toSet()
        _fallbackStates.value = snapshot
    }
}

data class ChatFallbackEntry(
    val reason: FallbackReason,
    val updatedAt: Long
)
