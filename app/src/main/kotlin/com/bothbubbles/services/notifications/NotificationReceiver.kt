package com.bothbubbles.services.notifications

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.FaceTimeRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.di.ApplicationScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives notification actions (reply, mark as read)
 */
@AndroidEntryPoint
class NotificationReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NotificationReceiverEntryPoint {
        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    companion object {
        private const val TAG = "NotificationReceiver"
    }

    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var faceTimeRepository: FaceTimeRepository

    @Inject
    lateinit var notificationService: NotificationService

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            NotificationReceiverEntryPoint::class.java
        )

        entryPoint.applicationScope().launch(Dispatchers.IO) {
            try {
                when (intent.action) {
                    NotificationService.ACTION_REPLY -> handleReply(intent)
                    NotificationService.ACTION_MARK_READ -> handleMarkRead(intent)
                    NotificationService.ACTION_COPY_CODE -> handleCopyCode(context, intent)
                    NotificationService.ACTION_ANSWER_FACETIME -> handleAnswerFaceTime(context, intent)
                    NotificationService.ACTION_DECLINE_FACETIME -> handleDeclineFaceTime(intent)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleReply(intent: Intent) {
        val chatGuid = intent.getStringExtra(NotificationService.EXTRA_CHAT_GUID) ?: return
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotificationService.EXTRA_REPLY_TEXT)
            ?.toString() ?: return

        try {
            messageRepository.sendMessage(
                chatGuid = chatGuid,
                text = replyText
            )

            // Clear the notification after successful reply
            notificationService.cancelNotification(chatGuid)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply for chat $chatGuid", e)
        }
    }

    private suspend fun handleMarkRead(intent: Intent) {
        val chatGuid = intent.getStringExtra(NotificationService.EXTRA_CHAT_GUID) ?: return

        try {
            chatRepository.markChatAsRead(chatGuid)
            notificationService.cancelNotification(chatGuid)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark chat $chatGuid as read", e)
        }
    }

    private suspend fun handleCopyCode(context: Context, intent: Intent) {
        val chatGuid = intent.getStringExtra(NotificationService.EXTRA_CHAT_GUID) ?: return
        val code = intent.getStringExtra(NotificationService.EXTRA_CODE_TO_COPY) ?: return

        // Copy code to clipboard
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Verification Code", code)
        clipboard.setPrimaryClip(clip)

        // Show toast and dismiss notification
        Toast.makeText(context, "Code copied: $code", Toast.LENGTH_SHORT).show()
        notificationService.cancelNotification(chatGuid)
    }

    private suspend fun handleAnswerFaceTime(context: Context, intent: Intent) {
        val callUuid = intent.getStringExtra(NotificationService.EXTRA_CALL_UUID) ?: return

        faceTimeRepository.answerCall(callUuid).fold(
            onSuccess = { link ->
                // Dismiss notification
                notificationService.dismissFaceTimeCallNotification(callUuid)

                // Open FaceTime link in browser
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(browserIntent)
            },
            onFailure = { e ->
                Toast.makeText(
                    context,
                    "Failed to answer: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    private suspend fun handleDeclineFaceTime(intent: Intent) {
        val callUuid = intent.getStringExtra(NotificationService.EXTRA_CALL_UUID) ?: return

        faceTimeRepository.declineCall(callUuid)
        notificationService.dismissFaceTimeCallNotification(callUuid)
    }
}
