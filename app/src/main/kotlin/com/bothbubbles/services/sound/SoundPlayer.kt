package com.bothbubbles.services.sound

/**
 * Interface for sound playback operations.
 * Allows mocking in tests without Android dependencies.
 *
 * This interface defines the contract for playing message-related sounds.
 * It intentionally exposes only the methods needed by UI layer consumers
 * (like ChatSendDelegate), keeping preview/configuration methods internal
 * to the concrete implementation.
 *
 * Implementation: [SoundManager]
 */
interface SoundPlayer {

    /**
     * Play the send message sound.
     * Only plays when app is in foreground (in-app sounds).
     * Background notifications use system default sounds.
     * Respects DND, sound mode, and user settings.
     */
    fun playSendSound()

    /**
     * Play the receive message sound.
     * Only plays when app is in foreground AND user is viewing the conversation.
     *
     * @param chatGuid The chat GUID for the incoming message
     */
    fun playReceiveSound(chatGuid: String)

    /**
     * Preview both sounds for a theme (receive then send).
     * Used when user selects a new sound theme in settings.
     *
     * @param theme The sound theme to preview
     */
    fun previewSounds(theme: SoundTheme)
}
