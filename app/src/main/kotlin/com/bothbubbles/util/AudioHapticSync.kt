package com.bothbubbles.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RawRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Audio-synchronized haptic feedback system.
 *
 * Plays haptic patterns synchronized with sound effects for rich tactile feedback.
 * Uses Android's VibrationEffect.Composition API (API 30+) for precise haptic timing.
 *
 * ## Usage
 *
 * ```kotlin
 * val audioHaptic = AudioHapticSync(context)
 *
 * // Play predefined pattern
 * audioHaptic.play(AudioHapticPattern.MESSAGE_SENT)
 *
 * // Play with custom sound
 * audioHaptic.playWithSound(R.raw.my_sound, AudioHapticPattern.NOTIFICATION)
 * ```
 *
 * ## Predefined Patterns
 *
 * - MESSAGE_SENT: Quick double-tap feel for sent confirmation
 * - MESSAGE_RECEIVED: Gentle notification pattern
 * - VOICE_RECORDING_START: Strong start feedback
 * - VOICE_RECORDING_STOP: Confirmation stop pattern
 * - REACTION_SELECTED: Light confirmation tap
 * - MODE_SWITCH: Toggle confirmation with ramp
 * - ERROR: Strong error indication
 *
 * @see AudioHapticPattern for available patterns
 */
class AudioHapticSync(private val context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var mediaPlayer: MediaPlayer? = null

    /**
     * Check if device supports haptic composition (API 30+).
     */
    val supportsComposition: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                vibrator.hasVibrator()

    /**
     * Check if device supports amplitude control for smooth haptics.
     */
    val supportsAmplitudeControl: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                vibrator.hasAmplitudeControl()

    /**
     * Play a predefined haptic pattern.
     *
     * @param pattern The haptic pattern to play
     */
    fun play(pattern: AudioHapticPattern) {
        if (!vibrator.hasVibrator()) return

        val effect = createVibrationEffect(pattern)
        vibrator.vibrate(effect)
    }

    /**
     * Play a haptic pattern synchronized with a sound resource.
     *
     * @param soundRes Raw resource ID of the sound file
     * @param pattern The haptic pattern to play
     * @param scope CoroutineScope for async operations
     */
    fun playWithSound(
        @RawRes soundRes: Int,
        pattern: AudioHapticPattern,
        scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.Main) {
            // Start both simultaneously
            launch { playSound(soundRes) }
            launch { play(pattern) }
        }
    }

    /**
     * Play a custom haptic sequence.
     *
     * @param sequence List of haptic events with timing
     */
    fun playSequence(sequence: List<HapticEvent>) {
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Use composition for precise timing
            val composition = VibrationEffect.startComposition()

            sequence.forEach { event ->
                when (event) {
                    is HapticEvent.Tick -> {
                        composition.addPrimitive(
                            VibrationEffect.Composition.PRIMITIVE_TICK,
                            event.intensity,
                            event.delayMs
                        )
                    }
                    is HapticEvent.Click -> {
                        composition.addPrimitive(
                            VibrationEffect.Composition.PRIMITIVE_CLICK,
                            event.intensity,
                            event.delayMs
                        )
                    }
                    is HapticEvent.Thud -> {
                        composition.addPrimitive(
                            VibrationEffect.Composition.PRIMITIVE_THUD,
                            event.intensity,
                            event.delayMs
                        )
                    }
                    is HapticEvent.Spin -> {
                        composition.addPrimitive(
                            VibrationEffect.Composition.PRIMITIVE_SPIN,
                            event.intensity,
                            event.delayMs
                        )
                    }
                    is HapticEvent.QuickRise -> {
                        composition.addPrimitive(
                            VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                            event.intensity,
                            event.delayMs
                        )
                    }
                    is HapticEvent.QuickFall -> {
                        composition.addPrimitive(
                            VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
                            event.intensity,
                            event.delayMs
                        )
                    }
                    is HapticEvent.SlowRise -> {
                        composition.addPrimitive(
                            VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
                            event.intensity,
                            event.delayMs
                        )
                    }
                }
            }

            vibrator.vibrate(composition.compose())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Fallback to waveform for older APIs
            val (timings, amplitudes) = convertToWaveform(sequence)
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            vibrator.vibrate(effect)
        }
    }

    /**
     * Create a VibrationEffect from a predefined pattern.
     */
    private fun createVibrationEffect(pattern: AudioHapticPattern): VibrationEffect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val composition = VibrationEffect.startComposition()

            pattern.events.forEach { event ->
                val primitive = when (event) {
                    is HapticEvent.Tick -> VibrationEffect.Composition.PRIMITIVE_TICK
                    is HapticEvent.Click -> VibrationEffect.Composition.PRIMITIVE_CLICK
                    is HapticEvent.Thud -> VibrationEffect.Composition.PRIMITIVE_THUD
                    is HapticEvent.Spin -> VibrationEffect.Composition.PRIMITIVE_SPIN
                    is HapticEvent.QuickRise -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
                    is HapticEvent.QuickFall -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
                    is HapticEvent.SlowRise -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
                }
                composition.addPrimitive(primitive, event.intensity, event.delayMs)
            }

            composition.compose()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Fallback to waveform
            val (timings, amplitudes) = convertToWaveform(pattern.events)
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            // Very old fallback
            @Suppress("DEPRECATION")
            VibrationEffect.createOneShot(pattern.fallbackDurationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    /**
     * Convert haptic events to waveform format for older APIs.
     */
    private fun convertToWaveform(events: List<HapticEvent>): Pair<LongArray, IntArray> {
        val timings = mutableListOf<Long>()
        val amplitudes = mutableListOf<Int>()

        events.forEachIndexed { index, event ->
            // Add delay if specified
            if (event.delayMs > 0) {
                timings.add(event.delayMs.toLong())
                amplitudes.add(0)
            }

            // Add the haptic event
            val duration = when (event) {
                is HapticEvent.Tick -> 10L
                is HapticEvent.Click -> 20L
                is HapticEvent.Thud -> 50L
                is HapticEvent.Spin -> 30L
                is HapticEvent.QuickRise -> 30L
                is HapticEvent.QuickFall -> 30L
                is HapticEvent.SlowRise -> 100L
            }
            val amplitude = (event.intensity * 255).toInt()

            timings.add(duration)
            amplitudes.add(amplitude)
        }

        return timings.toLongArray() to amplitudes.toIntArray()
    }

    /**
     * Play a sound resource.
     */
    private fun playSound(@RawRes soundRes: Int) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(context, soundRes)?.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setOnCompletionListener { mp ->
                    mp.release()
                    if (mediaPlayer == mp) {
                        mediaPlayer = null
                    }
                }
                start()
            }
        } catch (e: Exception) {
            // Silently fail - haptics will still play
        }
    }

    /**
     * Cancel any ongoing haptic playback.
     */
    fun cancel() {
        vibrator.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    /**
     * Release resources.
     */
    fun release() {
        cancel()
    }
}

