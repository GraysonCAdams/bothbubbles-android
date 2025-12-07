package com.bothbubbles.services.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import android.telephony.SmsMessage
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
 *
 * Uses [SmsErrorHelper] to provide user-friendly error messages for failures.
 */
@AndroidEntryPoint
class SmsStatusReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsStatusReceiver"
        private const val EXTRA_ERROR_CODE = "errorCode"
    }

    @Inject
    lateinit var smsSendService: SmsSendService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val messageGuid = intent.getStringExtra(SmsSendService.EXTRA_MESSAGE_GUID) ?: return
        val partIndex = intent.getIntExtra(SmsSendService.EXTRA_PART_INDEX, 0)

        when (intent.action) {
            SmsSendService.ACTION_SMS_SENT -> handleSentResult(context, messageGuid, partIndex, intent)
            SmsSendService.ACTION_SMS_DELIVERED -> handleDeliveredResult(context, messageGuid, partIndex, intent)
        }
    }

    private fun handleSentResult(context: Context, messageGuid: String, partIndex: Int, intent: Intent) {
        val errorCode = intent.getIntExtra(EXTRA_ERROR_CODE, 0)

        val (status, errorMessage) = when (resultCode) {
            Activity.RESULT_OK -> {
                Log.d(TAG, "SMS sent successfully: $messageGuid part $partIndex")
                "sent" to null
            }
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                Log.e(TAG, "SMS send failed (generic, errorCode=$errorCode): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode, errorCode)
            }
            SmsManager.RESULT_ERROR_NO_SERVICE -> {
                Log.e(TAG, "SMS send failed (no service): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            SmsManager.RESULT_ERROR_NULL_PDU -> {
                Log.e(TAG, "SMS send failed (null PDU): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            SmsManager.RESULT_ERROR_RADIO_OFF -> {
                Log.e(TAG, "SMS send failed (radio off): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> {
                Log.e(TAG, "SMS send failed (limit exceeded): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> {
                Log.e(TAG, "SMS send failed (short code not allowed): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> {
                Log.e(TAG, "SMS send failed (short code never allowed): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> {
                Log.e(TAG, "SMS send failed (FDN check failure): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            SmsManager.RESULT_NO_DEFAULT_SMS_APP -> {
                Log.e(TAG, "SMS send failed (not default app): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            else -> {
                Log.e(TAG, "SMS send failed (code $resultCode): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
        }

        scope.launch {
            // Check if this is a retryable error and attempt auto-retry
            if (status == "failed" && SmsErrorHelper.isRetryable(resultCode)) {
                val retryInitiated = smsSendService.retrySms(messageGuid, resultCode)
                if (retryInitiated) {
                    Log.d(TAG, "Auto-retry initiated for message: $messageGuid")
                    return@launch // Don't update status - retrySms handles it
                }
                // If retry not initiated (max retries exceeded), fall through to update as failed
                Log.d(TAG, "Auto-retry not possible for message: $messageGuid (max retries exceeded)")
            }

            smsSendService.updateMessageStatus(
                messageGuid = messageGuid,
                status = status,
                errorCode = if (status == "failed") resultCode else null,
                errorMessage = errorMessage
            )
        }
    }

    private fun handleDeliveredResult(context: Context, messageGuid: String, partIndex: Int, intent: Intent) {
        val deliveryStatus = extractDeliveryStatus(intent)

        val (status, errorMessage) = when (deliveryStatus) {
            Sms.STATUS_COMPLETE -> {
                Log.d(TAG, "SMS delivered: $messageGuid part $partIndex")
                "delivered" to null
            }
            Sms.STATUS_PENDING -> {
                Log.d(TAG, "SMS delivery pending: $messageGuid part $partIndex")
                "sent" to null // Keep as sent while pending
            }
            Sms.STATUS_FAILED -> {
                Log.w(TAG, "SMS delivery failed: $messageGuid part $partIndex")
                "delivery_failed" to SmsErrorHelper.getDeliveryFailedMessage(context)
            }
            else -> {
                Log.w(TAG, "SMS delivery unknown (status: $deliveryStatus): $messageGuid")
                "sent" to null // Keep as sent if delivery status unknown
            }
        }

        scope.launch {
            smsSendService.updateMessageStatus(
                messageGuid = messageGuid,
                status = status,
                errorCode = if (status == "delivery_failed") deliveryStatus else null,
                errorMessage = errorMessage
            )
        }
    }

    /**
     * Extracts delivery status from the intent PDU, handling both GSM (3GPP) and CDMA (3GPP2) formats.
     *
     * CDMA networks encode status differently in the PDU than GSM networks:
     * - GSM: Uses Activity.RESULT_OK/RESULT_CANCELED in the result code
     * - CDMA: Encodes status in the PDU bytes with error class bits
     */
    private fun extractDeliveryStatus(intent: Intent): Int {
        val pdu = intent.getByteArrayExtra("pdu")
        val format = intent.getStringExtra("format")

        // If no PDU available, fall back to result code
        if (pdu == null || format == null) {
            return when (resultCode) {
                Activity.RESULT_OK -> Sms.STATUS_COMPLETE
                Activity.RESULT_CANCELED -> Sms.STATUS_FAILED
                else -> Sms.STATUS_NONE
            }
        }

        return try {
            if (format == "3gpp2") {
                // CDMA format - parse PDU to extract status
                val message = SmsMessage.createFromPdu(pdu, format)
                val cdmaStatus = message.status

                // CDMA error class is in bits 0-1 of status
                // 0x00 = no error, 0x02 = temporary error (pending), 0x03 = permanent error (failed)
                val errorClass = cdmaStatus and 0x03
                when {
                    // Status 0x02 with error class 0x00 means delivered
                    errorClass == 0x00 && cdmaStatus == 0x02 -> Sms.STATUS_COMPLETE
                    // Error class 0x00 generally means success
                    errorClass == 0x00 -> Sms.STATUS_COMPLETE
                    // Temporary error - still pending
                    errorClass == 0x02 -> Sms.STATUS_PENDING
                    // Permanent error - failed
                    errorClass == 0x03 -> Sms.STATUS_FAILED
                    else -> Sms.STATUS_PENDING
                }
            } else {
                // GSM format (3gpp) - use result code directly
                when (resultCode) {
                    Activity.RESULT_OK -> Sms.STATUS_COMPLETE
                    Activity.RESULT_CANCELED -> Sms.STATUS_FAILED
                    else -> Sms.STATUS_NONE
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse delivery PDU", e)
            // Fall back to result code on parse failure
            when (resultCode) {
                Activity.RESULT_OK -> Sms.STATUS_COMPLETE
                Activity.RESULT_CANCELED -> Sms.STATUS_FAILED
                else -> Sms.STATUS_NONE
            }
        }
    }
}
