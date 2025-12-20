package com.bothbubbles.services.media

import android.content.Context
import timber.log.Timber
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pool of ExoPlayer instances for efficient video playback.
 *
 * Creating and destroying ExoPlayer instances is expensive. This pool
 * maintains a small number of reusable players that can be borrowed
 * for video playback and returned when done.
 *
 * Usage:
 * 1. Call [acquire] to get a player for a specific attachment
 * 2. Configure the player with your media source
 * 3. Call [release] when the video goes off-screen
 *
 * The pool automatically handles:
 * - Reusing players when possible
 * - Limiting total players to prevent memory issues
 * - Stopping playback when players are released
 */
@Singleton
class ExoPlayerPool @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ExoPlayerPool"
        private const val MAX_POOL_SIZE = 2 // Maximum players to keep pooled
        private const val MAX_ACTIVE_PLAYERS = 1 // Only one video plays at a time
    }

    // Active players currently in use, keyed by attachment GUID
    private val activePlayers = ConcurrentHashMap<String, ExoPlayer>()

    // Pool of available players ready for reuse
    private val availablePlayers = mutableListOf<ExoPlayer>()

    // Lock for thread-safe pool operations
    private val lock = Any()

    /**
     * Acquire a player for the given attachment.
     *
     * If a player is already active for this attachment, returns that player.
     * Otherwise, retrieves one from the pool or creates a new one.
     *
     * @param attachmentGuid Unique identifier for the attachment
     * @return An ExoPlayer instance ready for use
     */
    fun acquire(attachmentGuid: String): ExoPlayer = synchronized(lock) {
        // Check if we already have an active player for this attachment
        activePlayers[attachmentGuid]?.let { return it }

        // Try to get a player from the pool
        val player = if (availablePlayers.isNotEmpty()) {
            val pooled = availablePlayers.removeAt(availablePlayers.size - 1)
            Timber.d("Reusing pooled player for $attachmentGuid")
            pooled
        } else {
            // Create new player if under limit
            Timber.d("Creating new player for $attachmentGuid")
            createPlayer()
        }

        // If we have too many active players, evict the oldest one
        if (activePlayers.size >= MAX_ACTIVE_PLAYERS) {
            evictOldestPlayer()
        }

        activePlayers[attachmentGuid] = player
        return player
    }

    /**
     * Release a player back to the pool.
     *
     * The player is stopped and its media source is cleared. If the pool
     * is full, the player is released entirely.
     *
     * @param attachmentGuid Unique identifier for the attachment
     */
    fun release(attachmentGuid: String) {
        val player = activePlayers.remove(attachmentGuid) ?: return

        synchronized(lock) {
            // Stop playback and clear media
            player.stop()
            player.clearMediaItems()

            if (availablePlayers.size < MAX_POOL_SIZE) {
                availablePlayers.add(player)
                Timber.d("Returned player to pool, pool size: ${availablePlayers.size}")
            } else {
                player.release()
                Timber.d("Released player (pool full)")
            }
        }
    }

    /**
     * Prepare a player with a video source.
     *
     * @param attachmentGuid Unique identifier for the attachment
     * @param videoUri URI of the video to play
     * @return The prepared player
     */
    fun prepareForVideo(attachmentGuid: String, videoUri: String): ExoPlayer {
        val player = acquire(attachmentGuid)
        val mediaItem = MediaItem.fromUri(videoUri)
        player.setMediaItem(mediaItem)
        player.prepare()
        return player
    }

    /**
     * Release all players and clear the pool.
     *
     * Call this when the app is going to the background or when
     * memory is low.
     */
    fun releaseAll() {
        Timber.d("Releasing all players")

        // Release active players
        activePlayers.values.forEach { player ->
            player.stop()
            player.release()
        }
        activePlayers.clear()

        // Release pooled players
        synchronized(lock) {
            availablePlayers.forEach { it.release() }
            availablePlayers.clear()
        }
    }

    /**
     * Get the count of currently active players.
     */
    fun activeCount(): Int = activePlayers.size

    /**
     * Get the count of players in the pool.
     */
    fun pooledCount(): Int = synchronized(lock) { availablePlayers.size }

    private fun createPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context)
            .build()
            .apply {
                playWhenReady = false
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    private fun evictOldestPlayer() {
        // Find the first (oldest) player and release it
        val oldestKey = activePlayers.keys.firstOrNull() ?: return
        Timber.d("Evicting oldest player: $oldestKey")
        release(oldestKey)
    }
}
