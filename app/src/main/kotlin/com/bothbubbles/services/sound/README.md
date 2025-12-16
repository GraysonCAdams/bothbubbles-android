# Sound Manager

## Purpose

Manage sound playback for message send/receive and other UI sounds.

## Files

| File | Description |
|------|-------------|
| `SoundManager.kt` | Play notification and UI sounds |

## Architecture

```
Sound Playback:

Event → SoundManager
      → Check user preferences
      → Select appropriate sound
      → Play via MediaPlayer/SoundPool
```

## Required Patterns

### Sound Manager

```kotlin
class SoundManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationPreferences: NotificationPreferences
) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(5)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val sendSoundId = soundPool.load(context, R.raw.message_sent, 1)
    private val receiveSoundId = soundPool.load(context, R.raw.message_received, 1)

    suspend fun playMessageSent() {
        if (!notificationPreferences.soundEnabled.first()) return
        soundPool.play(sendSoundId, 1f, 1f, 1, 0, 1f)
    }

    suspend fun playMessageReceived() {
        if (!notificationPreferences.soundEnabled.first()) return
        soundPool.play(receiveSoundId, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
    }
}
```

## Best Practices

1. Respect user sound preferences
2. Use SoundPool for short UI sounds (more efficient than MediaPlayer)
3. Preload sounds on initialization
4. Release resources when not needed
5. Handle audio focus appropriately
