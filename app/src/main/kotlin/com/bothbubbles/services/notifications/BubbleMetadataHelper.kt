package com.bothbubbles.services.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.bothbubbles.MainActivity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.ui.bubble.BubbleActivity
import com.bothbubbles.util.AvatarGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for creating bubble metadata and conversation shortcuts.
 * Handles bubble filtering logic and shortcut management.
 */
@Singleton
class BubbleMetadataHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    // Cached values to avoid blocking calls
    @Volatile
    private var cachedBubbleFilterMode: String = "all"
    @Volatile
    private var cachedSelectedBubbleChats: Set<String> = emptySet()

    init {
        // Initialize cached values in background
        applicationScope.launch(ioDispatcher) {
            cachedBubbleFilterMode = settingsDataStore.bubbleFilterMode.first()
            cachedSelectedBubbleChats = settingsDataStore.selectedBubbleChats.first()
        }
        // Keep cached values updated
        applicationScope.launch(ioDispatcher) {
            settingsDataStore.bubbleFilterMode.collect { mode ->
                cachedBubbleFilterMode = mode
            }
        }
        applicationScope.launch(ioDispatcher) {
            settingsDataStore.selectedBubbleChats.collect { chats ->
                cachedSelectedBubbleChats = chats
            }
        }
    }

    /**
     * Creates or updates a dynamic shortcut for a conversation.
     * This is required for bubble notifications to work properly.
     *
     * @param chatGuid Unique identifier for the chat
     * @param chatTitle Display name of the conversation
     * @param isGroup Whether this is a group conversation
     * @param participantNames List of participant names for group collage (optional)
     * @param chatAvatarPath Custom group photo path (takes precedence over collage)
     * @return The shortcut ID
     */
    fun createConversationShortcut(
        chatGuid: String,
        chatTitle: String,
        isGroup: Boolean,
        participantNames: List<String> = emptyList(),
        chatAvatarPath: String? = null
    ): String {
        val shortcutId = "chat_$chatGuid"

        // Create intent for the shortcut
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(NotificationChannelManager.EXTRA_CHAT_GUID, chatGuid)
        }

        // Generate avatar icon - priority: custom chat avatar > group collage > single avatar
        // Uses transparent background so no white circle appears behind the collage
        val avatarIcon: IconCompat = when {
            // Custom group photo takes precedence
            chatAvatarPath != null -> {
                val customBitmap = AvatarGenerator.loadContactPhotoBitmap(context, chatAvatarPath, 128)
                if (customBitmap != null) {
                    IconCompat.createWithBitmap(customBitmap)
                } else {
                    // Fallback if custom photo fails to load
                    if (isGroup && participantNames.size > 1) {
                        AvatarGenerator.generateGroupIconCompat(participantNames, 128)
                    } else {
                        AvatarGenerator.generateIconCompat(chatTitle, 128)
                    }
                }
            }
            // Group collage for groups without custom photo
            isGroup && participantNames.size > 1 -> {
                AvatarGenerator.generateGroupIconCompat(participantNames, 128)
            }
            // Single avatar for 1:1 chats
            else -> {
                AvatarGenerator.generateIconCompat(chatTitle, 128)
            }
        }

        // Create person for the conversation
        val person = Person.Builder()
            .setName(chatTitle)
            .setKey(chatGuid)
            .setIcon(avatarIcon)
            .build()

        // Build the shortcut with LocusId for bubble support
        val locusId = LocusIdCompat(chatGuid)
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(chatTitle)
            .setLongLabel(chatTitle)
            .setIcon(avatarIcon)
            .setIntent(intent)
            .setLongLived(true)
            .setIsConversation()
            .setLocusId(locusId)
            .setPerson(person)
            .setCategories(setOf("com.bothbubbles.category.SHARE_TARGET"))
            .build()

        // Push the shortcut
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)

        return shortcutId
    }

    /**
     * Determines if bubbles should be shown for a conversation based on the filter mode.
     * Uses cached bubble filter mode to avoid blocking the calling thread.
     *
     * @param chatGuid The chat GUID
     * @param senderAddress The sender's address (phone number or email)
     * @return true if bubbles should be shown
     */
    fun shouldShowBubble(chatGuid: String, senderAddress: String?): Boolean {
        return when (cachedBubbleFilterMode) {
            "none" -> false
            "all" -> true
            "selected" -> cachedSelectedBubbleChats.contains(chatGuid)
            // For favorites, we'd need to check Android contacts which requires async lookup
            // Default to true and let system handle it
            "favorites" -> true
            else -> true
        }
    }

    /**
     * Creates bubble metadata for a conversation notification.
     * This enables the notification to be displayed as a floating bubble.
     *
     * @param chatGuid Unique identifier for the chat
     * @param chatTitle Display name of the conversation
     * @param isGroup Whether this is a group conversation
     * @param participantNames List of participant names for group collage (optional)
     * @param chatAvatarPath Custom group photo path (takes precedence over collage)
     * @return BubbleMetadata for the notification, or null if bubbles aren't supported
     */
    fun createBubbleMetadata(
        chatGuid: String,
        chatTitle: String,
        isGroup: Boolean = false,
        participantNames: List<String> = emptyList(),
        chatAvatarPath: String? = null
    ): NotificationCompat.BubbleMetadata? {
        // Bubbles require Android Q (API 29) or higher
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Timber.d("Bubbles not supported: API level ${Build.VERSION.SDK_INT} < 29")
            return null
        }

        // Create intent for the bubble activity
        val bubbleIntent = BubbleActivity.createIntent(context, chatGuid, chatTitle)
        val bubblePendingIntent = PendingIntent.getActivity(
            context,
            chatGuid.hashCode() + 100, // Different request code than main intent
            bubbleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // Generate avatar icon - priority: custom chat avatar > group collage > single avatar
        // Uses transparent background so no white circle appears behind the collage
        val bubbleIcon: IconCompat = when {
            // Custom group photo takes precedence
            chatAvatarPath != null -> {
                val customBitmap = AvatarGenerator.loadContactPhotoBitmap(context, chatAvatarPath, 128)
                if (customBitmap != null) {
                    IconCompat.createWithBitmap(customBitmap)
                } else {
                    // Fallback if custom photo fails to load
                    if (isGroup && participantNames.size > 1) {
                        AvatarGenerator.generateGroupIconCompat(participantNames, 128)
                    } else {
                        AvatarGenerator.generateIconCompat(chatTitle, 128)
                    }
                }
            }
            // Group collage for groups without custom photo
            isGroup && participantNames.size > 1 -> {
                AvatarGenerator.generateGroupIconCompat(participantNames, 128)
            }
            // Single avatar for 1:1 chats
            else -> {
                AvatarGenerator.generateIconCompat(chatTitle, 128)
            }
        }

        // Build bubble metadata
        val metadata = NotificationCompat.BubbleMetadata.Builder(
            bubblePendingIntent,
            bubbleIcon
        )
            .setDesiredHeight(600)
            .setAutoExpandBubble(false)
            .setSuppressNotification(false)
            .build()

        Timber.d("Created bubble metadata for chat: $chatGuid (title: $chatTitle)")
        return metadata
    }
}
