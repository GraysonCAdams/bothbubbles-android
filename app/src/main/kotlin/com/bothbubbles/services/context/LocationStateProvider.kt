package com.bothbubbles.services.context

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Provides location-based checks for auto-responder rules.
 *
 * Uses the FusedLocationProviderClient to get the user's current location
 * and check if they are inside or outside a specified geofence.
 *
 * Requires ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION permission.
 * For background auto-responses, ACCESS_BACKGROUND_LOCATION is also needed.
 */
@Singleton
class LocationStateProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    companion object {
        private const val TAG = "LocationStateProvider"
        private const val DEFAULT_RADIUS_METERS = 100
    }

    /**
     * Check if the user is currently inside a geofence.
     *
     * @param lat Geofence center latitude
     * @param lng Geofence center longitude
     * @param radiusMeters Geofence radius in meters (defaults to 100m)
     * @return true if inside the geofence, false if outside or location unavailable
     */
    suspend fun isInsideGeofence(
        lat: Double,
        lng: Double,
        radiusMeters: Int = DEFAULT_RADIUS_METERS
    ): Boolean {
        if (!hasLocationPermission()) {
            Timber.d("$TAG: Location permission not granted")
            return false
        }

        return try {
            val currentLocation = getCurrentLocation()
            if (currentLocation == null) {
                Timber.d("$TAG: Current location unavailable")
                return false
            }

            val distance = calculateDistance(
                currentLocation.latitude,
                currentLocation.longitude,
                lat,
                lng
            )

            val isInside = distance <= radiusMeters
            Timber.d("$TAG: Distance to geofence center: ${distance}m, radius: ${radiusMeters}m, inside: $isInside")
            isInside
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Error checking geofence")
            false
        }
    }

    /**
     * Get the current location.
     * Tries last known location first, then requests a fresh location if needed.
     */
    private suspend fun getCurrentLocation(): Location? {
        return getLastKnownLocation() ?: getFreshLocation()
    }

    /**
     * Get the last known location (faster, but may be stale).
     */
    private suspend fun getLastKnownLocation(): Location? = suspendCancellableCoroutine { cont ->
        if (!hasLocationPermission()) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    cont.resume(location)
                }
                .addOnFailureListener { e ->
                    Timber.w(e, "$TAG: Failed to get last location")
                    cont.resume(null)
                }
        } catch (e: SecurityException) {
            Timber.w(e, "$TAG: SecurityException getting last location")
            cont.resume(null)
        }
    }

    /**
     * Request a fresh location (slower, but more accurate).
     */
    private suspend fun getFreshLocation(): Location? = suspendCancellableCoroutine { cont ->
        if (!hasLocationPermission()) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val cancellationToken = CancellationTokenSource()
        cont.invokeOnCancellation { cancellationToken.cancel() }

        try {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cancellationToken.token
            )
                .addOnSuccessListener { location ->
                    cont.resume(location)
                }
                .addOnFailureListener { e ->
                    Timber.w(e, "$TAG: Failed to get fresh location")
                    cont.resume(null)
                }
        } catch (e: SecurityException) {
            Timber.w(e, "$TAG: SecurityException getting fresh location")
            cont.resume(null)
        }
    }

    /**
     * Calculate distance between two coordinates in meters.
     */
    private fun calculateDistance(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0]
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }
}
