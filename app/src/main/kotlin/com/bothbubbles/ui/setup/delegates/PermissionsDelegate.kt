package com.bothbubbles.ui.setup.delegates

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.bothbubbles.ui.setup.SetupUiState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Handles permission checking for setup.
 */
class PermissionsDelegate(private val context: Context) {

    fun checkPermissions(uiState: MutableStateFlow<SetupUiState>) {
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

        uiState.value = uiState.value.copy(
            hasNotificationPermission = hasNotificationPermission,
            hasContactsPermission = hasContactsPermission,
            isBatteryOptimizationDisabled = isBatteryOptimizationDisabled
        )
    }
}
