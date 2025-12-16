package com.bothbubbles.data.repository

import com.bothbubbles.data.local.db.dao.ChatDao
import timber.log.Timber
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.core.network.api.dto.ChatDto
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles chat participant management operations.
 * Extracted from ChatRepository to keep files under 500 lines.
 */
@Singleton
class ChatParticipantOperations @Inject constructor(
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val androidContactsService: AndroidContactsService
) {

    suspend fun getParticipantsForChat(chatGuid: String): List<HandleEntity> =
        chatDao.getParticipantsForChat(chatGuid)

    fun observeParticipantsForChat(chatGuid: String): Flow<List<HandleEntity>> =
        chatDao.observeParticipantsForChat(chatGuid)

    /**
     * Observe participants from multiple chats (for merged/unified conversations).
     * Combines and deduplicates participants from all specified chats.
     */
    fun observeParticipantsForChats(chatGuids: List<String>): Flow<List<HandleEntity>> {
        if (chatGuids.isEmpty()) return flowOf(emptyList())
        if (chatGuids.size == 1) return observeParticipantsForChat(chatGuids.first())

        return combine(
            chatGuids.map { guid -> chatDao.observeParticipantsForChat(guid) }
        ) { participantLists ->
            participantLists.flatMap { it.toList() }.distinctBy { it.id }
        }
    }

    /**
     * Get participants from multiple chats (one-shot query for merged/unified conversations).
     * Combines and deduplicates participants from all specified chats.
     * PERF: Uses batch DAO query instead of N+1 pattern.
     */
    suspend fun getParticipantsForChats(chatGuids: List<String>): List<HandleEntity> {
        if (chatGuids.isEmpty()) return emptyList()
        return chatDao.getParticipantsForChats(chatGuids)
    }

    /**
     * Get participants for multiple chats, grouped by chat GUID.
     * PERF: Fetches all participants in a single query and groups them.
     * @return Map of chat GUID to list of participants for that chat.
     */
    suspend fun getParticipantsGroupedByChat(chatGuids: List<String>): Map<String, List<HandleEntity>> {
        if (chatGuids.isEmpty()) return emptyMap()
        return chatDao.getParticipantsWithChatGuids(chatGuids)
            .groupBy({ it.chatGuid }, { it.handle })
    }

    /**
     * Find the "best" participant from a list - prefers one with saved contact info.
     * Used for displaying names/avatars in conversation lists.
     *
     * Priority:
     * 1. Participant with cachedDisplayName (saved contact)
     * 2. First participant in the list
     */
    fun getBestParticipant(participants: List<HandleEntity>): HandleEntity? {
        return participants.find { it.cachedDisplayName != null }
            ?: participants.firstOrNull()
    }

    /**
     * Get the first participant's phone number/address for a chat.
     * Used for blocking functionality.
     */
    suspend fun getChatParticipantAddress(chatGuid: String): String? {
        val participants = chatDao.getParticipantsForChat(chatGuid)
        return participants.firstOrNull()?.address
    }

    /**
     * Update cached contact info for a handle by address.
     * Used when a contact is added/modified in the device contacts.
     */
    suspend fun updateHandleCachedContactInfo(address: String, displayName: String?, avatarPath: String? = null) {
        val handles = handleDao.getHandlesByAddress(address)
        handles.forEach { handle ->
            handleDao.updateCachedContactInfo(handle.id, displayName, avatarPath)
        }
    }

    /**
     * Refresh contact info (display name and photo) for all handles from device contacts.
     * This should be called when:
     * - READ_CONTACTS permission is newly granted
     * - App starts with permission already granted (to catch contact changes)
     * Returns the number of handles updated.
     */
    suspend fun refreshAllContactInfo(): Int {
        if (!androidContactsService.hasReadPermission()) {
            Timber.d("refreshAllContactInfo: No READ_CONTACTS permission, skipping")
            return 0
        }

        var updatedCount = 0
        try {
            val allHandles = handleDao.getAllHandlesOnce()
            Timber.d("refreshAllContactInfo: Refreshing contact info for ${allHandles.size} handles")

            for (handle in allHandles) {
                val displayName = androidContactsService.getContactDisplayName(handle.address)
                val photoUri = androidContactsService.getContactPhotoUri(handle.address)

                // Only update if we found contact info or if there's existing cached info to clear
                if (displayName != handle.cachedDisplayName || photoUri != handle.cachedAvatarPath) {
                    handleDao.updateCachedContactInfo(handle.id, displayName, photoUri)
                    updatedCount++
                }
            }

            Timber.d("refreshAllContactInfo: Updated $updatedCount handles")
        } catch (e: Exception) {
            Timber.e(e, "refreshAllContactInfo: Error refreshing contact info")
        }

        return updatedCount
    }

    /**
     * Upsert handles for a chat and return their IDs.
     */
    suspend fun upsertHandlesForChat(chatDto: ChatDto): List<Long> {
        return chatDto.participants?.map { handleDto ->
            // Strip service suffixes (e.g., "(filtered)", "(smsft)") before contact lookup
            val cleanAddress = PhoneNumberFormatter.stripServiceSuffix(handleDto.address)

            // Look up contact info from device contacts using clean address
            val contactName = androidContactsService.getContactDisplayName(cleanAddress)
            val contactPhotoUri = androidContactsService.getContactPhotoUri(cleanAddress)

            val handle = HandleEntity(
                address = handleDto.address,  // Keep original for server sync
                service = handleDto.service,
                country = handleDto.country,
                formattedAddress = handleDto.formattedAddress
                    ?: PhoneNumberFormatter.format(cleanAddress),  // Generate if server doesn't provide
                defaultEmail = handleDto.defaultEmail,
                defaultPhone = handleDto.defaultPhone,
                originalRowId = handleDto.originalRowId,
                cachedDisplayName = contactName,
                cachedAvatarPath = contactPhotoUri
            )
            // Use upsert to get existing handle ID (insertHandle with REPLACE breaks cross-refs)
            handleDao.upsertHandle(handle)
        } ?: emptyList()
    }
}
