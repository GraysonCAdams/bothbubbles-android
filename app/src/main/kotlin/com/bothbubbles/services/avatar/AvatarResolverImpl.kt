package com.bothbubbles.services.avatar

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.IconCompat
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.util.AvatarGenerator
import com.bothbubbles.util.ContactPhotoLoader
import com.bothbubbles.util.GroupAvatarRenderer
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized avatar resolution implementation.
 *
 * This service provides consistent avatar resolution across all parts of the app:
 * - Conversation list
 * - Chat header
 * - Push notifications
 * - Chat details screen
 * - Bubble shortcuts
 *
 * Priority for avatar resolution:
 * 1. customAvatarPath (user-set custom avatar)
 * 2. serverGroupPhotoPath (from iMessage group settings)
 * 3. Participant photo collage (for groups with 2+ participants)
 * 4. Primary participant's contact photo (for 1:1 chats)
 * 5. Generated avatar from initials
 *
 * Caching:
 * - Participant avatars are cached in HandleEntity.cachedAvatarPath
 * - Chat avatars use in-memory cache with TTL
 * - Shortcut icons are cached by Android ShortcutManager
 */
@Singleton
class AvatarResolverImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val unifiedChatDao: UnifiedChatDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : AvatarResolver {

    companion object {
        private const val TAG = "AvatarResolver"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    }

    // In-memory cache for chat avatar data
    private data class CachedChatAvatar(
        val data: ChatAvatarData,
        val timestamp: Long
    )

    private val chatCacheMutex = Mutex()
    private val chatCache = mutableMapOf<String, CachedChatAvatar>()

    // In-memory cache for participant avatar data
    private data class CachedParticipantAvatar(
        val data: AvatarData,
        val timestamp: Long
    )

    private val participantCacheMutex = Mutex()
    private val participantCache = mutableMapOf<String, CachedParticipantAvatar>()

    override suspend fun resolveForParticipant(address: String): AvatarData = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()

        // Check cache first
        participantCacheMutex.withLock {
            participantCache[address]?.let { cached ->
                if (now - cached.timestamp < CACHE_TTL_MS) {
                    return@withContext cached.data
                }
                participantCache.remove(address)
            }
        }

        // Lookup handle from database
        val handle = handleDao.getHandleByAddressAny(address)

        val data = if (handle != null) {
            AvatarData(
                avatarPath = handle.cachedAvatarPath,
                displayName = handle.displayName,
                hasContactInfo = handle.cachedDisplayName != null,
                isBusiness = false // Could add business detection here if needed
            )
        } else {
            // No handle found, use formatted address as display name
            AvatarData(
                avatarPath = null,
                displayName = PhoneNumberFormatter.format(address),
                hasContactInfo = false,
                isBusiness = false
            )
        }

        // Cache the result
        participantCacheMutex.withLock {
            participantCache[address] = CachedParticipantAvatar(data, now)
        }

