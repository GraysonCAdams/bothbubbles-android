package com.bothbubbles.data.repository

import com.bothbubbles.core.model.entity.ContactCalendarAssociationEntity
import com.bothbubbles.data.local.db.dao.ContactCalendarDao
import com.bothbubbles.services.calendar.CalendarContentProvider
import com.bothbubbles.services.calendar.CurrentEventState
import com.bothbubbles.services.calendar.DeviceCalendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing contact-calendar associations and event queries.
 *
 * This repository provides:
 * - CRUD operations for calendar associations
 * - Reactive observation of associated calendars
 * - Event queries for associated contacts
 */
@Singleton
class ContactCalendarRepository @Inject constructor(
    private val dao: ContactCalendarDao,
    private val calendarProvider: CalendarContentProvider
) {
    companion object {
        private const val TAG = "ContactCalendarRepository"
    }

    // ===== Association Management =====

    /**
     * Observe the calendar association for a contact.
     * Returns null flow value if no association exists.
     */
    fun observeAssociation(address: String): Flow<ContactCalendarAssociation?> =
        dao.observeByAddress(address).map { entity ->
            entity?.toDomainModel()
        }

    /**
     * Observe all calendar associations.
     */
    fun observeAllAssociations(): Flow<List<ContactCalendarAssociation>> =
        dao.observeAll().map { entities ->
            entities.map { it.toDomainModel() }
        }

    /**
     * Get association for a contact (one-shot).
     */
    suspend fun getAssociation(address: String): ContactCalendarAssociation? =
        dao.getByAddress(address)?.toDomainModel()

    /**
     * Check if a contact has a calendar association.
     */
    suspend fun hasAssociation(address: String): Boolean =
        dao.hasAssociation(address)

    /**
     * Associate a calendar with a contact.
     * Overwrites any existing association for this contact.
     */
    suspend fun setAssociation(
        address: String,
        calendar: DeviceCalendar
    ): Result<Unit> = runCatching {
        val entity = ContactCalendarAssociationEntity(
            linkedAddress = address,
            calendarId = calendar.id,
            calendarDisplayName = calendar.displayName,
            calendarColor = calendar.color,
            accountName = calendar.accountName
        )
        dao.insert(entity)
        Timber.tag(TAG).d("Associated calendar '${calendar.displayName}' with contact $address")
    }

    /**
     * Remove calendar association for a contact.
     */
    suspend fun removeAssociation(address: String): Result<Unit> = runCatching {
        dao.deleteByAddress(address)
        Timber.tag(TAG).d("Removed calendar association for contact $address")
    }

    /**
     * Remove all associations for a calendar (e.g., if calendar was deleted).
     */
    suspend fun removeAssociationsForCalendar(calendarId: Long): Result<Unit> = runCatching {
        dao.deleteByCalendarId(calendarId)
        Timber.tag(TAG).d("Removed all associations for calendar $calendarId")
    }

    // ===== Event Queries =====

    /**
     * Get available calendars on the device.
     */
    suspend fun getAvailableCalendars(): List<DeviceCalendar> =
        calendarProvider.getCalendars()

    /**
     * Get current or next event for a contact's associated calendar.
     * Returns NoUpcomingEvents if no calendar is associated.
     */
    suspend fun getCurrentEventState(address: String): CurrentEventState {
        val association = dao.getByAddress(address)
            ?: return CurrentEventState.NoUpcomingEvents

        return calendarProvider.getCurrentOrNextEvent(
            calendarId = association.calendarId
        )
    }

    /**
     * Observe current event state for a contact with polling.
     * Emits updates every [pollIntervalMs] milliseconds.
     *
     * @param address Contact address
     * @param pollIntervalMs How often to re-query (default 30 seconds)
     */
    fun observeCurrentEventState(
        address: String,
        pollIntervalMs: Long = 30_000L
    ): Flow<CurrentEventState> = flow {
        while (true) {
            emit(getCurrentEventState(address))
            delay(pollIntervalMs)
        }
    }.distinctUntilChanged()

    // ===== Calendar Sync =====

    /**
     * Refresh cached calendar info for all associations.
     * Call this periodically or when user opens settings to keep names/colors current.
     */
    suspend fun refreshCachedCalendarInfo(): Result<Int> = runCatching {
        val associations = dao.getAll()
        val calendars = calendarProvider.getCalendars().associateBy { it.id }
        var updatedCount = 0

        for (association in associations) {
            val calendar = calendars[association.calendarId]
            if (calendar != null) {
                // Only update if something changed
                if (calendar.displayName != association.calendarDisplayName ||
                    calendar.color != association.calendarColor
                ) {
                    dao.updateCalendarInfo(
                        calendarId = association.calendarId,
                        displayName = calendar.displayName,
                        color = calendar.color
                    )
                    updatedCount++
                }
            } else {
                // Calendar no longer exists - remove association
                Timber.tag(TAG).w("Calendar ${association.calendarId} no longer exists, removing association")
                dao.deleteByAddress(association.linkedAddress)
            }
        }

        Timber.tag(TAG).d("Refreshed calendar info: $updatedCount updated")
        updatedCount
    }

    // ===== Mapping =====

    private fun ContactCalendarAssociationEntity.toDomainModel(): ContactCalendarAssociation {
        return ContactCalendarAssociation(
            linkedAddress = linkedAddress,
            calendarId = calendarId,
            calendarDisplayName = calendarDisplayName,
            calendarColor = calendarColor,
            accountName = accountName
        )
    }
}

/**
 * Domain model for a contact's calendar association.
 */
data class ContactCalendarAssociation(
    val linkedAddress: String,
    val calendarId: Long,
    val calendarDisplayName: String,
    val calendarColor: Int?,
    val accountName: String?
)