/**
 * Individual haptic event in a pattern.
 *
 * @property intensity Haptic intensity from 0.0 to 1.0
 * @property delayMs Delay before this event in milliseconds
 */
sealed class HapticEvent(open val intensity: Float, open val delayMs: Int) {
    /** Light tick sensation */
    data class Tick(
        override val intensity: Float = 1f,
        override val delayMs: Int = 0
    ) : HapticEvent(intensity, delayMs)

    /** Medium click sensation */
    data class Click(
        override val intensity: Float = 1f,
        override val delayMs: Int = 0
    ) : HapticEvent(intensity, delayMs)

    /** Heavy thud sensation */
    data class Thud(
        override val intensity: Float = 1f,
        override val delayMs: Int = 0
    ) : HapticEvent(intensity, delayMs)

    /** Spinning/buzzing sensation */
    data class Spin(
        override val intensity: Float = 1f,
        override val delayMs: Int = 0
    ) : HapticEvent(intensity, delayMs)

    /** Quick rising intensity */
    data class QuickRise(
        override val intensity: Float = 1f,
        override val delayMs: Int = 0
    ) : HapticEvent(intensity, delayMs)

    /** Quick falling intensity */
    data class QuickFall(
        override val intensity: Float = 1f,
        override val delayMs: Int = 0
    ) : HapticEvent(intensity, delayMs)

    /** Slow rising intensity */
    data class SlowRise(
        override val intensity: Float = 1f,
        override val delayMs: Int = 0
    ) : HapticEvent(intensity, delayMs)
}

/**
 * Predefined haptic patterns synchronized with common sound effects.
 *
 * Each pattern is designed to complement specific audio feedback
 * for a cohesive audiovisual-haptic experience.
 */
