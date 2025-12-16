package com.bothbubbles.ui.chat

import android.content.Context
import android.media.MediaActionSound
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import java.io.File

/**
 * State holder for voice memo recording and playback.
 *
 * Encapsulates MediaRecorder and MediaPlayer logic for the chat composer.
 * Manages recording state, amplitude tracking, playback preview, and cleanup.
 *
 * Usage:
 * ```
 * val audioState = rememberChatAudioState()
 *
 * // Start recording
 * audioState.startRecording(context, hapticFeedback)
 *
 * // Stop recording and enter preview
 * audioState.stopRecording(hapticFeedback)
 *
 * // Play/pause preview
 * audioState.togglePlayback()
 *
 * // Send the recording
 * val uri = audioState.getRecordingUri()
 * audioState.reset()
 * ```
 */
@Stable
class ChatAudioState {
    // Recording state
    var isRecording by mutableStateOf(false)
        private set
    var recordingDuration by mutableLongStateOf(0L)
        private set
    var amplitudeHistory by mutableStateOf(List(20) { 0f })
        private set

    // Preview/playback state
    var isPreviewingVoiceMemo by mutableStateOf(false)
        private set
    var isPlayingVoiceMemo by mutableStateOf(false)
        private set
    var playbackPosition by mutableLongStateOf(0L)
        private set
    var playbackDuration by mutableLongStateOf(0L)
        private set

    // Noise cancellation toggle
    var isNoiseCancellationEnabled by mutableStateOf(true)
        private set

    // Internal state
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var recordingFile: File? = null
    private var mediaActionSound: MediaActionSound? = null
    private var soundsLoaded = false

    /**
     * Initialize the audio state with a MediaActionSound.
     * Call this in a DisposableEffect to handle cleanup.
     * Note: Sounds are loaded lazily on first use to avoid blocking the main thread.
     */
    fun initialize(): () -> Unit {
        mediaActionSound = MediaActionSound()

        return {
            cleanup()
        }
    }

    /**
     * Lazily load sounds on first use to avoid blocking the main thread during composition.
     */
    private fun ensureSoundsLoaded() {
        if (!soundsLoaded && mediaActionSound != null) {
            mediaActionSound?.load(MediaActionSound.START_VIDEO_RECORDING)
            mediaActionSound?.load(MediaActionSound.STOP_VIDEO_RECORDING)
            soundsLoaded = true
        }
    }

    /**
     * Start voice memo recording.
     *
     * @param context Android context for file creation
     * @param hapticFeedback For tactile feedback
     * @param onError Callback if recording fails
     */
    fun startRecording(
        context: Context,
        hapticFeedback: HapticFeedback,
        onError: (String) -> Unit = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    ) {
        startVoiceMemoRecording(
            context = context,
            enableNoiseCancellation = isNoiseCancellationEnabled,
            onRecorderCreated = { recorder, file ->
                mediaRecorder = recorder
                recordingFile = file
                isRecording = true
                ensureSoundsLoaded()
                mediaActionSound?.play(MediaActionSound.START_VIDEO_RECORDING)
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onError = onError
        )
    }

    /**
     * Stop recording and transition to preview mode.
     *
     * @param hapticFeedback For tactile feedback
     */
    fun stopRecording(hapticFeedback: HapticFeedback) {
        if (!isRecording) return

        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            ensureSoundsLoaded()
            mediaActionSound?.play(MediaActionSound.STOP_VIDEO_RECORDING)
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        } catch (_: Exception) {
            // May fail if recording was too short
        }
        mediaRecorder = null
        isRecording = false

        // Transition to preview mode if we have a recording
        if (recordingFile?.exists() == true) {
            isPreviewingVoiceMemo = true
            playbackDuration = recordingDuration
        }
    }

    /**
     * Cancel recording without saving.
     */
    fun cancelRecording() {
        // Clean up recording state
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        isRecording = false

        // Clean up preview/playback state
        mediaPlayer?.release()
        mediaPlayer = null
        isPlayingVoiceMemo = false
        isPreviewingVoiceMemo = false
        playbackPosition = 0L

        // Delete recording file
        recordingFile?.delete()
        recordingFile = null
    }

    /**
     * Restart recording (discard current and start fresh).
     *
     * @param context Android context for file creation
     * @param hapticFeedback For tactile feedback
     * @param onError Callback if recording fails
     */
    fun restartRecording(
        context: Context,
        hapticFeedback: HapticFeedback,
        onError: (String) -> Unit = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    ) {
        // Stop current recording
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        recordingFile?.delete()

        // Start new recording
        startRecording(context, hapticFeedback, onError)
    }

