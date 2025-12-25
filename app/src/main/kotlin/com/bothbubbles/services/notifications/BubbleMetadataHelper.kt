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

    // Track shortcuts created this session to avoid redundant creation
    // Shortcuts are still needed for share targets even with bubbles disabled
    private val createdShortcuts = mutableSetOf<String>()
    private val shortcutLock = Any()

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
     * @param senderAvatarPath Avatar path for 1:1 chat sender (contact photo URI)
     * @param senderHasContactInfo Whether the sender has saved contact info (prevents false business detection)
     * @param participantAvatarPaths Avatar paths for group participants (corresponding to participantNames)
     * @param participantHasContactInfo List of booleans indicating if each participant has saved contact info
     * @return The shortcut ID
     */
    fun createConversationShortcut(
        chatGuid: String,
        chatTitle: String,
        isGroup: Boolean,
        participantNames: List<String> = emptyList(),
        chatAvatarPath: String? = null,
        senderAvatarPath: String? = null,
        senderHasContactInfo: Boolean = false,
        participantAvatarPaths: List<String?> = emptyList(),
        participantHasContactInfo: List<Boolean> = emptyList()
    ): String {
        val shortcutId = "chat_$chatGuid"

        // Skip if shortcut was already created this session (lazy creation)
        synchronized(shortcutLock) {
            if (shortcutId in createdShortcuts) {
                return shortcutId
            }
        }

        // Create intent for the shortcut
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(NotificationChannelManager.EXTRA_CHAT_GUID, chatGuid)
        }

        // Generate avatar icon - priority: custom chat avatar > contact photo > group collage > generated
        val avatarIcon: IconCompat = when {
            // Custom group photo takes precedence
            chatAvatarPath != null -> {
                val customBitmap = AvatarGenerator.loadContactPhotoBitmap(context, chatAvatarPath, 128, circleCrop = false)
                if (customBitmap != null) {
                    IconCompat.createWithAdaptiveBitmap(customBitmap)
                } else {
                    // Fallback if custom photo fails to load
                    if (isGroup && participantNames.size > 1) {
                        AvatarGenerator.generateGroupAdaptiveIconCompatWithPhotos(context, participantNames, participantAvatarPaths, 128)
                    } else {
                        // Use hasContactInfo to prevent false business icon detection
                        val hasContactInfo = if (isGroup) {
                            participantHasContactInfo.firstOrNull() ?: false
                        } else {
                            senderHasContactInfo
                        }
                        AvatarGenerator.generateAdaptiveIconCompat(context, chatTitle, 128, hasContactInfo)
                    }
                }
            }
            // Group collage with photos for groups
            isGroup && participantNames.size > 1 -> {
                AvatarGenerator.generateGroupAdaptiveIconCompatWithPhotos(context, participantNames, participantAvatarPaths, 128)
            }
            // 1:1 chat - try to load sender's contact photo
            senderAvatarPath != null -> {
                val photoBitmap = AvatarGenerator.loadContactPhotoBitmap(context, senderAvatarPath, 128, circleCrop = false)
                if (photoBitmap != null) {
                    IconCompat.createWithAdaptiveBitmap(photoBitmap)
                } else {
                    // Pass senderHasContactInfo to prevent false business icon detection
                    AvatarGenerator.generateAdaptiveIconCompat(context, chatTitle, 128, senderHasContactInfo)
                }
            }
            // Fallback to generated avatar with proper hasContactInfo
            else -> {
                val hasContactInfo = if (isGroup) {
                    participantHasContactInfo.firstOrNull() ?: false
                } else {
                    senderHasContactInfo
                }
                AvatarGenerator.generateAdaptiveIconCompat(context, chatTitle, 128, hasContactInfo)
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

        // Mark as created to avoid redundant creation this session
        synchronized(shortcutLock) {
            createdShortcuts.add(shortcutId)
        }

        return shortcutId
    }

    /**
     * Determines if bubbles should be shown for a conversation based on the filter mode.
     *
     * NOTE: Android Chat Bubbles feature is currently disabled.
     * Always returns false to prevent bubble notifications.
     *
     * @param chatGuid The chat GUID
     * @param senderAddress The sender's address (phone number or email)
     * @return false - bubbles are disabled
     */
    fun shouldShowBubble(chatGuid: String, senderAddress: String?): Boolean {
        // Android Chat Bubbles feature disabled
        return false
    }

    /**
     * Creates bubble metadata for a conversation notification.
     * This enables the notification to be displayed as a floating bubble.
     *
     * NOTE: Android Chat Bubbles feature is currently disabled.
     * Always returns null to prevent bubble notifications.
     *
     * @param chatGuid Unique identifier for the chat
     * @param chatTitle Display name of the conversation
     * @param isGroup Whether this is a group conversation
     * @param participantNames List of participant names for group collage (optional)
     * @param chatAvatarPath Custom group photo path (takes precedence over collage)
     * @param senderAvatarPath Avatar path for 1:1 chat sender (contact photo URI)
     * @param participantAvatarPaths Avatar paths for group participants (corresponding to participantNames)
     * @param mergedGuids Comma-separated list of merged chat GUIDs for unified chat support
     * @return null - bubbles are disabled
     */
    fun createBubbleMetadata(
        chatGuid: String,
        chatTitle: String,
        isGroup: Boolean = false,
        participantNames: List<String> = emptyList(),
        chatAvatarPath: String? = null,
        senderAvatarPath: String? = null,
        participantAvatarPaths: List<String?> = emptyList(),
        mergedGuids: String? = null
    ): NotificationCompat.BubbleMetadata? {
        // Android Chat Bubbles feature disabled
        return null

        // Original implementation below (disabled):
        /*
        // Bubbles require Android Q (API 29) or higher
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Timber.d("Bubbles not supported: API level ${Build.VERSION.SDK_INT} < 29")
            return null
        }

        // Create intent for the bubble activity with unified chat support
        val bubbleIntent = BubbleActivity.createIntent(context, chatGuid, chatTitle, mergedGuids)
        val bubblePendingIntent = PendingIntent.getActivity(
            context,
            chatGuid.hashCode() + 100, // Different request code than main intent
            bubbleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // Generate avatar icon - priority: custom chat avatar > contact photo > group collage > generated
        val bubbleIcon: IconCompat = when {
            // Custom group photo takes precedence
            chatAvatarPath != null -> {
                val customBitmap = AvatarGenerator.loadContactPhotoBitmap(context, chatAvatarPath, 128, circleCrop = false)
                if (customBitmap != null) {
                    IconCompat.createWithAdaptiveBitmap(customBitmap)
                } else {
                    // Fallback if custom photo fails to load
                    if (isGroup && participantNames.size > 1) {
                        AvatarGenerator.generateGroupAdaptiveIconCompatWithPhotos(context, participantNames, participantAvatarPaths, 128)
                    } else {
                        AvatarGenerator.generateAdaptiveIconCompat(context, chatTitle, 128)
                    }
                }
            }
            // Group collage with photos for groups
            isGroup && participantNames.size > 1 -> {
                AvatarGenerator.generateGroupAdaptiveIconCompatWithPhotos(context, participantNames, participantAvatarPaths, 128)
            }
            // 1:1 chat - try to load sender's contact photo
            senderAvatarPath != null -> {
                val photoBitmap = AvatarGenerator.loadContactPhotoBitmap(context, senderAvatarPath, 128, circleCrop = false)
                if (photoBitmap != null) {
                    IconCompat.createWithAdaptiveBitmap(photoBitmap)
                } else {
                    AvatarGenerator.generateAdaptiveIconCompat(context, chatTitle, 128)
                }
            }
            // Fallback to generated avatar
            else -> {
                AvatarGenerator.generateAdaptiveIconCompat(context, chatTitle, 128)
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
        */
    }
}
