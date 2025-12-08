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
import com.bothbubbles.services.AppLifecycleTracker
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages playback of send/receive message sounds.
 *
 * Features:
 * - Respects DND (Do Not Disturb) and silent/vibrate modes
 * - Uses media volume stream for playback
 * - User can disable sounds via settings
 */
@Singleton
class SoundManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val appLifecycleTracker: Lazy<AppLifecycleTracker>
) {
    companion object {
        private const val TAG = "SoundManager"
        private const val MAX_STREAMS = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private var soundPool: SoundPool? = null
    private var sendSoundId: Int = 0
    private var receiveSoundId: Int = 0
    private var isLoaded = false

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

        // Load sounds
        sendSoundId = soundPool?.load(context, R.raw.sound_send, 1) ?: 0
        receiveSoundId = soundPool?.load(context, R.raw.sound_receive, 1) ?: 0
    }

    /**
     * Play the send message sound.
     * Only plays when app is in foreground (in-app sounds).
     * Background notifications use system default sounds.
     * Respects DND, sound mode, and user settings.
     */
    fun playSendSound() {
        scope.launch {
            if (!appLifecycleTracker.get().isAppInForeground) {
                Log.d(TAG, "App in background - skipping send sound (use system notification)")
                return@launch
            }
            if (settingsDataStore.messageSoundsEnabled.first()) {
                playSound(sendSoundId)
            }
        }
    }

    /**
     * Play the receive message sound.
     * Only plays when app is in foreground (in-app sounds).
     * Background notifications use system default sounds.
     * Respects DND, sound mode, and user settings.
     */
    fun playReceiveSound() {
        scope.launch {
            if (!appLifecycleTracker.get().isAppInForeground) {
                Log.d(TAG, "App in background - skipping receive sound (use system notification)")
                return@launch
            }
            if (settingsDataStore.messageSoundsEnabled.first()) {
                playSound(receiveSoundId)
            }
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

        // Get current media volume as a ratio (0.0 to 1.0)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volumeRatio = if (maxVolume > 0) currentVolume.toFloat() / maxVolume else 0f

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
