package com.bluebubbles.services.sms

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for managing SMS-related permissions and default SMS app status.
 */
@Singleton
class SmsPermissionHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /**
         * All permissions required for full SMS functionality
         */
        val SMS_PERMISSIONS = arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.RECEIVE_WAP_PUSH,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.READ_CONTACTS
        )

        /**
         * Minimum permissions for basic SMS reading
         */
        val SMS_READ_PERMISSIONS = arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS
        )

        /**
         * Permissions for sending SMS
         */
        val SMS_SEND_PERMISSIONS = arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
    }

    /**
     * Check if app has permission to read SMS
     */
    fun hasReadSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if app has permission to send SMS
     */
    fun hasSendSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if app has permission to receive SMS
     */
    fun hasReceiveSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if app has all required SMS permissions
     */
    fun hasAllSmsPermissions(): Boolean {
        return SMS_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Get list of missing SMS permissions
     */
    fun getMissingSmsPermissions(): List<String> {
        return SMS_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if this app is the default SMS app
     */
    fun isDefaultSmsApp(): Boolean {
        val defaultPackage = Telephony.Sms.getDefaultSmsPackage(context)
        return defaultPackage == context.packageName
    }

    /**
     * Get the current default SMS app package name
     */
    fun getDefaultSmsApp(): String? {
        return Telephony.Sms.getDefaultSmsPackage(context)
    }

    /**
     * Create intent to request becoming the default SMS app
     */
    fun createDefaultSmsAppIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ uses RoleManager
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
            roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            // Older versions use standard intent
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
            }
        }
    }

    /**
     * Check if device supports SMS (has telephony feature)
     */
    fun deviceSupportsSms(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }

    /**
     * Check if device supports MMS
     */
    fun deviceSupportsMms(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA)
    }

    /**
     * Check if user should be shown permission rationale
     */
    fun shouldShowPermissionRationale(activity: Activity): Boolean {
        return SMS_PERMISSIONS.any { permission ->
            activity.shouldShowRequestPermissionRationale(permission)
        }
    }

    /**
     * Get a user-friendly description of what each permission is used for
     */
    fun getPermissionDescription(permission: String): String {
        return when (permission) {
            Manifest.permission.SEND_SMS -> "Send text messages"
            Manifest.permission.RECEIVE_SMS -> "Receive incoming text messages"
            Manifest.permission.READ_SMS -> "Read existing text messages"
            Manifest.permission.RECEIVE_MMS -> "Receive multimedia messages"
            Manifest.permission.RECEIVE_WAP_PUSH -> "Receive MMS download notifications"
            Manifest.permission.READ_PHONE_STATE -> "Access phone state for dual SIM support"
            Manifest.permission.READ_PHONE_NUMBERS -> "Read your phone number"
            Manifest.permission.READ_CONTACTS -> "Show contact names in conversations"
            else -> "Unknown permission"
        }
    }

    /**
     * Get SMS capability status
     */
    fun getSmsCapabilityStatus(): SmsCapabilityStatus {
        return SmsCapabilityStatus(
            deviceSupportsSms = deviceSupportsSms(),
            deviceSupportsMms = deviceSupportsMms(),
            hasReadPermission = hasReadSmsPermission(),
            hasSendPermission = hasSendSmsPermission(),
            hasReceivePermission = hasReceiveSmsPermission(),
            isDefaultSmsApp = isDefaultSmsApp(),
            missingPermissions = getMissingSmsPermissions()
        )
    }
}

/**
 * Data class representing current SMS capability status
 */
data class SmsCapabilityStatus(
    val deviceSupportsSms: Boolean,
    val deviceSupportsMms: Boolean,
    val hasReadPermission: Boolean,
    val hasSendPermission: Boolean,
    val hasReceivePermission: Boolean,
    val isDefaultSmsApp: Boolean,
    val missingPermissions: List<String>
) {
    /**
     * Can we read existing SMS messages?
     */
    val canReadSms: Boolean get() = deviceSupportsSms && hasReadPermission

    /**
     * Can we send SMS messages?
     */
    val canSendSms: Boolean get() = deviceSupportsSms && hasSendPermission

    /**
     * Can we receive new SMS messages?
     */
    val canReceiveSms: Boolean get() = deviceSupportsSms && hasReceivePermission

    /**
     * Is SMS fully functional (read, write, receive)?
     */
    val isFullyFunctional: Boolean get() = canReadSms && canSendSms && canReceiveSms

    /**
     * Do we need the user to do something to enable SMS?
     */
    val needsSetup: Boolean get() = deviceSupportsSms && !isFullyFunctional
}
