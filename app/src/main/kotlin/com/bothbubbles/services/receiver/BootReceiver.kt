package com.bothbubbles.services.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.services.foreground.SocketForegroundService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives boot completed broadcast and starts services as needed.
 *
 * Handles:
 * - Starting foreground service if "keep app alive" mode is enabled
 * - Starting SMS content observer for external SMS detection (Android Auto, etc.)
 * - FCM is handled automatically by Firebase SDK on boot
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootReceiverEntryPoint {
        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    companion object {
        private const val TAG = "BootReceiver"
    }

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    lateinit var smsRepository: SmsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON"
            )
        ) {
            return
        }

        Log.d(TAG, "Boot completed, checking service settings")

        val pendingResult = goAsync()

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            BootReceiverEntryPoint::class.java
        )

        entryPoint.applicationScope().launch(Dispatchers.IO) {
            try {
                // Check if setup is complete
                val setupComplete = settingsDataStore.isSetupComplete.first()
                if (!setupComplete) {
                    Log.d(TAG, "Setup not complete, skipping service start")
                    pendingResult.finish()
                    return@launch
                }

                // Check notification provider setting
                val provider = settingsDataStore.notificationProvider.first()
                Log.d(TAG, "Notification provider: $provider")

                when (provider) {
                    "foreground" -> {
                        // Start foreground service to maintain socket connection
                        Log.i(TAG, "Starting foreground service on boot")
                        SocketForegroundService.start(context)
                    }
                    "fcm" -> {
                        // FCM is handled automatically by Firebase SDK
                        // Token refresh will trigger re-registration if needed
                        Log.d(TAG, "FCM mode, Firebase will handle push notifications")
                    }
                }

                // Start SMS content observer if we're the default SMS app
                // This detects external SMS sent via Android Auto, Google Assistant, etc.
                if (smsRepository.isDefaultSmsApp()) {
                    Log.i(TAG, "Starting SMS content observer on boot")
                    smsRepository.startObserving()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
