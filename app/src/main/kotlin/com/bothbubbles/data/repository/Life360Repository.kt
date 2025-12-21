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
     * Observe member linked to a specific address (for conversation view).
     * This is the primary method for looking up Life360 members in chats.
     * Uses address-based linking which works across multiple handle IDs for the same contact.
     */
    fun observeMemberByLinkedAddress(address: String): Flow<Life360Member?> =
        life360Dao.getMemberByLinkedAddressFlow(address).map { entity ->
            Timber.d("Life360 observeMemberByLinkedAddress: address=$address, found=${entity != null}, member=${entity?.firstName} ${entity?.lastName}")
            entity?.toDomain()
        }

    /**
     * Observe all members with valid location data.
     */
    fun observeMembersWithLocation(): Flow<List<Life360Member>> =
        life360Dao.getMembersWithLocation().map { entities ->
            entities.map { it.toDomain() }
        }

    /**
     * Observe Life360 members matching any of the given addresses by phoneNumber.
     *
     * This is the primary method for finding Life360 members in conversations.
     * Matches by phoneNumber (case-insensitive) rather than linked_address,
     * so it works immediately without requiring autoMapContacts to run first.
     *
     * @param addresses Set of participant addresses (phone numbers/emails) to match
     * @return Flow of members whose phoneNumber matches any of the addresses
     */
    fun observeMembersByPhoneNumbers(addresses: Set<String>): Flow<List<Life360Member>> =
        observeAllMembers().map { allMembers ->
            allMembers.filter { member ->
                member.phoneNumber?.let { phone ->
                    addresses.any { addr -> phone.equals(addr, ignoreCase = true) }
                } == true
            }
        }

    // ===== Getters =====

    /**
     * Get member by ID.
     */
    suspend fun getMember(memberId: String): Life360Member? =
        life360Dao.getMemberById(memberId)?.toDomain()

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
     * Map a Life360 member to a BothBubbles contact by address.
     * Also clears the auto_link_disabled flag so future auto-linking
     * can work if the user unlinks again.
     *
     * @param memberId The Life360 member ID
     * @param address The contact's phone number or email address
     */
    suspend fun mapMemberToContact(memberId: String, address: String) {
        life360Dao.mapMemberToAddress(memberId, address)
        life360Dao.setAutoLinkDisabled(memberId, false)
        Timber.d("Mapped Life360 member $memberId to address $address")
    }

    /**
     * Clear contact mapping for a member and disable auto-linking.
     * The auto_link_disabled flag prevents autoMapContacts from re-linking
     * this member on subsequent syncs.
     */
    suspend fun unmapMember(memberId: String) {
        life360Dao.unmapMember(memberId)
        life360Dao.setAutoLinkDisabled(memberId, true)
        Timber.d("Unmapped Life360 member $memberId and disabled auto-linking")
    }

    /**
     * Auto-map Life360 members to contacts by matching phone numbers.
     * Skips members that are already mapped or have auto-linking disabled
     * (i.e., were manually unlinked by the user).
     *
     * @return Number of members successfully mapped
     */
    suspend fun autoMapContacts(): Int {
        val membersWithPhones = life360Dao.getMembersWithPhoneNumbers()
        var mappedCount = 0

        for (member in membersWithPhones) {
            if (member.linkedAddress != null) continue  // Already mapped
            if (member.autoLinkDisabled) continue  // User manually unlinked, don't re-link

            val phoneNumber = member.phoneNumber ?: continue
            val normalizedPhone = PhoneNumberFormatter.normalize(phoneNumber)

            // Try to find a handle with matching phone number
            val handle = normalizedPhone?.let { handleDao.getHandlesByAddress(it).firstOrNull() }
                ?: handleDao.getHandlesByAddress(phoneNumber).firstOrNull()

            if (handle != null) {
                // Store the handle's address, not the handle ID
                life360Dao.mapMemberToAddress(member.memberId, handle.address)
                mappedCount++
                Timber.d("Auto-mapped Life360 member ${member.firstName} to address ${handle.address}")
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
