package com.bothbubbles.services.avatar

import android.graphics.Bitmap
import androidx.core.graphics.drawable.IconCompat

/**
 * Centralized avatar resolution data.
 * Provides consistent avatar information across the app.
 *
 * @param avatarPath The resolved avatar path (content:// URI or file path)
 * @param displayName The display name for fallback avatar generation
 * @param hasContactInfo Whether this contact has saved contact info (prevents false business detection)
 * @param isBusiness Whether this is explicitly a business contact
 */
data class AvatarData(
    val avatarPath: String?,
    val displayName: String,
    val hasContactInfo: Boolean = false,
    val isBusiness: Boolean = false
)

/**
 * Centralized avatar resolution for a chat/conversation.
 *
 * @param groupAvatarPath Custom group photo path (takes precedence over participant collage)
 * @param participantNames List of participant names for group collage
 * @param participantAvatarPaths Avatar paths for each participant (corresponding to participantNames)
 * @param participantHasContactInfo Whether each participant has saved contact info
 * @param primaryAvatarPath Avatar path for 1:1 chats (first participant's photo)
 * @param displayName Display name for the chat (used for fallback generation)
 * @param isGroup Whether this is a group chat
 * @param hasContactInfo Whether the primary contact has saved info (for 1:1 chats)
 */
data class ChatAvatarData(
    val groupAvatarPath: String?,
    val participantNames: List<String>,
    val participantAvatarPaths: List<String?>,
    val participantHasContactInfo: List<Boolean>,
    val primaryAvatarPath: String?,
    val displayName: String,
    val isGroup: Boolean,
    val hasContactInfo: Boolean = false
)

/**
 * Interface for centralized avatar resolution.
 *
 * This service provides a single source of truth for avatar data across the app,
 * ensuring consistent avatar display in:
 * - Conversation list
 * - Chat header
 * - Push notifications
 * - Chat details/info screen
 * - Bubble shortcuts
 *
 * Implementation: [AvatarResolverImpl]
 */
interface AvatarResolver {

    /**
     * Resolve avatar data for a single participant/contact.
     *
     * @param address The participant's address (phone number or email)
     * @return Avatar data including path and contact info status
     */
    suspend fun resolveForParticipant(address: String): AvatarData

    /**
     * Resolve complete avatar data for a chat.
     * Handles both 1:1 and group chats with consistent priority:
     * 1. Custom group photo (groupAvatarPath)
     * 2. Participant photo collage (for groups)
     * 3. Primary participant photo (for 1:1)
     * 4. Generated avatar from initials
     *
     * @param chatGuid The chat's unique identifier
     * @return Complete avatar data for the chat
     */
    suspend fun resolveForChat(chatGuid: String): ChatAvatarData

    /**
     * Generate a notification-ready bitmap for a chat.
     * Uses the same resolution logic as [resolveForChat] but returns a bitmap.
     *
     * @param chatGuid The chat's unique identifier
     * @param sizePx The size of the bitmap in pixels
     * @param circleCrop Whether to crop to a circle
     * @return A bitmap ready for use in notifications
     */
    suspend fun generateChatAvatarBitmap(
        chatGuid: String,
        sizePx: Int,
        circleCrop: Boolean = false
    ): Bitmap

    /**
     * Generate a notification-ready IconCompat for a chat.
     * Uses adaptive bitmap format for proper display on all Android versions.
     *
     * @param chatGuid The chat's unique identifier
     * @param sizePx The size of the icon in pixels
     * @return An IconCompat ready for use in notifications/shortcuts
     */
    suspend fun generateChatIconCompat(
        chatGuid: String,
        sizePx: Int
    ): IconCompat

    /**
     * Generate an avatar bitmap for a specific sender in a chat.
     * Used for notifications where we need the message sender's avatar.
     *
     * @param senderAddress The sender's address
     * @param senderName Optional display name for the sender
     * @param sizePx The size of the bitmap in pixels
     * @param circleCrop Whether to crop to a circle
     * @return A bitmap for the sender's avatar
     */
    suspend fun generateSenderAvatarBitmap(
        senderAddress: String,
        senderName: String?,
        sizePx: Int,
        circleCrop: Boolean = false
    ): Bitmap

    /**
     * Invalidate cached avatar data for a participant.
     * Call this when a contact's photo is updated.
     *
     * @param address The participant's address
     */
    suspend fun invalidateParticipant(address: String)

    /**
     * Invalidate cached avatar data for a chat.
     * Call this when a chat's custom avatar or participants change.
     *
     * @param chatGuid The chat's unique identifier
     */
    suspend fun invalidateChat(chatGuid: String)

    /**
     * Invalidate all cached avatar data.
     * Call this when contacts are synced or bulk-updated.
     */
    suspend fun invalidateAll()
}
