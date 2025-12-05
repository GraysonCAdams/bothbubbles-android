package com.bluebubbles.services.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.bluebubbles.data.repository.SmsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives MMS messages when app is default SMS handler
 */
@AndroidEntryPoint
class MmsBroadcastReceiver : BroadcastReceiver() {

    @Inject
    lateinit var smsRepository: SmsRepository

    companion object {
        private const val TAG = "MmsBroadcastReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION,
            Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION -> {
                handleMmsReceived(context, intent)
            }
        }
    }

    private fun handleMmsReceived(context: Context, intent: Intent) {
        Log.d(TAG, "MMS received")
        // MMS handling is more complex - typically requires parsing PDU data
        // For now, we rely on the SmsContentObserver to pick up new MMS
        // from the content provider after the system processes it
    }
}
