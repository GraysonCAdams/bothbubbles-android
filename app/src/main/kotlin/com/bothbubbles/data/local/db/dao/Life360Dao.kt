package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bothbubbles.core.model.entity.Life360MemberEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Life360 member data.
 */
@Dao
interface Life360Dao {

    // ===== Queries =====

    /**
     * Get all members, optionally filtered by circle.
     */
    @Query("SELECT * FROM life360_members ORDER BY first_name ASC")
    fun getAllMembers(): Flow<List<Life360MemberEntity>>

    @Query("SELECT * FROM life360_members WHERE circle_id = :circleId ORDER BY first_name ASC")
    fun getMembersByCircle(circleId: String): Flow<List<Life360MemberEntity>>

    @Query("SELECT * FROM life360_members WHERE circle_id = :circleId ORDER BY first_name ASC")
    suspend fun getMembersByCircleOnce(circleId: String): List<Life360MemberEntity>

    @Query("SELECT * FROM life360_members WHERE member_id = :memberId")
    suspend fun getMemberById(memberId: String): Life360MemberEntity?

    @Query("SELECT * FROM life360_members WHERE member_id = :memberId")
    fun getMemberByIdFlow(memberId: String): Flow<Life360MemberEntity?>

    /**
     * Get member mapped to a specific handle (for showing location in conversation).
     * DEPRECATED: Use getMemberByLinkedAddressFlow instead.
     */
    @Query("SELECT * FROM life360_members WHERE mapped_handle_id = :handleId LIMIT 1")
    suspend fun getMemberByHandleId(handleId: Long): Life360MemberEntity?

    @Query("SELECT * FROM life360_members WHERE mapped_handle_id = :handleId LIMIT 1")
    fun getMemberByHandleIdFlow(handleId: Long): Flow<Life360MemberEntity?>

    /**
     * Get member linked to a specific address (phone number or email).
     * This is the primary lookup method for showing location in conversations.
     * Uses address-based matching which works across multiple handle IDs for the same contact.
     */
    @Query("SELECT * FROM life360_members WHERE linked_address = :address LIMIT 1")
    fun getMemberByLinkedAddressFlow(address: String): Flow<Life360MemberEntity?>

    /**
     * Get member by phone number (for matching by address when handle IDs differ).
     * Matches both exact phone number and normalized versions.
     */
    @Query("""
        SELECT * FROM life360_members
        WHERE phone_number = :phoneNumber
           OR phone_number = :normalizedPhone
           OR REPLACE(REPLACE(REPLACE(phone_number, '-', ''), ' ', ''), '(', '') LIKE '%' || :digitsOnly || '%'
        LIMIT 1
    """)
    fun getMemberByPhoneNumberFlow(
        phoneNumber: String,
        normalizedPhone: String,
        digitsOnly: String
    ): Flow<Life360MemberEntity?>

    /**
     * Get all members with location data (latitude/longitude not null).
     */
    @Query("SELECT * FROM life360_members WHERE latitude IS NOT NULL AND longitude IS NOT NULL")
    fun getMembersWithLocation(): Flow<List<Life360MemberEntity>>

    /**
     * Find potential contact matches by phone number.
     * Used for auto-mapping contacts.
     */
    @Query("SELECT * FROM life360_members WHERE phone_number IS NOT NULL")
    suspend fun getMembersWithPhoneNumbers(): List<Life360MemberEntity>

    /**
     * Get count of members in a circle.
     */
    @Query("SELECT COUNT(*) FROM life360_members WHERE circle_id = :circleId")
    suspend fun getMemberCountByCircle(circleId: String): Int

    /**
     * Get distinct circle IDs.
     */
    @Query("SELECT DISTINCT circle_id FROM life360_members")
    suspend fun getDistinctCircleIds(): List<String>

    // ===== Inserts/Updates =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: Life360MemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<Life360MemberEntity>)

    @Update
    suspend fun updateMember(member: Life360MemberEntity)

    /**
     * Update member location data only.
     */
    @Query("""
        UPDATE life360_members SET
            latitude = :latitude,
            longitude = :longitude,
            accuracy_meters = :accuracy,
            address = :address,
            short_address = :shortAddress,
            place_name = :placeName,
            battery = :battery,
            is_driving = :isDriving,
            is_charging = :isCharging,
            location_timestamp = :locationTimestamp,
            last_updated = :lastUpdated,
            no_location_reason = :noLocationReason
        WHERE member_id = :memberId
    """)
    suspend fun updateMemberLocation(
        memberId: String,
        latitude: Double?,
        longitude: Double?,
        accuracy: Int?,
        address: String?,
        shortAddress: String?,
        placeName: String?,
        battery: Int?,
        isDriving: Boolean?,
        isCharging: Boolean?,
        locationTimestamp: Long?,
        lastUpdated: Long,
        noLocationReason: String?
    )

    /**
     * Map a Life360 member to a BothBubbles contact by address.
     */
    @Query("UPDATE life360_members SET linked_address = :address WHERE member_id = :memberId")
    suspend fun mapMemberToAddress(memberId: String, address: String?)

    /**
     * Clear contact mapping for a member.
     */
    @Query("UPDATE life360_members SET linked_address = NULL WHERE member_id = :memberId")
    suspend fun unmapMember(memberId: String)

    /**
     * Set auto_link_disabled flag for a member.
     * When true, prevents autoMapContacts from re-linking this member.
     */
    @Query("UPDATE life360_members SET auto_link_disabled = :disabled WHERE member_id = :memberId")
    suspend fun setAutoLinkDisabled(memberId: String, disabled: Boolean)

    /**
     * Clear all contact mappings (e.g., when rebuilding mappings).
     */
    @Query("UPDATE life360_members SET linked_address = NULL")
    suspend fun clearAllMappings()

    // ===== Deletes =====

    @Query("DELETE FROM life360_members WHERE member_id = :memberId")
    suspend fun deleteMember(memberId: String)

    @Query("DELETE FROM life360_members WHERE circle_id = :circleId")
    suspend fun deleteMembersByCircle(circleId: String)

    @Query("DELETE FROM life360_members")
    suspend fun deleteAllMembers()

    /**
     * Delete members not in the provided list of IDs (for sync cleanup).
     */
    @Query("DELETE FROM life360_members WHERE circle_id = :circleId AND member_id NOT IN (:keepMemberIds)")
    suspend fun deleteMembersNotIn(circleId: String, keepMemberIds: List<String>)

    // ===== Upsert =====

    /**
     * Upsert members from API response, preserving contact mappings and auto_link_disabled flags.
     */
    @Transaction
    suspend fun upsertMembers(circleId: String, members: List<Life360MemberEntity>) {
        // Get existing members to preserve mappings and auto_link_disabled flags
        val existingMembers = getMembersByCircleOnce(circleId).associateBy { it.memberId }

        // Apply preserved values to new members
        val membersWithPreservedState = members.map { member ->
            val existing = existingMembers[member.memberId]
            member.copy(
                linkedAddress = existing?.linkedAddress,
                autoLinkDisabled = existing?.autoLinkDisabled ?: false
            )
        }

        // Insert/replace all members
        insertMembers(membersWithPreservedState)

        // Clean up members no longer in circle
        val currentMemberIds = members.map { it.memberId }
        deleteMembersNotIn(circleId, currentMemberIds)
    }
}
