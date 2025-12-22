package com.bothbubbles.services.life360

import com.bothbubbles.core.data.prefs.FeaturePreferences
import com.bothbubbles.data.repository.Life360Repository
import com.bothbubbles.ui.chat.integration.ChatHeaderContent
import com.bothbubbles.ui.chat.integration.ChatHeaderIntegration
import com.bothbubbles.ui.chat.integration.IntegrationIcons
import com.bothbubbles.ui.chat.integration.TapActionData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat header integration for Life360 location display.
 *
 * Shows the current location of a contact if:
 * - The chat is a 1:1 (not a group)
 * - The contact is linked to a Life360 member
 * - The location data is not stale (< 30 minutes old)
 *
 * Priority: 100 (shown first, as location is most dynamic)
 *
 * Tap action: Navigate to Conversation Details screen, scroll to Life360 section
 */
@Singleton
class Life360HeaderIntegration @Inject constructor(
    private val life360Repository: Life360Repository,
    private val life360Service: Life360Service,
    private val featurePreferences: FeaturePreferences
) : ChatHeaderIntegration {

    companion object {
        private const val TAG = "Life360HeaderIntegration"

        /** Threshold for considering location data stale */
        private const val LOCATION_STALE_THRESHOLD_MS = 30 * 60 * 1000L // 30 minutes
    }

    override val id: String = "life360"
    override val priority: Int = 100

    // Scope for one-off sync operations
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Track which members we've synced to avoid repeated API calls
    private val syncedMembers = mutableSetOf<String>()

    override fun observeContent(
        participantAddresses: Set<String>,
        isGroup: Boolean
    ): Flow<ChatHeaderContent?> {
        // Only for 1:1 chats
        if (isGroup) {
            return flowOf(null)
        }

        if (participantAddresses.isEmpty()) {
            return flowOf(null)
        }

        // Observe Life360 members matching participant phone numbers
        return life360Repository.observeMembersByPhoneNumbers(participantAddresses)
            .map { members ->
                val member = members.firstOrNull() ?: return@map null

                // Trigger sync on first detection (if Life360 is enabled)
                if (member.memberId !in syncedMembers) {
                    syncedMembers.add(member.memberId)
                    syncScope.launch {
                        try {
                            if (featurePreferences.life360Enabled.first()) {
                                life360Service.syncMember(member.circleId, member.memberId)
                                Timber.tag(TAG).d("Synced Life360 member ${member.memberId}")
                            }
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Failed to sync Life360 member")
                        }
                    }
                }

                val location = member.location ?: return@map null

                // Check if location is stale
                val isStale = (System.currentTimeMillis() - location.timestamp) > LOCATION_STALE_THRESHOLD_MS
                if (isStale) {
                    return@map null
                }

                // Determine display text (prefer place name, then short address)
                val text = location.placeName ?: location.shortAddress ?: return@map null

                ChatHeaderContent(
                    text = text,
                    sourceId = id,
                    priority = priority,
                    icon = IntegrationIcons.Location,
                    triggerCycleOnChange = true, // Location changes are interesting
                    tapActionData = TapActionData.Life360Location(member.memberId)
                )
            }
    }
}
