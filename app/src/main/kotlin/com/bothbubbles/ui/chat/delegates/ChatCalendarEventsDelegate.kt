package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.data.repository.CalendarEventOccurrenceRepository
import com.bothbubbles.data.repository.ContactCalendarRepository
import com.bothbubbles.services.calendar.CalendarEventSyncPreferences
import com.bothbubbles.ui.components.message.CalendarEventItem
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Delegate for observing and providing calendar events for a chat.
 *
 * Calendar events are displayed as system-style indicators in the chat timeline,
 * similar to group member changes. They show what contacts with synced calendars
 * are doing.
 *
 * Features:
 * - Observes calendar event occurrences for chat participants
 * - Performs retroactive sync if data is stale (>48 hours old)
 * - Does NOT bump conversations or trigger notifications
 *
 * @see CalendarEventOccurrenceRepository for sync logic
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatCalendarEventsDelegate @AssistedInject constructor(
    private val calendarEventOccurrenceRepository: CalendarEventOccurrenceRepository,
    private val contactCalendarRepository: ContactCalendarRepository,
    private val syncPreferences: CalendarEventSyncPreferences,
    @Assisted private val scope: CoroutineScope
) {

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): ChatCalendarEventsDelegate
    }

    companion object {
        private const val TAG = "ChatCalendarEvents"
    }

    // Current participant addresses for the chat
    private val _participantAddresses = MutableStateFlow<Set<String>>(emptySet())

    // Calendar events for display in the chat timeline
    private val _calendarEvents = MutableStateFlow<List<CalendarEventItem>>(emptyList())
    val calendarEvents: StateFlow<List<CalendarEventItem>> = _calendarEvents.asStateFlow()

    init {
        // Observe calendar events for participant addresses
        scope.launch {
            _participantAddresses
                .flatMapLatest { addresses ->
                    if (addresses.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        // For 1:1 chats, observe events for the single participant
                        // For group chats, we could observe all participants but that might be noisy
                        // For now, only observe for single participants (1:1 chats)
                        val address = addresses.singleOrNull()
                        if (address != null) {
                            calendarEventOccurrenceRepository.observeForAddress(address)
                                .map { entities ->
                                    val now = System.currentTimeMillis()
                                    entities
                                        .filter { it.eventStartTime <= now } // Only show events that have started
                                        .map { CalendarEventItem.fromEntity(it) }
                                }
                        } else {
                            // Group chats - don't show calendar events (too noisy)
                            flowOf(emptyList())
                        }
                    }
                }
                .collect { events ->
                    _calendarEvents.value = events
                }
        }

        // Observe calendar association changes to trigger sync when user adds association
        // from Chat Details screen. Without this, the calendar events wouldn't appear
        // until the user leaves and re-enters the chat.
        scope.launch {
            _participantAddresses
                .flatMapLatest { addresses ->
                    val address = addresses.singleOrNull()
                    if (address != null) {
                        contactCalendarRepository.observeAssociation(address)
                            .distinctUntilChangedBy { it?.calendarId }
                            .map { association -> address to association }
                    } else {
                        flowOf(null)
                    }
                }
                .collect { result ->
                    if (result != null) {
                        val (address, association) = result
                        if (association != null) {
                            // Association exists - perform sync to load events
                            Timber.tag(TAG).d("Calendar association changed for $address, syncing events")
                            performRetroactiveSyncIfNeeded(address, forceSync = true)
                        }
                    }
                }
        }
    }

    /**
     * Update participant addresses for the chat.
     * Called when chat info loads with participant data.
     *
     * For 1:1 chats, performs retroactive sync if data is stale.
     */
    fun updateParticipants(addresses: Set<String>, isGroup: Boolean) {
        _participantAddresses.value = addresses

        // Only perform retroactive sync for 1:1 chats
        if (!isGroup) {
            addresses.singleOrNull()?.let { address ->
                scope.launch {
                    performRetroactiveSyncIfNeeded(address)
                }
            }
        }
    }

    /**
     * Perform retroactive sync if the contact hasn't been synced in 48+ hours.
     * This fetches past calendar events for display in the timeline.
     *
     * @param address Contact address to sync
     * @param forceSync If true, skip the staleness check and always sync.
     *                  Used when calendar association changes while in chat.
     */
    private suspend fun performRetroactiveSyncIfNeeded(address: String, forceSync: Boolean = false) {
        // Check if contact has a calendar association
        val hasAssociation = contactCalendarRepository.hasAssociation(address)
        if (!hasAssociation) {
            Timber.tag(TAG).d("No calendar association for $address, skipping retroactive sync")
            return
        }

        // Check if sync is stale (skip if forceSync is true)
        if (!forceSync && !syncPreferences.needsRetroactiveSync(address)) {
            Timber.tag(TAG).d("Calendar sync for $address is fresh, skipping retroactive sync")
            return
        }

        Timber.tag(TAG).d("Performing retroactive calendar sync for $address (forced=$forceSync)")
        val result = calendarEventOccurrenceRepository.retroactiveSyncForContact(address)
        result.fold(
            onSuccess = { count ->
                syncPreferences.setLastSyncTime(address)
                if (count > 0) {
                    Timber.tag(TAG).d("Retroactively synced $count calendar events for $address")
                }
            },
            onFailure = { error ->
                Timber.tag(TAG).w(error, "Failed to perform retroactive sync for $address")
            }
        )
    }
}
