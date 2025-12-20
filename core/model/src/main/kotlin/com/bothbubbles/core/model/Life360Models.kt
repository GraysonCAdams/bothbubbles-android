package com.bothbubbles.core.model

import com.bothbubbles.core.model.entity.Life360MemberEntity

/**
 * Domain model for Life360 circle members, consumed by UI layer.
 *
 * This is a simplified, UI-ready representation of [Life360MemberEntity].
 * Use [Life360MemberEntity.toDomain] to convert from entity.
 */
data class Life360Member(
    val memberId: String,
    val circleId: String,
    val displayName: String,
    val avatarUrl: String?,
    val phoneNumber: String?,
    val location: Life360Location?,     // Null if unavailable
    val battery: Int?,
    val isCharging: Boolean?,
    val lastUpdated: Long,
    val noLocationReason: NoLocationReason?,
    val mappedHandleId: Long?
)

/**
 * Location data for a Life360 member.
 */
data class Life360Location(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Int?,
    val address: String?,
    val shortAddress: String?,          // City, State (for subtitle display)
    val placeName: String?,             // e.g., "Home", "Work"
    val isDriving: Boolean?,
    val timestamp: Long                 // When Life360 got the location
)

/**
 * Life360 circle (family/group).
 */
data class Life360Circle(
    val id: String,
    val name: String,
    val memberCount: Int
)

/**
 * Reasons why location may be unavailable for a Life360 member.
 * Priority indicates which reason takes precedence when multiple apply.
 */
enum class NoLocationReason(val priority: Int) {
    /** User explicitly disabled location sharing */
    EXPLICIT(3),
    /** Unknown reason - "may have lost connection to Life360" */
    NO_REASON(2),
    /** Member not sharing location in this circle */
    NOT_SHARING(1),
    /** Member not found (404 response) */
    NOT_FOUND(0),
    /** No location check attempted yet */
    NOT_SET(-1)
}

/**
 * Extension function to convert [Life360MemberEntity] to domain model [Life360Member].
 */
fun Life360MemberEntity.toDomain(): Life360Member = Life360Member(
    memberId = memberId,
    circleId = circleId,
    displayName = listOfNotNull(firstName, lastName).joinToString(" ").ifEmpty { "Unknown" },
    avatarUrl = avatarUrl,
    phoneNumber = phoneNumber,
    location = if (latitude != null && longitude != null) {
        Life360Location(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            address = address,
            shortAddress = shortAddress,
            placeName = placeName,
            isDriving = isDriving,
            timestamp = locationTimestamp ?: 0L
        )
    } else null,
    battery = battery,
    isCharging = isCharging,
    lastUpdated = lastUpdated,
    noLocationReason = noLocationReason?.let { reason ->
        try {
            NoLocationReason.valueOf(reason)
        } catch (_: IllegalArgumentException) {
            null
        }
    },
    mappedHandleId = mappedHandleId
)
