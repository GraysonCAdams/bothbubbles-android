package com.bothbubbles.data.repository

import com.bothbubbles.core.model.Life360Circle
import com.bothbubbles.core.model.Life360Member
import com.bothbubbles.core.model.toDomain
import com.bothbubbles.core.model.entity.Life360MemberEntity
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.Life360Dao
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for Life360 member data.
 *
 * Provides access to locally cached Life360 member data and handles
 * contact auto-mapping. Network operations are in [Life360Service].
 */
@Singleton
class Life360Repository @Inject constructor(
    private val life360Dao: Life360Dao,
    private val handleDao: HandleDao
) {

    // ===== Observation =====

    /**
     * Observe all members across all circles.
     */
    fun observeAllMembers(): Flow<List<Life360Member>> =
        life360Dao.getAllMembers().map { entities ->
            entities.map { it.toDomain() }
        }

    /**
     * Observe members in a specific circle.
     */
    fun observeMembersByCircle(circleId: String): Flow<List<Life360Member>> =
        life360Dao.getMembersByCircle(circleId).map { entities ->
            entities.map { it.toDomain() }
        }

    /**
     * Observe a specific member.
     */
    fun observeMember(memberId: String): Flow<Life360Member?> =
        life360Dao.getMemberByIdFlow(memberId).map { entity ->
            entity?.toDomain()
        }

    /**
     * Observe member mapped to a specific handle (for conversation view).
     */
    fun observeMemberByHandle(handleId: Long): Flow<Life360Member?> =
        life360Dao.getMemberByHandleIdFlow(handleId).map { entity ->
            Timber.d("Life360 observeMemberByHandle: handleId=$handleId, found=${entity != null}, member=${entity?.firstName} ${entity?.lastName}")
            entity?.toDomain()
        }

    /**
     * Observe member by phone number/address.
     * More reliable than handle ID matching since handles can differ across services.
     */
    fun observeMemberByAddress(address: String): Flow<Life360Member?> {
        // Normalize the phone number for matching
        val normalized = PhoneNumberFormatter.normalize(address) ?: address
        val digitsOnly = address.filter { it.isDigit() }.takeLast(10)  // Last 10 digits

        Timber.d("Life360 observeMemberByAddress: address=$address, normalized=$normalized, digits=$digitsOnly")

        return life360Dao.getMemberByPhoneNumberFlow(address, normalized, digitsOnly).map { entity ->
            Timber.d("Life360 observeMemberByAddress result: found=${entity != null}, member=${entity?.firstName} ${entity?.lastName}")
            entity?.toDomain()
        }
    }

    /**
     * Observe all members with valid location data.
     */
    fun observeMembersWithLocation(): Flow<List<Life360Member>> =
        life360Dao.getMembersWithLocation().map { entities ->
            entities.map { it.toDomain() }
        }

    // ===== Getters =====

    /**
     * Get member by ID.
     */
    suspend fun getMember(memberId: String): Life360Member? =
        life360Dao.getMemberById(memberId)?.toDomain()

    /**
     * Get member mapped to a handle.
     */
    suspend fun getMemberByHandle(handleId: Long): Life360Member? =
        life360Dao.getMemberByHandleId(handleId)?.toDomain()

    /**
     * Get members by circle.
     */
    suspend fun getMembersByCircle(circleId: String): List<Life360Member> =
        life360Dao.getMembersByCircleOnce(circleId).map { it.toDomain() }

    /**
     * Get all distinct circle IDs.
     */
    suspend fun getCircleIds(): List<String> =
        life360Dao.getDistinctCircleIds()

    // ===== Mutations =====

    /**
     * Save members from API response.
     * Preserves existing contact mappings.
     */
    suspend fun saveMembers(circleId: String, members: List<Life360MemberEntity>) {
        life360Dao.upsertMembers(circleId, members)
        Timber.d("Saved ${members.size} Life360 members for circle $circleId")
    }

    /**
     * Update location for a single member.
     */
    suspend fun updateMemberLocation(
        memberId: String,
        latitude: Double?,
        longitude: Double?,
        accuracyMeters: Int?,
        address: String?,
        shortAddress: String?,
        placeName: String?,
        battery: Int?,
        isDriving: Boolean?,
        isCharging: Boolean?,
        locationTimestamp: Long?,
        noLocationReason: String?
    ) {
        life360Dao.updateMemberLocation(
            memberId = memberId,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracyMeters,
            address = address,
            shortAddress = shortAddress,
            placeName = placeName,
            battery = battery,
            isDriving = isDriving,
            isCharging = isCharging,
            locationTimestamp = locationTimestamp,
            lastUpdated = System.currentTimeMillis(),
            noLocationReason = noLocationReason
        )
    }

    // ===== Contact Mapping =====

    /**
     * Map a Life360 member to a BothBubbles contact (handle).
     */
    suspend fun mapMemberToContact(memberId: String, handleId: Long) {
        life360Dao.mapMemberToHandle(memberId, handleId)
        Timber.d("Mapped Life360 member $memberId to handle $handleId")
    }

    /**
     * Clear contact mapping for a member.
     */
    suspend fun unmapMember(memberId: String) {
        life360Dao.unmapMember(memberId)
        Timber.d("Unmapped Life360 member $memberId")
    }

    /**
     * Auto-map Life360 members to contacts by matching phone numbers.
     *
     * @return Number of members successfully mapped
     */
    suspend fun autoMapContacts(): Int {
        val membersWithPhones = life360Dao.getMembersWithPhoneNumbers()
        var mappedCount = 0

        for (member in membersWithPhones) {
            if (member.mappedHandleId != null) continue  // Already mapped

            val phoneNumber = member.phoneNumber ?: continue
            val normalizedPhone = PhoneNumberFormatter.normalize(phoneNumber)

            // Try to find a handle with matching phone number
            val handle = normalizedPhone?.let { handleDao.getHandlesByAddress(it).firstOrNull() }
                ?: handleDao.getHandlesByAddress(phoneNumber).firstOrNull()

            if (handle != null) {
                life360Dao.mapMemberToHandle(member.memberId, handle.id)
                mappedCount++
                Timber.d("Auto-mapped Life360 member ${member.firstName} to handle ${handle.address}")
            }
        }

        Timber.d("Auto-mapped $mappedCount Life360 members to contacts")
        return mappedCount
    }

    // ===== Cleanup =====

    /**
     * Delete all members in a circle.
     */
    suspend fun deleteCircleMembers(circleId: String) {
        life360Dao.deleteMembersByCircle(circleId)
        Timber.d("Deleted all members for circle $circleId")
    }

    /**
     * Delete all Life360 data (logout).
     */
    suspend fun deleteAllMembers() {
        life360Dao.deleteAllMembers()
        Timber.d("Deleted all Life360 members")
    }
}
