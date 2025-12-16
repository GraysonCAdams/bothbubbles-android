package com.bothbubbles.fakes

import com.bothbubbles.services.sound.SoundPlayer
import com.bothbubbles.services.sound.SoundTheme

/**
 * Fake implementation of [SoundPlayer] for unit testing.
 *
 * This fake records all method calls without playing actual sounds.
 *
 * Usage:
 * ```kotlin
 * val fakeSound = FakeSoundManager()
 *
 * // Use in test
 * val delegate = ChatSendDelegate(soundPlayer = fakeSound, ...)
 * delegate.sendMessage(...)
 *
 * // Verify
 * assertEquals(1, fakeSound.sendSoundPlayCount)
 * ```
 */
class FakeSoundManager : SoundPlayer {

    // ===== Call Recording =====

    var sendSoundPlayCount = 0
        private set

    var receiveSoundPlayCount = 0
        private set

    val receiveSoundChatGuids = mutableListOf<String>()

    var previewSoundsCalls = mutableListOf<SoundTheme>()

    var releaseCallCount = 0
        private set

    // ===== Interface Methods =====

    override fun playSendSound() {
        sendSoundPlayCount++
    }

    override fun playReceiveSound(chatGuid: String) {
        receiveSoundPlayCount++
        receiveSoundChatGuids.add(chatGuid)
    }

    fun previewSounds(theme: SoundTheme) {
        previewSoundsCalls.add(theme)
    }

    fun release() {
        releaseCallCount++
    }

    // ===== Test Helpers =====

    fun reset() {
        sendSoundPlayCount = 0
        receiveSoundPlayCount = 0
        receiveSoundChatGuids.clear()
        previewSoundsCalls.clear()
        releaseCallCount = 0
    }
}
