package com.bothbubbles.services.socket

import android.util.Log
import io.socket.client.Socket
import org.json.JSONObject

/**
 * Handles outbound Socket.IO event emission (events sent from client to server).
 * This class encapsulates all client-to-server communication logic.
 */
class SocketEventEmitter(
    private val getSocket: () -> Socket?,
    private val isConnected: () -> Boolean
) {
    companion object {
        private const val TAG = "SocketEventEmitter"
    }

    // ===== Typing Indicators =====

    /**
     * Send started-typing event to the server.
     * This notifies the other party that we are typing.
     * Requires Private API to be enabled on the server.
     */
    fun sendStartedTyping(chatGuid: String) {
        if (!isConnected()) {
            Log.d(TAG, "Cannot send typing indicator: not connected")
            return
        }

        val payload = JSONObject().apply {
            put("chatGuid", chatGuid)
        }

        getSocket()?.emit("started-typing", payload)
        Log.d(TAG, "Sent started-typing for chat: $chatGuid")
    }

    /**
     * Send stopped-typing event to the server.
     * This notifies the other party that we stopped typing.
     * Requires Private API to be enabled on the server.
     */
    fun sendStoppedTyping(chatGuid: String) {
        if (!isConnected()) {
            Log.d(TAG, "Cannot send typing indicator: not connected")
            return
        }

        val payload = JSONObject().apply {
            put("chatGuid", chatGuid)
        }

        getSocket()?.emit("stopped-typing", payload)
        Log.d(TAG, "Sent stopped-typing for chat: $chatGuid")
    }

    // ===== Chat State Events =====

    /**
     * Notify server that user opened a conversation.
     * This tells the server which chat is currently active, allowing it to
     * optimize notifications (e.g., skip push for active chat).
     */
    fun sendOpenChat(chatGuid: String) {
        if (!isConnected()) {
            Log.d(TAG, "Cannot send open-chat: not connected")
            return
        }

        val payload = JSONObject().apply {
            put("chatGuid", chatGuid)
        }

        getSocket()?.emit("open-chat", payload)
        Log.d(TAG, "Sent open-chat for: $chatGuid")
    }

    /**
     * Notify server that user closed the conversation.
     * Call this when navigating away from the chat screen.
     */
    fun sendCloseChat(chatGuid: String) {
        if (!isConnected()) {
            Log.d(TAG, "Cannot send close-chat: not connected")
            return
        }

        val payload = JSONObject().apply {
            put("chatGuid", chatGuid)
        }

        getSocket()?.emit("close-chat", payload)
        Log.d(TAG, "Sent close-chat for: $chatGuid")
    }
}
