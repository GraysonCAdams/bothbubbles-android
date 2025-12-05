package com.bluebubbles.services.messaging

import android.util.Log
import com.bluebubbles.services.socket.ConnectionState
import com.bluebubbles.services.socket.SocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val socketService: SocketService
) {
    companion object {
        private const val TAG = "ChatFallbackTracker"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // In-memory map of chat GUIDs to their fallback state
    private val fallbackChats = ConcurrentHashMap<String, FallbackReason>()

    // Observable set of chats in fallback mode
    private val _fallbackModeChats = MutableStateFlow<Set<String>>(emptySet())
    val fallbackModeChats: StateFlow<Set<String>> = _fallbackModeChats.asStateFlow()

    init {
        // Observe server connection state to restore iMessage mode when reconnected
        scope.launch {
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
        fallbackChats[chatGuid] = reason
        updateObservable()
        Log.i(TAG, "Chat $chatGuid entered SMS fallback mode: $reason")
    }

    /**
     * Exit SMS fallback mode for a chat
     */
    fun exitFallbackMode(chatGuid: String) {
        fallbackChats.remove(chatGuid)
        updateObservable()
        Log.i(TAG, "Chat $chatGuid exited SMS fallback mode")
    }

    /**
     * Check if a chat is in SMS fallback mode
     */
    fun isInFallbackMode(chatGuid: String): Boolean {
        return fallbackChats.containsKey(chatGuid)
    }

    /**
     * Get the fallback reason for a chat
     */
    fun getFallbackReason(chatGuid: String): FallbackReason? {
        return fallbackChats[chatGuid]
    }

    /**
     * Called when the BlueBubbles server reconnects.
     * Automatically restores iMessage mode for chats that entered fallback due to server disconnect.
     */
    private fun onServerReconnected() {
        val chatsToRestore = fallbackChats.filter { (_, reason) ->
            reason == FallbackReason.SERVER_DISCONNECTED
        }.keys.toList()

        chatsToRestore.forEach { chatGuid ->
            exitFallbackMode(chatGuid)
        }

        if (chatsToRestore.isNotEmpty()) {
            Log.i(TAG, "Server reconnected, restored ${chatsToRestore.size} chats to iMessage mode")
        }
    }

    private fun updateObservable() {
        _fallbackModeChats.value = fallbackChats.keys.toSet()
    }
}
