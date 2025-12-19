package com.bothbubbles.core.network.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Life360 API DTOs.
 *
 * All numeric values come as strings from the Life360 API and need parsing.
 * Location accuracy is in FEET and must be converted to meters (* 0.3048).
 * Speed must be multiplied by 2.25 for MPH.
 *
 * @see com.bothbubbles.services.life360.Life360ServiceImpl for parsing/conversion
 */

// ===== Auth Response =====

@JsonClass(generateAdapter = true)
data class Life360AuthResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "token_type") val tokenType: String,   // Usually "Bearer"
    @Json(name = "expires_in") val expiresIn: Long?
)

// ===== Circles Response =====

@JsonClass(generateAdapter = true)
data class Life360CirclesResponse(
    @Json(name = "circles") val circles: List<Life360CircleDto>
)

@JsonClass(generateAdapter = true)
data class Life360CircleDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "memberCount") val memberCount: Int? = null
)

// ===== Members Response =====

@JsonClass(generateAdapter = true)
data class Life360MembersResponse(
    @Json(name = "members") val members: List<Life360MemberDto>
)

@JsonClass(generateAdapter = true)
data class Life360MemberDto(
    @Json(name = "id") val id: String,
    @Json(name = "firstName") val firstName: String,
    @Json(name = "lastName") val lastName: String? = null,
    @Json(name = "avatar") val avatar: String? = null,          // URL to profile picture
    @Json(name = "loginPhone") val loginPhone: String? = null,  // Phone number for auto-matching
    @Json(name = "location") val location: Life360LocationDto? = null,
    @Json(name = "features") val features: Life360FeaturesDto? = null,
    @Json(name = "issues") val issues: Life360IssuesDto? = null
)

@JsonClass(generateAdapter = true)
data class Life360LocationDto(
    @Json(name = "latitude") val latitude: String,        // String, convert to Double
    @Json(name = "longitude") val longitude: String,      // String, convert to Double
    @Json(name = "accuracy") val accuracy: String,        // In FEET, convert to meters (* 0.3048)
    @Json(name = "address1") val address1: String? = null,    // Street address
    @Json(name = "address2") val address2: String? = null,    // City, State
    @Json(name = "name") val name: String? = null,            // Place name (e.g., "Home", "Work")
    @Json(name = "since") val since: String,              // Unix timestamp - when arrived at location
    @Json(name = "timestamp") val timestamp: String,      // Unix timestamp - when location was updated
    @Json(name = "isDriving") val isDriving: String,      // "0" or "1"
    @Json(name = "speed") val speed: String,              // Multiply by 2.25 for MPH
    @Json(name = "battery") val battery: String,          // Battery percentage as string (e.g., "87.5")
    @Json(name = "charge") val charge: String,            // "0" or "1" - is charging
    @Json(name = "wifiState") val wifiState: String       // "0" or "1" - WiFi enabled
)

@JsonClass(generateAdapter = true)
data class Life360FeaturesDto(
    @Json(name = "shareLocation") val shareLocation: String? = null    // "0" or "1" - sharing with this circle
)

@JsonClass(generateAdapter = true)
data class Life360IssuesDto(
    @Json(name = "title") val title: String? = null,       // Error title if location unavailable
    @Json(name = "dialog") val dialog: String? = null      // Extended error message
)
