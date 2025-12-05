package com.bluebubbles.services.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Broadcast receiver for SMS sent/delivered status updates.
 * Updates message status in the database based on carrier responses.
 */
@AndroidEntryPoint
class SmsStatusReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsStatusReceiver"
    }

    @Inject
    lateinit var smsSendService: SmsSendService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val messageGuid = intent.getStringExtra(SmsSendService.EXTRA_MESSAGE_GUID) ?: return
        val partIndex = intent.getIntExtra(SmsSendService.EXTRA_PART_INDEX, 0)

        when (intent.action) {
            SmsSendService.ACTION_SMS_SENT -> handleSentResult(messageGuid, partIndex, resultCode)
            SmsSendService.ACTION_SMS_DELIVERED -> handleDeliveredResult(messageGuid, partIndex, resultCode)
        }
    }

    private fun handleSentResult(messageGuid: String, partIndex: Int, resultCode: Int) {
        val status = when (resultCode) {
            Activity.RESULT_OK -> {
                Log.d(TAG, "SMS sent successfully: $messageGuid part $partIndex")
                "sent"
            }
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                Log.e(TAG, "SMS send failed (generic): $messageGuid")
                "failed"
            }
            SmsManager.RESULT_ERROR_NO_SERVICE -> {
                Log.e(TAG, "SMS send failed (no service): $messageGuid")
                "failed"
            }
            SmsManager.RESULT_ERROR_NULL_PDU -> {
                Log.e(TAG, "SMS send failed (null PDU): $messageGuid")
                "failed"
            }
            SmsManager.RESULT_ERROR_RADIO_OFF -> {
                Log.e(TAG, "SMS send failed (radio off): $messageGuid")
                "failed"
            }
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> {
                Log.e(TAG, "SMS send failed (limit exceeded): $messageGuid")
                "failed"
            }
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> {
                Log.e(TAG, "SMS send failed (short code not allowed): $messageGuid")
                "failed"
            }
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> {
                Log.e(TAG, "SMS send failed (short code never allowed): $messageGuid")
                "failed"
            }
            else -> {
                Log.e(TAG, "SMS send failed (unknown: $resultCode): $messageGuid")
                "failed"
            }
        }

        scope.launch {
            smsSendService.updateMessageStatus(
                messageGuid,
                status,
                if (status == "failed") resultCode else null
            )
        }
    }

    private fun handleDeliveredResult(messageGuid: String, partIndex: Int, resultCode: Int) {
        val status = when (resultCode) {
            Activity.RESULT_OK -> {
                Log.d(TAG, "SMS delivered: $messageGuid part $partIndex")
                "delivered"
            }
            Activity.RESULT_CANCELED -> {
                Log.w(TAG, "SMS delivery failed: $messageGuid")
                "delivery_failed"
            }
            else -> {
                Log.w(TAG, "SMS delivery unknown ($resultCode): $messageGuid")
                "sent" // Keep as sent if delivery status unknown
            }
        }

        scope.launch {
            smsSendService.updateMessageStatus(messageGuid, status)
        }
    }
}
