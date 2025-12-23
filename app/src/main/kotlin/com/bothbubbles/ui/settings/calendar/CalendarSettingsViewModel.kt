package com.bothbubbles.ui.settings.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.repository.ContactCalendarAssociation
import com.bothbubbles.data.repository.ContactCalendarRepository
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.services.calendar.DeviceCalendar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for calendar settings screen.
 */
data class CalendarSettingsUiState(
    val associations: ImmutableList<CalendarAssociationWithContact> = emptyList<CalendarAssociationWithContact>().toImmutableList(),
    val isLoading: Boolean = true
)

/**
 * Calendar association with resolved contact display name.
 */
data class CalendarAssociationWithContact(
    val association: ContactCalendarAssociation,
    val contactDisplayName: String?,
    val avatarPath: String?
)

@HiltViewModel
class CalendarSettingsViewModel @Inject constructor(
    private val calendarRepository: ContactCalendarRepository,
    private val handleRepository: HandleRepository
) : ViewModel() {

    private val _isLoading = MutableStateFlow(true)

    val uiState: StateFlow<CalendarSettingsUiState> = combine(
        calendarRepository.observeAllAssociations(),
        _isLoading
    ) { associations, isLoading ->
        // Resolve contact names for each association
        val associationsWithContacts = associations.map { association ->
            val handle = handleRepository.getHandleByAddressAny(association.linkedAddress)
            CalendarAssociationWithContact(
                association = association,
                contactDisplayName = handle?.cachedDisplayName,
                avatarPath = handle?.cachedAvatarPath
            )
        }.sortedBy { it.contactDisplayName ?: it.association.linkedAddress }

        CalendarSettingsUiState(
            associations = associationsWithContacts.toImmutableList(),
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CalendarSettingsUiState()
    )

    init {
        // Refresh cached calendar info when screen opens
        viewModelScope.launch {
            calendarRepository.refreshCachedCalendarInfo()
            _isLoading.value = false
        }
    }

    /**
     * Get available device calendars.
     */
    suspend fun getAvailableCalendars(): List<DeviceCalendar> =
        calendarRepository.getAvailableCalendars()

    /**
     * Update calendar association for a contact.
     */
    fun updateAssociation(address: String, calendar: DeviceCalendar) {
        viewModelScope.launch {
            calendarRepository.setAssociation(address, calendar)
        }
    }

    /**
     * Remove calendar association for a contact.
     */
    fun removeAssociation(address: String) {
        viewModelScope.launch {
            calendarRepository.removeAssociation(address)
        }
    }
}