        data
    }

    override suspend fun resolveForChat(chatGuid: String): ChatAvatarData = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()

        // Check cache first
        chatCacheMutex.withLock {
            chatCache[chatGuid]?.let { cached ->
                if (now - cached.timestamp < CACHE_TTL_MS) {
                    return@withContext cached.data
                }
                chatCache.remove(chatGuid)
            }
        }

        // Fetch chat entity
        val chat = chatDao.getChatByGuid(chatGuid)
        if (chat == null) {
            Timber.w("Chat not found: $chatGuid")
            return@withContext ChatAvatarData(
                groupAvatarPath = null,
                participantNames = emptyList(),
                participantAvatarPaths = emptyList(),
                participantHasContactInfo = emptyList(),
                primaryAvatarPath = null,
                displayName = "Unknown",
                isGroup = false,
                hasContactInfo = false
            )
        }

        // Fetch unified chat for effectiveAvatarPath
        val unifiedChat = chat.unifiedChatId?.let { unifiedChatDao.getById(it) }

        // Fetch participants
        val participants = chatDao.getParticipantsForChat(chatGuid)

        // Priority: customAvatarPath > serverGroupPhotoPath (from unified or chat)
        val groupAvatarPath = unifiedChat?.effectiveAvatarPath ?: chat.serverGroupPhotoPath

        val participantNames = participants.map { it.displayName }
        val participantAvatarPaths = participants.map { it.cachedAvatarPath }
        val participantHasContactInfo = participants.map { it.cachedDisplayName != null }

        val primaryParticipant = participants.firstOrNull()
        val primaryAvatarPath = primaryParticipant?.cachedAvatarPath
        val hasContactInfo = primaryParticipant?.cachedDisplayName != null

        // Resolve display name with consistent logic
        val displayName: String = when {
            !chat.isGroup && primaryParticipant != null -> primaryParticipant.displayName
            chat.isGroup && !chat.displayName.isNullOrBlank() -> chat.displayName!!
            chat.isGroup && participantNames.isNotEmpty() -> participantNames.joinToString(", ")
            else -> chat.chatIdentifier?.let { PhoneNumberFormatter.format(it) } ?: "Unknown"
        }

        val data = ChatAvatarData(
            groupAvatarPath = groupAvatarPath,
            participantNames = participantNames,
            participantAvatarPaths = participantAvatarPaths,
            participantHasContactInfo = participantHasContactInfo,
            primaryAvatarPath = primaryAvatarPath,
            displayName = displayName,
            isGroup = chat.isGroup,
            hasContactInfo = hasContactInfo
        )

        // Cache the result
        chatCacheMutex.withLock {
            chatCache[chatGuid] = CachedChatAvatar(data, now)
        }

        data
    }

    override suspend fun generateChatAvatarBitmap(
        chatGuid: String,
        sizePx: Int,
        circleCrop: Boolean
    ): Bitmap = withContext(ioDispatcher) {
        val avatarData = resolveForChat(chatGuid)

        // Priority 1: Custom/server group photo
        avatarData.groupAvatarPath?.let { path ->
            val bitmap = ContactPhotoLoader.loadContactPhotoBitmap(context, path, sizePx, circleCrop)
            if (bitmap != null) return@withContext bitmap
        }

        // Priority 2: Group collage for groups with 2+ participants
        if (avatarData.isGroup && avatarData.participantNames.size > 1) {
            return@withContext GroupAvatarRenderer.generateGroupCollageBitmapWithPhotos(
                context,
                avatarData.participantNames,
                avatarData.participantAvatarPaths,
                sizePx,
                circleCrop
            )
        }

        // Priority 3: Primary participant photo
        avatarData.primaryAvatarPath?.let { path ->
            val bitmap = ContactPhotoLoader.loadContactPhotoBitmap(context, path, sizePx, circleCrop)
            if (bitmap != null) return@withContext bitmap
        }

        // Priority 4: Generated avatar
        AvatarGenerator.generateBitmap(
            context = context,
            name = avatarData.displayName,
            sizePx = sizePx,
            hasContactInfo = avatarData.hasContactInfo,
            circleCrop = circleCrop
        )
    }

    override suspend fun generateChatIconCompat(
        chatGuid: String,
        sizePx: Int
    ): IconCompat = withContext(ioDispatcher) {
        val avatarData = resolveForChat(chatGuid)

        // Priority 1: Custom/server group photo
        avatarData.groupAvatarPath?.let { path ->
            val bitmap = ContactPhotoLoader.loadContactPhotoBitmap(context, path, sizePx, circleCrop = false)
            if (bitmap != null) {
                return@withContext IconCompat.createWithAdaptiveBitmap(bitmap)
            }
        }

        // Priority 2: Group collage for groups with 2+ participants
        if (avatarData.isGroup && avatarData.participantNames.size > 1) {
            return@withContext GroupAvatarRenderer.generateGroupAdaptiveIconCompatWithPhotos(
                context,
                avatarData.participantNames,
                avatarData.participantAvatarPaths,
                sizePx
            )
        }

        // Priority 3: Primary participant photo
        avatarData.primaryAvatarPath?.let { path ->
            val bitmap = ContactPhotoLoader.loadContactPhotoBitmap(context, path, sizePx, circleCrop = false)
            if (bitmap != null) {
                return@withContext IconCompat.createWithAdaptiveBitmap(bitmap)
            }
        }

        // Priority 4: Generated avatar
        AvatarGenerator.generateAdaptiveIconCompat(
            context = context,
            name = avatarData.displayName,
            sizePx = sizePx,
            hasContactInfo = avatarData.hasContactInfo
        )
    }

    override suspend fun generateSenderAvatarBitmap(
        senderAddress: String,
        senderName: String?,
        sizePx: Int,
        circleCrop: Boolean
    ): Bitmap = withContext(ioDispatcher) {
        val avatarData = resolveForParticipant(senderAddress)

        // Try to load contact photo
        avatarData.avatarPath?.let { path ->
            val bitmap = ContactPhotoLoader.loadContactPhotoBitmap(context, path, sizePx, circleCrop)
            if (bitmap != null) return@withContext bitmap
        }

        // Generate avatar with proper hasContactInfo
        val displayName = senderName ?: avatarData.displayName
        AvatarGenerator.generateBitmap(
            context = context,
            name = displayName,
            sizePx = sizePx,
            hasContactInfo = avatarData.hasContactInfo,
            circleCrop = circleCrop
        )
    }

    override suspend fun invalidateParticipant(address: String) {
        participantCacheMutex.withLock {
            participantCache.remove(address)
        }
        // Also invalidate any chats that might include this participant
        // For now, we just clear the whole chat cache as it's simpler
        invalidateAll()
    }

    override suspend fun invalidateChat(chatGuid: String) {
        chatCacheMutex.withLock {
            chatCache.remove(chatGuid)
        }
    }

    override suspend fun invalidateAll() {
        chatCacheMutex.withLock {
            chatCache.clear()
        }
        participantCacheMutex.withLock {
            participantCache.clear()
        }
    }
}
