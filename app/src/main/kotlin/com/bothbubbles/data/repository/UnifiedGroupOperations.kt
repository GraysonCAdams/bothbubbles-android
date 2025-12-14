package com.bothbubbles.data.repository

import android.util.Log
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatMember
import com.bothbubbles.data.remote.api.dto.ChatDto
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles unified chat group operations for merging iMessage and SMS conversations.
 * Extracted from ChatRepository to keep files under 500 lines.
 */
@Singleton
class UnifiedGroupOperations @Inject constructor(
    private val unifiedChatGroupDao: UnifiedChatGroupDao
) {
    companion object {
        private const val TAG = "UnifiedGroupOps"
    }

    /**
     * Link a chat to a unified group if it has a single participant.
     * This enables merging iMessage and SMS conversations for the same contact.
     */
    suspend fun linkChatToUnifiedGroupIfNeeded(chatDto: ChatDto) {
        val participants = chatDto.participants
        if (participants != null && participants.size == 1) {
            val participant = participants.first()
            val normalizedPhone = PhoneAndCodeParsingUtils.normalizePhoneNumber(participant.address)
            linkChatToUnifiedGroup(chatDto.guid, normalizedPhone, chatDto.displayName)
        }
    }

    /**
     * Link an iMessage chat to a unified group, creating the group if necessary.
     * This enables merging iMessage and SMS conversations for the same contact.
     */
    suspend fun linkChatToUnifiedGroup(
        chatGuid: String,
        normalizedPhone: String,
        displayName: String?
    ) {
        // Skip if identifier is empty or looks like an email/RCS address (not a phone)
        // This prevents all non-phone chats from being lumped into a single group
        if (normalizedPhone.isBlank() || normalizedPhone.contains("@")) {
            Log.d(TAG, "linkChatToUnifiedGroup: Skipping $chatGuid - invalid identifier '$normalizedPhone'")
            return
        }

        Log.d(TAG, "linkChatToUnifiedGroup: Linking $chatGuid to group for '$normalizedPhone'")

        try {
            // Check if this chat is already in a unified group
            if (unifiedChatGroupDao.isChatInUnifiedGroup(chatGuid)) {
                Log.d(TAG, "linkChatToUnifiedGroup: $chatGuid is already in a unified group")
                return
            }

            // Check if unified group already exists for this phone number
            var group = unifiedChatGroupDao.getGroupByIdentifier(normalizedPhone)
            Log.d(TAG, "linkChatToUnifiedGroup: Existing group for '$normalizedPhone': ${group?.id}")

            if (group == null) {
                // Create new unified group with this iMessage chat as primary
                // Clean the displayName: strip service suffixes
                val cleanedDisplayName = displayName
                    ?.let { PhoneNumberFormatter.stripServiceSuffix(it) }
                    ?.takeIf { it.isValidDisplayName() }

                val newGroup = UnifiedChatGroupEntity(
                    identifier = normalizedPhone,
                    primaryChatGuid = chatGuid,
                    displayName = cleanedDisplayName
                )
                // Use atomic method to prevent FOREIGN KEY errors
                group = unifiedChatGroupDao.getOrCreateGroupAndAddMember(newGroup, chatGuid)
                Log.d(TAG, "linkChatToUnifiedGroup: Created/got group id=${group.id} and added member $chatGuid")

                // If SMS created the group first (during concurrent sync), claim primary for iMessage
                if (group.primaryChatGuid != chatGuid &&
                    (group.primaryChatGuid.startsWith("sms;") || group.primaryChatGuid.startsWith("mms;"))) {
                    Log.d(TAG, "linkChatToUnifiedGroup: Claiming primary for iMessage (was ${group.primaryChatGuid})")
                    unifiedChatGroupDao.updatePrimaryChatGuid(group.id, chatGuid)
                }
            } else {
                // Group exists, add this iMessage chat to it
                Log.d(TAG, "linkChatToUnifiedGroup: Adding $chatGuid to existing group ${group.id}")
                unifiedChatGroupDao.insertMember(
                    UnifiedChatMember(groupId = group.id, chatGuid = chatGuid)
                )

                // If SMS is currently primary, claim it for iMessage
                if (group.primaryChatGuid.startsWith("sms;") || group.primaryChatGuid.startsWith("mms;")) {
                    Log.d(TAG, "linkChatToUnifiedGroup: Claiming primary for iMessage (was ${group.primaryChatGuid})")
                    unifiedChatGroupDao.updatePrimaryChatGuid(group.id, chatGuid)
                }
            }

            Log.d(TAG, "linkChatToUnifiedGroup: Successfully linked $chatGuid to group ${group.id}")
        } catch (e: Exception) {
            Log.e(TAG, "linkChatToUnifiedGroup: FAILED to link $chatGuid to group for '$normalizedPhone'", e)
            throw e
        }
    }
}
