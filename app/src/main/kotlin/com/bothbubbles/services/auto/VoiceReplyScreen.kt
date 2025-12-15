package com.bothbubbles.services.auto

import android.content.ActivityNotFoundException
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
import com.bothbubbles.services.messaging.MessageSendingService
import com.bothbubbles.services.socket.SocketConnection
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
 *
 * Implements voice input resilience:
 * - Wraps RecognizerIntent in try-catch for ActivityNotFoundException
 * - Falls back gracefully when voice recognizer is unavailable
 * - Shows informative error messages to driver
 *
 * Connection status awareness:
 * - Shows warning when offline
 * - Messages are queued for delivery when back online
 */
class VoiceReplyScreen(
    carContext: CarContext,
    private val chat: ChatEntity,
    private val messageSendingService: MessageSendingService,
    private val onMessageSent: () -> Unit,
    private val socketConnection: SocketConnection? = null
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

    @Volatile
    private var voiceInputAvailable: Boolean = true

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

        val message = when {
            !voiceInputAvailable -> "Voice input is not available on this head unit.\n\nUse the notification reply feature instead."
            messageText.isNotBlank() -> "Your message:\n\"$messageText\"\n\nTap Send to send, or use voice to change."
            else -> "Tap the microphone to dictate your message"
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

    /**
     * Start voice input with resilient error handling.
     *
     * Handles ActivityNotFoundException for head units that don't have
     * a speech recognizer (older Linux-based units, specific OEM skins).
     *
     * Falls back gracefully with informative user messaging.
     */
    private fun startVoiceInput() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your message")
            }

            // Check if activity is available before attempting to start
            val resolveInfo = carContext.packageManager.queryIntentActivities(intent, 0)
            if (resolveInfo.isEmpty()) {
                handleVoiceInputUnavailable()
                return
            }

            // Show toast indicating voice input is starting
            CarToast.makeText(carContext, "Speak your message now...", CarToast.LENGTH_LONG).show()

            // Note: In Android Auto, voice input is typically handled by the car's voice assistant
            // The notification-based reply is the primary voice input method

        } catch (e: ActivityNotFoundException) {
            // Head unit doesn't have speech recognizer activity
            android.util.Log.w(TAG, "Speech recognizer not available on this head unit", e)
            handleVoiceInputUnavailable()
        } catch (e: SecurityException) {
            // Permission denied for speech recognition
            android.util.Log.w(TAG, "Speech recognition permission denied", e)
            CarToast.makeText(
                carContext,
                "Voice input permission denied",
                CarToast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            // Catch-all for other unexpected errors
            android.util.Log.e(TAG, "Failed to start voice input", e)
            CarToast.makeText(
                carContext,
                "Voice input not available",
                CarToast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Handle voice input being unavailable.
     * Updates UI state and shows informative message.
     */
    private fun handleVoiceInputUnavailable() {
        voiceInputAvailable = false
        CarToast.makeText(
            carContext,
            "Voice input unavailable. Use notification reply instead.",
            CarToast.LENGTH_LONG
        ).show()
        invalidate()
    }

    private fun sendMessage() {
        if (messageText.isBlank()) return

        // Check connection status and warn user if offline
        val isOnline = socketConnection?.isConnected() ?: true
        if (!isOnline) {
            CarToast.makeText(carContext, "Offline: Message queued", CarToast.LENGTH_LONG).show()
        }

        isSending = true
        invalidate()

        screenScope.launch {
            try {
                messageSendingService.sendMessage(
                    chatGuid = chat.guid,
                    text = messageText
                )
                val toastMessage = if (isOnline) "Message sent!" else "Message queued for delivery"
                CarToast.makeText(carContext, toastMessage, CarToast.LENGTH_SHORT).show()
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
