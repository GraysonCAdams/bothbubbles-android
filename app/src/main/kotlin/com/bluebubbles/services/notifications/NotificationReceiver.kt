package com.bluebubbles.services.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.bluebubbles.data.repository.ChatRepository
import com.bluebubbles.data.repository.MessageRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives notification actions (reply, mark as read)
 */
@AndroidEntryPoint
class NotificationReceiver : BroadcastReceiver() {

    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var notificationService: NotificationService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            NotificationService.ACTION_REPLY -> handleReply(intent)
            NotificationService.ACTION_MARK_READ -> handleMarkRead(intent)
        }
    }

    private fun handleReply(intent: Intent) {
        val chatGuid = intent.getStringExtra(NotificationService.EXTRA_CHAT_GUID) ?: return
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotificationService.EXTRA_REPLY_TEXT)
            ?.toString() ?: return

        scope.launch {
            try {
                messageRepository.sendMessage(
                    chatGuid = chatGuid,
                    text = replyText
                )

                // Clear the notification after successful reply
                notificationService.cancelNotification(chatGuid)
            } catch (e: Exception) {
                // Could show an error notification here
                e.printStackTrace()
            }
        }
    }

    private fun handleMarkRead(intent: Intent) {
        val chatGuid = intent.getStringExtra(NotificationService.EXTRA_CHAT_GUID) ?: return

        scope.launch {
            try {
                chatRepository.markChatAsRead(chatGuid)
                notificationService.cancelNotification(chatGuid)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
