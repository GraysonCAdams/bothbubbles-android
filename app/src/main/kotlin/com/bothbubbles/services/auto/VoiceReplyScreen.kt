package com.bothbubbles.services.auto

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.bothbubbles.R
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.repository.MessageRepository
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android Auto screen for composing and sending a voice reply.
 * Uses the car's voice input system to dictate a message.
 */
class VoiceReplyScreen(
    carContext: CarContext,
    private val chat: ChatEntity,
    private val messageRepository: MessageRepository,
    private val onMessageSent: () -> Unit
) : Screen(carContext) {

    // Screen-local scope that follows screen lifecycle
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Register lifecycle observer to cancel scope when screen is destroyed
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                screenScope.cancel()
            }
        })
    }

    @Volatile
    private var messageText: String = ""

    @Volatile
    private var isSending: Boolean = false

    override fun onGetTemplate(): Template {
        val displayTitle = chat.displayName ?: "Reply"

        if (isSending) {
            return MessageTemplate.Builder("Sending message...")
                .setTitle(displayTitle)
                .setHeaderAction(Action.BACK)
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_send)
                    ).build()
                )
                .build()
        }

        val message = if (messageText.isNotBlank()) {
            "Your message:\n\"$messageText\"\n\nTap Send to send, or use voice to change."
        } else {
            "Tap the microphone to dictate your message"
        }

        val sendAction = Action.Builder()
            .setTitle("Send")
            .setOnClickListener {
                if (messageText.isNotBlank()) {
                    sendMessage()
                } else {
                    CarToast.makeText(carContext, "Please dictate a message first", CarToast.LENGTH_SHORT).show()
                }
            }
            .build()

        val voiceAction = Action.Builder()
            .setTitle("Voice")
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, android.R.drawable.ic_btn_speak_now)
                ).build()
            )
            .setOnClickListener(ParkedOnlyOnClickListener.create {
                // Start voice recognition - the car handles this
                startVoiceInput()
            })
            .build()

        return MessageTemplate.Builder(message)
            .setTitle("Reply to $displayTitle")
            .setHeaderAction(Action.BACK)
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, R.mipmap.ic_launcher)
                ).build()
            )
            .addAction(voiceAction)
            .addAction(sendAction)
            .build()
    }

    private fun startVoiceInput() {
        // Use CarContext's startCarApp with voice input intent
        // The host will handle voice recognition
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your message")
            }
            // For now, show a toast - actual voice input is handled by the host
            CarToast.makeText(carContext, "Speak your message now...", CarToast.LENGTH_LONG).show()

            // In a real implementation, this would be handled by the host's voice system
            // The notification-based reply is the primary voice input method
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to start voice input", e)
            CarToast.makeText(carContext, "Voice input not available", CarToast.LENGTH_SHORT).show()
        }
    }

    private fun sendMessage() {
        if (messageText.isBlank()) return

        isSending = true
        invalidate()

        screenScope.launch {
            try {
                messageRepository.sendMessage(
                    chatGuid = chat.guid,
                    text = messageText
                )
                CarToast.makeText(carContext, "Message sent!", CarToast.LENGTH_SHORT).show()
                onMessageSent()
                screenManager.pop()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to send message", e)
                CarToast.makeText(carContext, "Failed to send message", CarToast.LENGTH_SHORT).show()
                isSending = false
                invalidate()
            }
        }
    }

    // Called when voice input result is received
    fun onVoiceInputResult(text: String) {
        messageText = text
        invalidate()
    }

    companion object {
        private const val TAG = "VoiceReplyScreen"
    }
}
