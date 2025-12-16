package com.bothbubbles.services.sound

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.util.Log
import com.bothbubbles.R
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.AppLifecycleTracker
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Available sound themes for message sounds.
 */
enum class SoundTheme(val displayName: String) {
    DEFAULT("Default"),
    HIGH("High"),
    POP("Pop")
}

/**
 * Manages playback of send/receive message sounds.
 *
 * Features:
 * - Multiple sound themes (Default, High, Pop)
 * - Respects DND (Do Not Disturb) and silent/vibrate modes
 * - Uses media volume stream for playback
 * - User can disable sounds via settings
 *
 * Implements [SoundPlayer] interface for testability in UI layer consumers.
 */
@Singleton
class SoundManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val appLifecycleTracker: Lazy<AppLifecycleTracker>,
    private val activeConversationManager: ActiveConversationManager,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : SoundPlayer {
    companion object {
        private const val TAG = "SoundManager"
        private const val MAX_STREAMS = 2
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private var soundPool: SoundPool? = null
    private var isLoaded = false

    // Sound IDs for each theme
    private var defaultSendId: Int = 0
    private var defaultReceiveId: Int = 0
    private var highSendId: Int = 0
    private var highReceiveId: Int = 0
    private var popSendId: Int = 0
    private var popReceiveId: Int = 0

    init {
        initializeSoundPool()
    }

    private fun initializeSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(audioAttributes)
            .build()
            .apply {
                setOnLoadCompleteListener { _, _, status ->
                    if (status == 0) {
                        isLoaded = true
                        Log.d(TAG, "Sounds loaded successfully")
                    }
                }
            }

        // Load all sound themes
        soundPool?.let { pool ->
            // Default theme
            defaultSendId = pool.load(context, R.raw.sound_send, 1)
            defaultReceiveId = pool.load(context, R.raw.sound_receive, 1)
            // High theme
            highSendId = pool.load(context, R.raw.sound_high_send, 1)
            highReceiveId = pool.load(context, R.raw.sound_high_receive, 1)
            // Pop theme
            popSendId = pool.load(context, R.raw.sound_pop_send, 1)
            popReceiveId = pool.load(context, R.raw.sound_pop_receive, 1)
        }
    }

    /**
     * Get the send sound ID for the given theme.
     */
    private fun getSendSoundId(theme: SoundTheme): Int = when (theme) {
        SoundTheme.DEFAULT -> defaultSendId
        SoundTheme.HIGH -> highSendId
        SoundTheme.POP -> popSendId
    }

    /**
     * Get the receive sound ID for the given theme.
     */
    private fun getReceiveSoundId(theme: SoundTheme): Int = when (theme) {
        SoundTheme.DEFAULT -> defaultReceiveId
        SoundTheme.HIGH -> highReceiveId
        SoundTheme.POP -> popReceiveId
    }

    /**
     * Play the send message sound.
     * Only plays when app is in foreground (in-app sounds).
     * Background notifications use system default sounds.
     * Respects DND, sound mode, and user settings.
     */
    override fun playSendSound() {
        applicationScope.launch(ioDispatcher) {
            if (!appLifecycleTracker.get().isAppInForeground) {
                Log.d(TAG, "App in background - skipping send sound (use system notification)")
                return@launch
            }
            if (settingsDataStore.messageSoundsEnabled.first()) {
                val theme = settingsDataStore.soundTheme.first()
                playSound(getSendSoundId(theme))
            }
        }
    }

    /**
     * Play the receive message sound.
     * Only plays when app is in foreground AND user is viewing the conversation.
     * For other conversations, the system notification will handle the sound.
     * Respects DND, sound mode, and user settings.
     *
     * @param chatGuid The chat GUID for the incoming message
     */
    override fun playReceiveSound(chatGuid: String) {
        applicationScope.launch(ioDispatcher) {
            if (!appLifecycleTracker.get().isAppInForeground) {
                Log.d(TAG, "App in background - skipping receive sound (use system notification)")
                return@launch
            }
            // Only play in-app sound if user is viewing this conversation
            if (!activeConversationManager.isConversationActive(chatGuid)) {
                Log.d(TAG, "Chat $chatGuid not active - skipping receive sound (use system notification)")
                return@launch
            }
            if (settingsDataStore.messageSoundsEnabled.first()) {
                val theme = settingsDataStore.soundTheme.first()
                playSound(getReceiveSoundId(theme))
            }
        }
    }

    /**
     * Preview both sounds for a theme (receive then send).
     * Used when user selects a new sound theme in settings.
     */
    override fun previewSounds(theme: SoundTheme) {
        applicationScope.launch(ioDispatcher) {
            // Play receive sound first
            playSoundDirect(getReceiveSoundId(theme))
            // Wait a moment, then play send sound
            delay(600)
            playSoundDirect(getSendSoundId(theme))
        }
    }

    private fun playSound(soundId: Int) {
        if (!isLoaded || soundId == 0) {
            Log.d(TAG, "Sound not loaded yet")
            return
        }

        if (!canPlaySound()) {
            Log.d(TAG, "Sound blocked by DND or sound mode")
            return
        }

        playSoundDirect(soundId)
    }

    /**
     * Play sound directly without checking DND/ringer mode.
     * Used for preview functionality.
     */
    private fun playSoundDirect(soundId: Int) {
        if (!isLoaded || soundId == 0) {
            Log.d(TAG, "Sound not loaded yet")
            return
        }

        // Get current media volume as a ratio (0.0 to 1.0)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volumeRatio = if (maxVolume > 0) currentVolume.toFloat() / maxVolume else 0.5f

        soundPool?.play(
            soundId,
            volumeRatio,  // left volume
            volumeRatio,  // right volume
            1,            // priority
            0,            // loop (0 = no loop)
            1.0f          // playback rate
        )
    }

    /**
     * Check if sound can be played based on DND and ringer mode.
     */
    private fun canPlaySound(): Boolean {
        // Check DND (Do Not Disturb) mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val interruptionFilter = notificationManager.currentInterruptionFilter
            if (interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                interruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            ) {
                // DND is active (priority, alarms only, or total silence)
                return false
            }
        }

        // Check ringer mode (silent or vibrate)
        return when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT,
            AudioManager.RINGER_MODE_VIBRATE -> false
            else -> true
        }
    }

    /**
     * Release resources when no longer needed.
     */
    fun release() {
        soundPool?.release()
        soundPool = null
        isLoaded = false
    }
}
