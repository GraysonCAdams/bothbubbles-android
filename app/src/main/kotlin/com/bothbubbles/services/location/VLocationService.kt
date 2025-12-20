package com.bothbubbles.services.location

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for creating and parsing Apple vLocation files.
 *
 * Apple's native iMessage location format uses a VCF (vCard-like) file with:
 * - MIME type: text/x-vlocation
 * - UTI: public.vlocation
 * - Extension: .loc.vcf
 *
 * This allows location pins sent from Android to appear as native
 * "Shared Location" messages on iPhones.
 */
@Singleton
class VLocationService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MIME_TYPE = "text/x-vlocation"
        const val UTI = "public.vlocation"
        const val FILE_EXTENSION = ".loc.vcf"

        // VCF format template for Apple vLocation
        private const val VCARD_TEMPLATE = """BEGIN:VCARD
VERSION:3.0
PRODID:-//Apple Inc.//macOS 13.0//EN
N:;Current Location;;;
FN:Current Location
URL;type=pref:https://maps.apple.com/?ll=%s\,%s&q=%s\,%s
END:VCARD
"""
    }

    /**
     * Creates an Apple vLocation VCF string from coordinates.
     *
     * @param latitude The latitude coordinate
     * @param longitude The longitude coordinate
     * @return VCF string in Apple vLocation format
     */
    fun createVLocationString(latitude: Double, longitude: Double): String {
        val latStr = latitude.toString()
        val lngStr = longitude.toString()
        return String.format(VCARD_TEMPLATE, latStr, lngStr, latStr, lngStr)
    }

    /**
     * Creates a vLocation file and returns its URI for sending as attachment.
     *
     * @param latitude The latitude coordinate
     * @param longitude The longitude coordinate
     * @return URI to the created vLocation file
     */
    fun createVLocationFile(latitude: Double, longitude: Double): Uri {
        Timber.d("[LOCATION_DEBUG] createVLocationFile: lat=$latitude, lng=$longitude")

        val vcfContent = createVLocationString(latitude, longitude)
        Timber.d("[LOCATION_DEBUG] VCF content:\n$vcfContent")

        val guid = "temp-${UUID.randomUUID().toString().take(8)}"
        val fileName = "$guid-CL$FILE_EXTENSION"
        Timber.d("[LOCATION_DEBUG] Generated filename: $fileName")

        // Write to cache directory
        val cacheDir = File(context.cacheDir, "vlocation")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val file = File(cacheDir, fileName)
        file.writeText(vcfContent)
        Timber.d("[LOCATION_DEBUG] File written to: ${file.absolutePath}, exists=${file.exists()}, size=${file.length()}")

        // Return content URI via FileProvider
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        Timber.d("[LOCATION_DEBUG] FileProvider URI: $uri")
        return uri
    }

    /**
     * Parses an Apple Maps URL from a vLocation VCF string.
     *
     * @param vcfContent The VCF file content
     * @return Apple Maps URL or null if not found
     */
    fun parseAppleMapUrl(vcfContent: String): String? {
        val lines = vcfContent.split("\n")
        val urlLine = lines.firstOrNull { it.contains("URL") }
        return urlLine?.substringAfter("pref:")?.replace("\\,", ",")
    }

    /**
     * Extracts coordinates from a vLocation VCF string.
     *
     * @param vcfContent The VCF file content
     * @return Pair of (latitude, longitude) or null if parsing fails
     */
    fun parseCoordinates(vcfContent: String): Pair<Double, Double>? {
        val url = parseAppleMapUrl(vcfContent) ?: return null

        // Parse ll= parameter: https://maps.apple.com/?ll=LAT,LNG&q=...
        val llMatch = Regex("""ll=(-?\d+\.?\d*),(-?\d+\.?\d*)""").find(url)
        if (llMatch != null) {
            val lat = llMatch.groupValues[1].toDoubleOrNull()
            val lng = llMatch.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null) {
                return Pair(lat, lng)
            }
        }

        return null
    }

    /**
     * Converts a vLocation to a Google Maps URL for rendering.
     *
     * @param vcfContent The VCF file content
     * @return Google Maps URL or null if parsing fails
     */
    fun toGoogleMapsUrl(vcfContent: String): String? {
        val coords = parseCoordinates(vcfContent) ?: return null
        return "https://maps.google.com/?q=${coords.first},${coords.second}"
    }

    /**
     * Checks if a MIME type is a vLocation.
     */
    fun isVLocation(mimeType: String?): Boolean {
        return mimeType == MIME_TYPE
    }

    /**
     * Checks if a UTI is a vLocation.
     */
    fun isVLocationUti(uti: String?): Boolean {
        return uti == UTI
    }

    /**
     * Cleans up temporary vLocation files older than 1 hour.
     */
    fun cleanupOldFiles() {
        val cacheDir = File(context.cacheDir, "vlocation")
        if (cacheDir.exists()) {
            val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000)
            cacheDir.listFiles()?.forEach { file ->
                if (file.lastModified() < oneHourAgo) {
                    file.delete()
                }
            }
        }
    }
}
