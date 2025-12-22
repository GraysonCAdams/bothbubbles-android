package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bothbubbles.core.model.entity.ContactCalendarAssociationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for contact calendar associations.
 *
 * Provides CRUD operations for managing which device calendars
 * are associated with which contacts.
 */
@Dao
interface ContactCalendarDao {

    // ===== Queries =====

    /**
     * Observe the calendar association for a specific contact address.
     * Primary lookup method for showing calendar info in chat headers.
     */
    @Query("SELECT * FROM contact_calendar_associations WHERE linked_address = :address")
    fun observeByAddress(address: String): Flow<ContactCalendarAssociationEntity?>

    /**
     * Get association by address (one-shot).
     */
    @Query("SELECT * FROM contact_calendar_associations WHERE linked_address = :address")
    suspend fun getByAddress(address: String): ContactCalendarAssociationEntity?

    /**
     * Observe all calendar associations.
     */
    @Query("SELECT * FROM contact_calendar_associations ORDER BY calendar_display_name ASC")
    fun observeAll(): Flow<List<ContactCalendarAssociationEntity>>

    /**
     * Get all associations (one-shot).
     */
    @Query("SELECT * FROM contact_calendar_associations ORDER BY calendar_display_name ASC")
    suspend fun getAll(): List<ContactCalendarAssociationEntity>

    /**
     * Get all contacts associated with a specific calendar.
     * Useful for bulk cleanup if a calendar is deleted.
     */
    @Query("SELECT * FROM contact_calendar_associations WHERE calendar_id = :calendarId")
    suspend fun getByCalendarId(calendarId: Long): List<ContactCalendarAssociationEntity>

    /**
     * Check if a contact has a calendar association.
     */
    @Query("SELECT COUNT(*) > 0 FROM contact_calendar_associations WHERE linked_address = :address")
    suspend fun hasAssociation(address: String): Boolean

    // ===== Mutations =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(association: ContactCalendarAssociationEntity)

    @Update
    suspend fun update(association: ContactCalendarAssociationEntity)

    @Query("DELETE FROM contact_calendar_associations WHERE linked_address = :address")
    suspend fun deleteByAddress(address: String)

    @Query("DELETE FROM contact_calendar_associations WHERE calendar_id = :calendarId")
    suspend fun deleteByCalendarId(calendarId: Long)

    @Query("DELETE FROM contact_calendar_associations")
    suspend fun deleteAll()

    /**
     * Update cached calendar info (called when calendar is renamed/recolored).
     */
    @Query(
        """
        UPDATE contact_calendar_associations
        SET calendar_display_name = :displayName,
            calendar_color = :color,
            updated_at = :updatedAt
        WHERE calendar_id = :calendarId
    """
    )
    suspend fun updateCalendarInfo(
        calendarId: Long,
        displayName: String,
        color: Int?,
        updatedAt: Long = System.currentTimeMillis()
    )
}
