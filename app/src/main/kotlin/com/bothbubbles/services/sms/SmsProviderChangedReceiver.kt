package com.bothbubbles.services.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import timber.log.Timber
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.di.ApplicationScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receiver for default SMS app changes (API 24+).
 *
 * When the user changes the default SMS app:
 * 1. If we BECAME the default: Trigger SMS re-sync to import messages
 *    sent by the previous default app while we weren't monitoring
 * 2. If we LOST default: Could optionally stop SMS-specific features
 *
 * This handles the "Android Auto sends SMS while different app is default"
 * scenario - when user switches back to BothBubbles, we catch up on
 * any messages that were sent.
 */
@AndroidEntryPoint
class SmsProviderChangedReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SmsProviderChangedReceiverEntryPoint {
        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    @Inject
    lateinit var smsRepository: SmsRepository

    @Inject
    lateinit var smsPermissionHelper: SmsPermissionHelper

    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // This receiver only makes sense on API 24+
            return
        }

        if (intent.action != Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED) {
            return
        }

        val currentPackage = Telephony.Sms.getDefaultSmsPackage(context)
        val ourPackage = context.packageName
        val isNowDefault = currentPackage == ourPackage

        Timber.i("Default SMS package changed. Current: $currentPackage, IsNowDefault: $isNowDefault")

        val pendingResult = goAsync()

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SmsProviderChangedReceiverEntryPoint::class.java
        )

        entryPoint.applicationScope().launch(Dispatchers.IO) {
            try {
                if (isNowDefault) {
                    handleBecameDefault()
                } else {
                    handleLostDefault()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error handling SMS provider change")
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Called when we become the default SMS app.
     * Import any SMS messages that were sent while we weren't default.
     */
    private suspend fun handleBecameDefault() {
        Timber.i("Became default SMS app - triggering SMS re-sync")

        // Start SMS content observer to catch future messages
        smsRepository.startObserving()

        // Import any SMS messages that were sent while we weren't default
        // This catches messages from Android Auto, other SMS apps, etc.
        try {
            smsRepository.importAllThreads()
            Timber.i("SMS re-sync completed after becoming default")
        } catch (e: Exception) {
            Timber.e(e, "Failed to re-sync SMS after becoming default")
        }
    }

    /**
     * Called when we lose default SMS app status.
     * The SMS content observer will continue running to detect
     * messages sent by the new default app.
     */
    private fun handleLostDefault() {
        Timber.i("Lost default SMS app status - SmsContentObserver will continue monitoring")
        // Note: We don't stop observing here because SmsContentObserver
        // is specifically designed to catch messages from other apps
        // (like Android Auto) when we're NOT the default.
    }
}
