package com.bothbubbles.services.auto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Audio player for Android Auto that handles media playback with proper audio focus.
 *
 * Features:
 * - Proper audio focus management (ducks other audio during playback)
 * - Automatic resource cleanup
 * - Error handling with callbacks
 */
class AutoAudioPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // Playback state
    @Volatile
    var isPlaying: Boolean = false
        private set

    /**
     * Play an audio file from the given path.
     *
     * @param filePath Path to the audio file
     * @param onComplete Callback when playback completes
     * @param onError Callback when an error occurs
     */
    suspend fun playAudio(
        filePath: String,
        onComplete: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) = withContext(Dispatchers.Main) {
        try {
            // Stop any existing playback
            stop()

            val file = File(filePath)
            if (!file.exists()) {
                throw Exception("Audio file not found: $filePath")
            }

            // Request audio focus
            val focusGranted = requestAudioFocus()
            if (!focusGranted) {
                throw Exception("Could not obtain audio focus")
            }

            // Create and configure MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(filePath)

                setOnPreparedListener {
                    this@AutoAudioPlayer.isPlaying = true
                    start()
                    Timber.d("Started audio playback: $filePath")
                }

                setOnCompletionListener {
                    Timber.d("Audio playback completed")
                    stop()
                    onComplete()
                }

                setOnErrorListener { _, what, extra ->
                    Timber.e("MediaPlayer error: what=$what, extra=$extra")
                    stop()
                    onError(Exception("Playback error: $what"))
                    true
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to play audio")
            stop()
            onError(e)
        }
    }

    /**
     * Stop current playback and release resources.
     */
    fun stop() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.reset()
                player.release()
            }
            mediaPlayer = null
            isPlaying = false

            // Abandon audio focus
            abandonAudioFocus()
            Timber.d("Stopped audio playback")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping audio")
        }
    }

    /**
     * Request audio focus for playback.
     * Uses AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK to duck other audio.
     */
    private fun requestAudioFocus(): Boolean {
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Timber.d("Audio focus lost, stopping playback")
                        stop()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        // Could lower volume here if needed
                    }
                }
            }
            .build()

        audioFocusRequest = focusRequest
        val result = audioManager.requestAudioFocus(focusRequest)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Timber.d("Audio focus request result: $result (granted=$granted)")
        return granted
    }

    /**
     * Abandon audio focus when done.
     */
    private fun abandonAudioFocus() {
        audioFocusRequest?.let { request ->
            audioManager.abandonAudioFocusRequest(request)
            audioFocusRequest = null
            Timber.d("Audio focus abandoned")
        }
    }

    companion object {
        private const val TAG = "AutoAudioPlayer"
    }
}