enum class AudioHapticPattern(
    val events: List<HapticEvent>,
    val fallbackDurationMs: Long
) {
    /**
     * Message sent confirmation - quick double tap.
     * Designed for the "whoosh" send sound.
     */
    MESSAGE_SENT(
        events = listOf(
            HapticEvent.Click(intensity = 0.8f, delayMs = 0),
            HapticEvent.Tick(intensity = 0.4f, delayMs = 50)
        ),
        fallbackDurationMs = 50
    ),

    /**
     * Message received notification.
     * Designed for incoming message sounds.
     */
    MESSAGE_RECEIVED(
        events = listOf(
            HapticEvent.Click(intensity = 0.6f, delayMs = 0),
            HapticEvent.Tick(intensity = 0.3f, delayMs = 100),
            HapticEvent.Tick(intensity = 0.2f, delayMs = 50)
        ),
        fallbackDurationMs = 100
    ),

    /**
     * Voice recording started.
     * Strong feedback for recording initiation.
     */
    VOICE_RECORDING_START(
        events = listOf(
            HapticEvent.QuickRise(intensity = 0.9f, delayMs = 0),
            HapticEvent.Click(intensity = 1f, delayMs = 20)
        ),
        fallbackDurationMs = 80
    ),

    /**
     * Voice recording stopped.
     * Confirmation of recording completion.
     */
    VOICE_RECORDING_STOP(
        events = listOf(
            HapticEvent.Click(intensity = 0.8f, delayMs = 0),
            HapticEvent.QuickFall(intensity = 0.5f, delayMs = 30)
        ),
        fallbackDurationMs = 60
    ),

    /**
     * Reaction/tapback selected.
     * Light confirmation tap.
     */
    REACTION_SELECTED(
        events = listOf(
            HapticEvent.Click(intensity = 0.7f, delayMs = 0)
        ),
        fallbackDurationMs = 30
    ),

    /**
     * iMessage/SMS mode switch.
     * Toggle confirmation with ramp.
     */
    MODE_SWITCH(
        events = listOf(
            HapticEvent.QuickRise(intensity = 0.6f, delayMs = 0),
            HapticEvent.Thud(intensity = 0.8f, delayMs = 40)
        ),
        fallbackDurationMs = 70
    ),

    /**
     * Error indication.
     * Strong error feedback.
     */
    ERROR(
        events = listOf(
            HapticEvent.Thud(intensity = 1f, delayMs = 0),
            HapticEvent.Click(intensity = 0.6f, delayMs = 100),
            HapticEvent.Click(intensity = 0.4f, delayMs = 50)
        ),
        fallbackDurationMs = 150
    ),

    /**
     * Success confirmation.
     * Pleasant confirmation pattern.
     */
    SUCCESS(
        events = listOf(
            HapticEvent.QuickRise(intensity = 0.7f, delayMs = 0),
            HapticEvent.Click(intensity = 0.9f, delayMs = 30),
            HapticEvent.Tick(intensity = 0.3f, delayMs = 50)
        ),
        fallbackDurationMs = 80
    ),

    /**
     * Pull-to-refresh threshold reached.
     * Indicates ready to release.
     */
    PULL_THRESHOLD(
        events = listOf(
            HapticEvent.SlowRise(intensity = 0.6f, delayMs = 0),
            HapticEvent.Click(intensity = 0.8f, delayMs = 50)
        ),
        fallbackDurationMs = 100
    ),

    /**
     * Swipe action threshold crossed.
     * Feedback during swipe gestures.
     */
    SWIPE_THRESHOLD(
        events = listOf(
            HapticEvent.Click(intensity = 0.7f, delayMs = 0)
        ),
        fallbackDurationMs = 30
    ),

    /**
     * Attachment added.
     * Confirmation of adding an attachment.
     */
    ATTACHMENT_ADDED(
        events = listOf(
            HapticEvent.Tick(intensity = 0.6f, delayMs = 0),
            HapticEvent.Click(intensity = 0.5f, delayMs = 30)
        ),
        fallbackDurationMs = 50
    )
}

/**
 * Builder for creating custom haptic patterns.
 *
 * ```kotlin
 * val pattern = HapticPatternBuilder()
 *     .click(intensity = 0.8f)
 *     .delay(50)
 *     .tick(intensity = 0.4f)
 *     .build()
 * ```
 */
class HapticPatternBuilder {
    private val events = mutableListOf<HapticEvent>()
    private var nextDelay = 0

    fun tick(intensity: Float = 1f): HapticPatternBuilder {
        events.add(HapticEvent.Tick(intensity, nextDelay))
        nextDelay = 0
        return this
    }

    fun click(intensity: Float = 1f): HapticPatternBuilder {
        events.add(HapticEvent.Click(intensity, nextDelay))
        nextDelay = 0
        return this
    }

    fun thud(intensity: Float = 1f): HapticPatternBuilder {
        events.add(HapticEvent.Thud(intensity, nextDelay))
        nextDelay = 0
        return this
    }

    fun spin(intensity: Float = 1f): HapticPatternBuilder {
        events.add(HapticEvent.Spin(intensity, nextDelay))
        nextDelay = 0
        return this
    }

    fun quickRise(intensity: Float = 1f): HapticPatternBuilder {
        events.add(HapticEvent.QuickRise(intensity, nextDelay))
        nextDelay = 0
        return this
    }

    fun quickFall(intensity: Float = 1f): HapticPatternBuilder {
        events.add(HapticEvent.QuickFall(intensity, nextDelay))
        nextDelay = 0
        return this
    }

    fun slowRise(intensity: Float = 1f): HapticPatternBuilder {
        events.add(HapticEvent.SlowRise(intensity, nextDelay))
        nextDelay = 0
        return this
    }

    fun delay(ms: Int): HapticPatternBuilder {
        nextDelay = ms
        return this
    }

    fun build(): List<HapticEvent> = events.toList()
}
