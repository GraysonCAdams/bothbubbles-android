package com.bothbubbles.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.bothbubbles.services.eta.NavigationAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized utility for checking runtime permission states.
 *
 * This class provides a testable, injectable way to check permissions
 * throughout the app without directly calling ContextCompat everywhere.
 *
 * Use this to guard operations that require dangerous permissions,
 * especially in services that may restart after permission revocation.
 */
@Singleton
class PermissionStateMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Check if READ_CONTACTS permission is granted.
     * Required for: ContactPhotoLoader, ContactParser, ContactsContentObserver
     */
    fun hasContactsPermission(): Boolean {
        return checkPermission(Manifest.permission.READ_CONTACTS)
    }

    /**
     * Check if ACCESS_FINE_LOCATION permission is granted.
     * Required for: Location-based features, ETA sharing
     */
    fun hasFineLocationPermission(): Boolean {
        return checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * Check if ACCESS_COARSE_LOCATION permission is granted.
     */
    fun hasCoarseLocationPermission(): Boolean {
        return checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /**
     * Check if any location permission is granted.
     */
    fun hasAnyLocationPermission(): Boolean {
        return hasFineLocationPermission() || hasCoarseLocationPermission()
    }

    /**
     * Check if READ_SMS permission is granted.
     */
    fun hasSmsReadPermission(): Boolean {
        return checkPermission(Manifest.permission.READ_SMS)
    }

    /**
     * Check if SEND_SMS permission is granted.
     */
    fun hasSmsSendPermission(): Boolean {
        return checkPermission(Manifest.permission.SEND_SMS)
    }

    /**
     * Check if POST_NOTIFICATIONS permission is granted (Android 13+).
     * Always returns true for Android 12 and below.
     */
    fun hasNotificationPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            checkPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true // Permission not required on older Android versions
        }
    }

    /**
     * Check a specific permission by name.
     * @param permission The permission constant (e.g., Manifest.permission.READ_CONTACTS)
     * @return true if permission is granted, false otherwise
     */
    fun checkPermission(permission: String): Boolean {
        val result = ContextCompat.checkSelfPermission(context, permission)
        val granted = result == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Timber.d("Permission not granted: $permission")
        }
        return granted
    }

    /**
     * Check multiple permissions at once.
     * @return true only if ALL permissions are granted
     */
    fun hasAllPermissions(vararg permissions: String): Boolean {
        return permissions.all { checkPermission(it) }
    }

    /**
     * Check if any of the specified permissions are granted.
     * @return true if at least one permission is granted
     */
    fun hasAnyPermission(vararg permissions: String): Boolean {
        return permissions.any { checkPermission(it) }
    }

    /**
     * Log the current state of common permissions for debugging.
     */
    fun logPermissionState() {
        Timber.d("Permission State:")
        Timber.d("  - Contacts: ${hasContactsPermission()}")
        Timber.d("  - Fine Location: ${hasFineLocationPermission()}")
        Timber.d("  - Coarse Location: ${hasCoarseLocationPermission()}")
        Timber.d("  - SMS Read: ${hasSmsReadPermission()}")
        Timber.d("  - SMS Send: ${hasSmsSendPermission()}")
        Timber.d("  - Notifications: ${hasNotificationPermission()}")
        Timber.d("  - Accessibility Service: ${hasAccessibilityServiceEnabled()}")
    }

    // ===== Accessibility Service =====

    /**
     * Check if the NavigationAccessibilityService is enabled.
     * This service is used for ETA destination detection from Google Maps/Waze.
     * @return true if the accessibility service is enabled in system settings
     */
    fun hasAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val serviceName = ComponentName(
            context,
            NavigationAccessibilityService::class.java
        ).flattenToString()

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)

        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(serviceName, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    /**
     * Get an intent to open system accessibility settings.
     * User must manually enable the accessibility service from there.
     */
    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
