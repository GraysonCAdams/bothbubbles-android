package com.bothbubbles.services.context

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the current phone call state from the system.
 *
 * Used by auto-responder rules to trigger responses only when
 * the user is on a phone call.
 *
 * Requires READ_PHONE_STATE permission to read call state.
 */
@Singleton
class CallStateProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val telephonyManager: TelephonyManager by lazy {
        context.getSystemService(TelephonyManager::class.java)
    }

    /**
     * Check if the user is currently on a phone call.
     *
     * This includes both incoming calls (ringing) and active calls.
     *
     * @return true if on a call, false if idle or permission not granted
     */
    fun isOnCall(): Boolean {
        if (!hasPhoneStatePermission()) {
            Timber.d("CallStateProvider: READ_PHONE_STATE permission not granted")
            return false
        }

        return try {
            @Suppress("DEPRECATION")
            val callState = telephonyManager.callState
            callState != TelephonyManager.CALL_STATE_IDLE
        } catch (e: SecurityException) {
            Timber.w(e, "CallStateProvider: SecurityException reading call state")
            false
        }
    }

    /**
     * Check if the user is in an active (ongoing) call, not just ringing.
     */
    fun isInActiveCall(): Boolean {
        if (!hasPhoneStatePermission()) {
            return false
        }

        return try {
            @Suppress("DEPRECATION")
            telephonyManager.callState == TelephonyManager.CALL_STATE_OFFHOOK
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Check if phone is ringing with an incoming call.
     */
    fun isRinging(): Boolean {
        if (!hasPhoneStatePermission()) {
            return false
        }

        return try {
            @Suppress("DEPRECATION")
            telephonyManager.callState == TelephonyManager.CALL_STATE_RINGING
        } catch (e: SecurityException) {
            false
        }
    }

    private fun hasPhoneStatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }
}
