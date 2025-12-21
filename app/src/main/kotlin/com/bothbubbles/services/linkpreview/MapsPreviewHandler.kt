package com.bothbubbles.services.linkpreview

import android.content.Context
import android.location.Geocoder
import timber.log.Timber
import java.util.Locale

/**
 * Handles detection and preview generation for Google Maps and Apple Maps URLs.
 *
 * Extracts coordinates from map URLs, generates static map images, and performs
 * reverse geocoding to get human-readable addresses.
 */
internal class MapsPreviewHandler(
    private val context: Context
) {
    // Geocoder for reverse geocoding map coordinates to addresses
    private val geocoder by lazy { Geocoder(context, Locale.getDefault()) }

    companion object {
        // Maps URL patterns for synthetic preview generation
        // Each pattern captures (latitude, longitude) as groups 1 and 2
        private val MAPS_PATTERNS = listOf(
            // Google Maps ?q= format (our sending format): maps.google.com/?q=37.7749,-122.4194
            MapsPattern(
                """maps\.google\.com/?\?q=(-?\d+\.?\d*),(-?\d+\.?\d*)""".toRegex(RegexOption.IGNORE_CASE),
                "Google Maps"
            ),
            // Google Maps @ format: google.com/maps/@37.7749,-122.4194,15z
            MapsPattern(
                """google\.com/maps.*@(-?\d+\.?\d*),(-?\d+\.?\d*)""".toRegex(RegexOption.IGNORE_CASE),
                "Google Maps"
            ),
            // Apple Maps ?ll= format: maps.apple.com/?ll=37.7749,-122.4194
            MapsPattern(
                """maps\.apple\.com.*[?&]ll=(-?\d+\.?\d*),(-?\d+\.?\d*)""".toRegex(RegexOption.IGNORE_CASE),
                "Apple Maps"
            ),
            // Apple Maps ?q= format: maps.apple.com/?q=37.7749,-122.4194
            MapsPattern(
                """maps\.apple\.com.*[?&]q=(-?\d+\.?\d*),(-?\d+\.?\d*)""".toRegex(RegexOption.IGNORE_CASE),
                "Apple Maps"
            )
        )
    }

    /**
     * Tries to generate a synthetic preview for Google Maps / Apple Maps URLs.
     * Extracts coordinates from the URL, generates a static map image, and reverse geocodes the address.
     */
    suspend fun tryMapsPreview(url: String): LinkMetadataResult? {
        // Find matching maps pattern
        for (pattern in MAPS_PATTERNS) {
            val match = pattern.regex.find(url) ?: continue

            // Extract coordinates from regex groups
            val lat = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: continue
            val lng = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: continue

            // Validate coordinate ranges
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                Timber.w("Invalid coordinates in maps URL: lat=$lat, lng=$lng")
                continue
            }

            Timber.d("Detected ${pattern.siteName} URL with coordinates: $lat, $lng")

            // Generate static map image URL
            val staticMapUrl = buildStaticMapUrl(lat, lng)

            // Try to reverse geocode the address
            val address = reverseGeocode(lat, lng)

            // Build the preview metadata
            val title = address ?: "Shared Location"
            val description = "%.6f, %.6f".format(lat, lng)

            return LinkMetadataResult.Success(
                LinkMetadata(
                    title = title,
                    description = description,
                    imageUrl = staticMapUrl,
                    faviconUrl = null,
                    siteName = pattern.siteName,
                    contentType = "location"
                )
            )
        }

        return null
    }

    /**
     * Builds a static map image URL using OpenStreetMap (free, no API key required)
     */
    private fun buildStaticMapUrl(lat: Double, lng: Double): String {
        // Offset center north so pin appears vertically centered in the preview
        // At zoom 15, offset is ~0.0008 (double zoom 16's 0.0004)
        val centeredLat = lat + 0.0008
        return "https://staticmap.openstreetmap.de/staticmap.php?" +
            "center=$centeredLat,$lng&zoom=15&size=400x200&markers=$lat,$lng,red-pushpin"
    }

    /**
     * Reverse geocodes coordinates to a human-readable address using Android Geocoder
     */
    @Suppress("DEPRECATION")
    private fun reverseGeocode(lat: Double, lng: Double): String? {
        return try {
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            addresses?.firstOrNull()?.getAddressLine(0)
        } catch (e: Exception) {
            Timber.w("Reverse geocoding failed: ${e.message}")
            null
        }
    }
}