    /**
     * Re-record from preview mode (discard current preview and start fresh).
     *
     * @param context Android context for file creation
     * @param hapticFeedback For tactile feedback
     * @param onError Callback if recording fails
     */
    fun reRecordFromPreview(
        context: Context,
        hapticFeedback: HapticFeedback,
        onError: (String) -> Unit = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    ) {
        // Clean up preview state
        mediaPlayer?.release()
        mediaPlayer = null
        isPlayingVoiceMemo = false
        playbackPosition = 0L
        isPreviewingVoiceMemo = false
        recordingFile?.delete()
        recordingFile = null

        // Start new recording
        startRecording(context, hapticFeedback, onError)
    }

    /**
     * Toggle playback in preview mode.
     */
    fun togglePlayback() {
        if (isPlayingVoiceMemo) {
            mediaPlayer?.pause()
            isPlayingVoiceMemo = false
        } else {
            if (mediaPlayer == null && recordingFile != null) {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(recordingFile!!.absolutePath)
                    prepare()
                    setOnCompletionListener {
                        isPlayingVoiceMemo = false
                        playbackPosition = 0L
                    }
                }
            }
            mediaPlayer?.start()
            isPlayingVoiceMemo = true
        }
    }

    /**
     * Toggle noise cancellation setting.
     */
    fun toggleNoiseCancellation() {
        isNoiseCancellationEnabled = !isNoiseCancellationEnabled
    }

    /**
     * Get the URI of the recorded file for sending.
     */
    fun getRecordingUri(): Uri? = recordingFile?.toUri()

    /**
     * Reset state after sending voice memo.
     * Does NOT delete the file (it's been passed to the send flow).
     */
    fun resetAfterSend() {
        mediaPlayer?.release()
        mediaPlayer = null
        isPlayingVoiceMemo = false
        isPreviewingVoiceMemo = false
        playbackPosition = 0L
        recordingFile = null
    }

    /**
     * Update amplitude history during recording.
     * Called from a coroutine that samples amplitude at regular intervals.
     */
    internal fun updateAmplitude() {
        try {
            val amplitude = mediaRecorder?.maxAmplitude ?: 0
            // Normalize to 0-1 range (maxAmplitude can be up to 32767)
            val normalized = (amplitude / 32767f).coerceIn(0f, 1f)
            amplitudeHistory = amplitudeHistory.drop(1) + normalized
        } catch (_: Exception) {
            // Recorder may have been released
        }
    }

    /**
     * Increment recording duration.
     * Called from a coroutine that tracks elapsed time.
     */
    internal fun incrementDuration(ms: Long) {
        recordingDuration += ms
    }

    /**
     * Update playback position.
     * Called from a coroutine that tracks playback progress.
     */
    internal fun updatePlaybackPosition() {
        try {
            playbackPosition = mediaPlayer?.currentPosition?.toLong() ?: 0L
            if (mediaPlayer?.isPlaying == false) {
                isPlayingVoiceMemo = false
                playbackPosition = 0L
            }
        } catch (_: Exception) {
            isPlayingVoiceMemo = false
        }
    }

    /**
     * Reset recording duration and amplitude for a new recording.
     */
    internal fun resetRecordingState() {
        recordingDuration = 0L
        amplitudeHistory = List(20) { 0f }
    }

    /**
     * Clean up all resources.
     */
    private fun cleanup() {
        mediaRecorder?.release()
        mediaRecorder = null
        mediaPlayer?.release()
        mediaPlayer = null
        mediaActionSound?.release()
        mediaActionSound = null
        soundsLoaded = false
    }
}

/**
 * Remember a ChatAudioState instance with proper lifecycle management.
 * Handles cleanup on dispose.
 */
@Composable
fun rememberChatAudioState(): ChatAudioState {
    val audioState = remember { ChatAudioState() }

    DisposableEffect(Unit) {
        val cleanup = audioState.initialize()
        onDispose {
            cleanup()
        }
    }

    return audioState
}

/**
 * Side effect that tracks recording duration and amplitude.
 * Should be called while recording is active.
 */
@Composable
fun RecordingDurationEffect(
    audioState: ChatAudioState
) {
    LaunchedEffect(audioState.isRecording) {
        if (audioState.isRecording) {
            audioState.resetRecordingState()
            while (audioState.isRecording) {
                delay(100L)
                audioState.incrementDuration(100L)
                audioState.updateAmplitude()
            }
        }
    }
}

/**
 * Side effect that tracks playback position.
 * Should be called while playback is active.
 */
@Composable
fun PlaybackPositionEffect(
    audioState: ChatAudioState
) {
    LaunchedEffect(audioState.isPlayingVoiceMemo) {
        if (audioState.isPlayingVoiceMemo) {
            while (audioState.isPlayingVoiceMemo) {
                audioState.updatePlaybackPosition()
                delay(50L)
            }
        }
    }
}

/**
 * Combined effect for audio state tracking.
 * Handles both recording duration/amplitude and playback position.
 */
@Composable
fun ChatAudioEffects(audioState: ChatAudioState) {
    RecordingDurationEffect(audioState)
    PlaybackPositionEffect(audioState)
}
