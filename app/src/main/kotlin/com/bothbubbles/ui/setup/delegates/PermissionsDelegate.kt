package com.bothbubbles.ui.setup.delegates

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Handles permission checking for setup.
 *
 * Phase 9: Uses @Inject constructor with ApplicationContext.
 * Exposes StateFlow<PermissionsState> instead of mutating external state.
 */
class PermissionsDelegate @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow(PermissionsState())
    val state: StateFlow<PermissionsState> = _state.asStateFlow()

    fun checkPermissions() {
        val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isBatteryOptimizationDisabled = powerManager.isIgnoringBatteryOptimizations(context.packageName)

        _state.update {
            it.copy(
                hasNotificationPermission = hasNotificationPermission,
                hasContactsPermission = hasContactsPermission,
                isBatteryOptimizationDisabled = isBatteryOptimizationDisabled
            )
        }
    }
}
