package com.bothbubbles.services.sms

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Headless service required for the app to be eligible as the default SMS app.
 *
 * This service handles android.intent.action.RESPOND_VIA_MESSAGE, which is triggered
 * when the user chooses to respond to an incoming call via text message ("Reply with message"
 * feature on incoming calls).
 *
 * Without this service declared in the manifest with the proper intent filter and permission,
 * the app cannot be set as the default SMS app.
 */
class HeadlessSmsSendService : Service() {

    companion object {
        private const val TAG = "HeadlessSmsSendService"
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val action = intent.action
        if (TelephonyManager.ACTION_RESPOND_VIA_MESSAGE == action) {
            handleRespondViaMessage(intent)
        }

        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun handleRespondViaMessage(intent: Intent) {
        // Get the recipient address from the intent data (sms:+1234567890 or smsto:+1234567890)
        val uri: Uri? = intent.data
        if (uri == null) {
            Log.e(TAG, "No URI in RESPOND_VIA_MESSAGE intent")
            return
        }

        val recipient = getRecipientFromUri(uri)
        if (recipient.isNullOrBlank()) {
            Log.e(TAG, "Could not extract recipient from URI: $uri")
            return
        }

        // Get the message text from the intent extra
        val message = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (message.isNullOrBlank()) {
            Log.e(TAG, "No message text in RESPOND_VIA_MESSAGE intent")
            return
        }

        Log.d(TAG, "Sending quick-reply SMS to $recipient")

        try {
            val smsManager = getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(message)

            if (parts.size == 1) {
                smsManager.sendTextMessage(
                    recipient,
                    null, // service center - use default
                    message,
                    null, // sent intent - not tracking for quick reply
                    null  // delivery intent
                )
            } else {
                smsManager.sendMultipartTextMessage(
                    recipient,
                    null,
                    parts,
                    null,
                    null
                )
            }

            Log.d(TAG, "Quick-reply SMS sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send quick-reply SMS", e)
        }
    }

    private fun getRecipientFromUri(uri: Uri): String? {
        // URI format is typically: sms:+1234567890 or smsto:+1234567890
        val schemeSpecificPart = uri.schemeSpecificPart
        if (!schemeSpecificPart.isNullOrBlank()) {
            // Remove any extra parameters (after ?)
            return schemeSpecificPart.split("?")[0]
        }
        return null
    }
}
