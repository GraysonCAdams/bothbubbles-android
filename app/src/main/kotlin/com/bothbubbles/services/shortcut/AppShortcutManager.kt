package com.bothbubbles.services.shortcut

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.bothbubbles.MainActivity
import com.bothbubbles.data.repository.PopularChatsRepository
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.notifications.NotificationChannelManager
import com.bothbubbles.util.AvatarGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages dynamic app shortcuts that appear when long-pressing the app icon.
 *
 * These shortcuts show the top 3 most popular (most engaged) chats based on
 * message frequency in the last 30 days. Tapping a shortcut opens that chat directly.
 *
 * Unlike share targets (handled by [ShortcutService]), these are launcher shortcuts
 * that always appear in the long-press menu, not just in the share sheet.
 *
 * Features:
 * - Automatically updates when chat popularity changes
 * - Debounced updates to prevent excessive processing
 * - Respects Android's shortcut rate limiting
 * - Filters out archived and deleted chats
 */
@Singleton
class AppShortcutManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val popularChatsRepository: PopularChatsRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "AppShortcutManager"
        private const val DEBOUNCE_MS = 5000L // 5 seconds debounce
        private const val MAX_SHORTCUTS = 3
        private const val SHORTCUT_ID_PREFIX = "popular_"
    }

    private var observationJob: Job? = null
    private var debounceJob: Job? = null

    private val _isObserving = MutableStateFlow(false)
    val isObserving: StateFlow<Boolean> = _isObserving.asStateFlow()

    /**
     * Start observing popular chats and updating launcher shortcuts.
     *
     * Should be called when the app starts (e.g., in Application.onCreate()).
     * Uses a Flow to reactively update shortcuts when chat engagement changes.
     */
    fun startObserving() {
        if (_isObserving.value) {
            Timber.tag(TAG).d("Already observing, skipping start")
            return
        }

        Timber.tag(TAG).d("Starting launcher shortcut observation")

        observationJob = applicationScope.launch(ioDispatcher) {
            popularChatsRepository.observePopularChatsForLauncher()
                .distinctUntilChanged()
                .collect { popularChats ->
                    debounceAndUpdate(popularChats)
                }
        }

        _isObserving.value = true
    }

    /**
     * Stop observing and remove all managed shortcuts.
     */
    fun stopObserving() {
        Timber.tag(TAG).d("Stopping launcher shortcut observation")

        observationJob?.cancel()
        observationJob = null
        debounceJob?.cancel()
        debounceJob = null

        // Remove our managed shortcuts (leave share targets from ShortcutService)
        try {
            val shortcutIds = (0 until MAX_SHORTCUTS).map { "$SHORTCUT_ID_PREFIX$it" }
            ShortcutManagerCompat.removeDynamicShortcuts(context, shortcutIds)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error removing shortcuts")
        }

        _isObserving.value = false
    }

    /**
     * Force an immediate update of shortcuts.
     * Useful after significant changes like completing initial sync.
     */
    suspend fun refreshNow() {
        Timber.tag(TAG).d("Forcing immediate shortcut refresh")
        debounceJob?.cancel()
        val popularChats = popularChatsRepository.getPopularChatsForLauncher()
        updateShortcuts(popularChats)
    }

    /**
     * Debounce updates to prevent rapid shortcut publishing.
     */
    private fun debounceAndUpdate(popularChats: List<PopularChatsRepository.PopularChat>) {
        debounceJob?.cancel()
        debounceJob = applicationScope.launch(ioDispatcher) {
            delay(DEBOUNCE_MS)
            updateShortcuts(popularChats)
        }
    }

    /**
     * Update the launcher shortcuts with the given popular chats.
     */
    private fun updateShortcuts(popularChats: List<PopularChatsRepository.PopularChat>) {
        try {
            // Check rate limiting
            if (ShortcutManagerCompat.isRateLimitingActive(context)) {
                Timber.tag(TAG).w("Shortcut rate limiting active, skipping update")
                return
            }

            // Remove existing shortcuts first
            val existingIds = (0 until MAX_SHORTCUTS).map { "$SHORTCUT_ID_PREFIX$it" }
            ShortcutManagerCompat.removeDynamicShortcuts(context, existingIds)

            if (popularChats.isEmpty()) {
                Timber.tag(TAG).d("No popular chats to publish as shortcuts")
                return
            }

            // Build and publish shortcuts
            val shortcuts = popularChats.take(MAX_SHORTCUTS).mapIndexed { index, chat ->
                buildShortcut(chat, index)
            }

            Timber.tag(TAG).d("Publishing ${shortcuts.size} launcher shortcuts")

            shortcuts.forEach { shortcut ->
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            }

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error updating launcher shortcuts")
        }
    }

    /**
     * Build a ShortcutInfoCompat for a popular chat.
     */
    private fun buildShortcut(
        chat: PopularChatsRepository.PopularChat,
        rank: Int
    ): ShortcutInfoCompat {
        val shortcutId = "$SHORTCUT_ID_PREFIX$rank"

        // Intent to open the chat directly
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(NotificationChannelManager.EXTRA_CHAT_GUID, chat.chatGuid)
            // Flags to ensure proper task handling
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        // Generate avatar icon
        val avatarIcon = AvatarGenerator.generateIconCompat(context, chat.displayName, 128)

        return ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(chat.displayName)
            .setLongLabel(chat.displayName)
            .setIcon(avatarIcon)
            .setIntent(intent)
            .setRank(rank)
            .build()
    }
}
