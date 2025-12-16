package com.bothbubbles.services.auto

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import timber.log.Timber
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Text-to-Speech utility for Android Auto.
 * Provides TTS functionality for reading messages aloud.
 *
 * Features:
 * - Proper initialization handling
 * - Utterance progress tracking
 * - Automatic cleanup
 */
class AutoTextToSpeech(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var initCallback: ((Boolean) -> Unit)? = null

    @Volatile
    var isSpeaking: Boolean = false
        private set

    /**
     * Initialize TTS engine. Must be called before speaking.
     */
    fun initialize(onReady: (Boolean) -> Unit) {
        if (isInitialized) {
            onReady(true)
            return
        }

        initCallback = onReady

        tts = TextToSpeech(context) { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            if (isInitialized) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(utteranceListener)
                Timber.d("TTS initialized successfully")
            } else {
                Timber.e("TTS initialization failed with status: $status")
            }
            initCallback?.invoke(isInitialized)
            initCallback = null
        }
    }

    /**
     * Speak the given text.
     *
     * @param text Text to speak
     * @param onStart Called when speech starts
     * @param onDone Called when speech completes
     * @param onError Called on error
     */
    fun speak(
        text: String,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (!isInitialized) {
            Timber.w("TTS not initialized, initializing now...")
            initialize { success ->
                if (success) {
                    speakInternal(text, onStart, onDone, onError)
                } else {
                    onError("TTS not available")
                }
            }
            return
        }

        speakInternal(text, onStart, onDone, onError)
    }

    private fun speakInternal(
        text: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val utteranceId = "auto_tts_${System.currentTimeMillis()}"
        currentCallbacks = TtsCallbacks(onStart, onDone, onError)

        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Timber.e("TTS speak failed with result: $result")
            onError("Failed to start speech")
            currentCallbacks = null
        }
    }

    /**
     * Speak a list of messages with sender attribution.
     *
     * @param messages List of pairs (senderName, messageText)
     * @param onComplete Called when all messages have been read
     */
    fun speakMessages(
        messages: List<Pair<String, String>>,
        onComplete: () -> Unit
    ) {
        if (messages.isEmpty()) {
            onComplete()
            return
        }

        // Build combined text with pauses between messages
        val combinedText = messages.joinToString(separator = ". ") { (sender, text) ->
            "$sender says: $text"
        }

        speak(
            text = combinedText,
            onDone = onComplete,
            onError = { onComplete() }
        )
    }

    /**
     * Stop any current speech.
     */
    fun stop() {
        tts?.stop()
        isSpeaking = false
        currentCallbacks = null
        Timber.d("TTS stopped")
    }

    /**
     * Release TTS resources. Call when done.
     */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Timber.d("TTS shutdown")
    }

    // Internal state for callbacks
    private var currentCallbacks: TtsCallbacks? = null

    private data class TtsCallbacks(
        val onStart: () -> Unit,
        val onDone: () -> Unit,
        val onError: (String) -> Unit
    )

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            isSpeaking = true
            currentCallbacks?.onStart?.invoke()
            Timber.d("TTS started: $utteranceId")
        }

        override fun onDone(utteranceId: String?) {
            isSpeaking = false
            currentCallbacks?.onDone?.invoke()
            currentCallbacks = null
            Timber.d("TTS done: $utteranceId")
        }

        @Deprecated("Deprecated in API 21+")
        override fun onError(utteranceId: String?) {
            isSpeaking = false
            currentCallbacks?.onError?.invoke("Speech error")
            currentCallbacks = null
            Timber.e("TTS error: $utteranceId")
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            isSpeaking = false
            currentCallbacks?.onError?.invoke("Speech error: $errorCode")
            currentCallbacks = null
            Timber.e("TTS error: $utteranceId, code: $errorCode")
        }
    }

    companion object {
        private const val TAG = "AutoTextToSpeech"
    }
}
