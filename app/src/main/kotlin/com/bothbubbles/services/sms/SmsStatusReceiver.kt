package com.bothbubbles.services.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import android.telephony.SmsMessage
import timber.log.Timber
import com.bothbubbles.di.ApplicationScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SmsStatusReceiverEntryPoint {
        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    companion object {
        private const val TAG = "SmsStatusReceiver"
        private const val EXTRA_ERROR_CODE = "errorCode"

        // Delay before marking as failed to give carrier time to correct status.
        // Some carriers report failure initially then success shortly after.
        private const val FAILURE_DELAY_MS = 10_000L
    }

    @Inject
    lateinit var smsSendService: SmsSendService

    override fun onReceive(context: Context, intent: Intent) {
        val messageGuid = intent.getStringExtra(SmsSendService.EXTRA_MESSAGE_GUID) ?: return
        val partIndex = intent.getIntExtra(SmsSendService.EXTRA_PART_INDEX, 0)

        val pendingResult = goAsync()

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SmsStatusReceiverEntryPoint::class.java
        )

        entryPoint.applicationScope().launch(Dispatchers.IO) {
            try {
                when (intent.action) {
                    SmsSendService.ACTION_SMS_SENT -> handleSentResult(context, messageGuid, partIndex, intent)
                    SmsSendService.ACTION_SMS_DELIVERED -> handleDeliveredResult(context, messageGuid, partIndex, intent)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleSentResult(context: Context, messageGuid: String, partIndex: Int, intent: Intent) {
        val errorCode = intent.getIntExtra(EXTRA_ERROR_CODE, 0)

        val (status, errorMessage) = when (resultCode) {
            Activity.RESULT_OK -> {
                Timber.d("SMS sent successfully: $messageGuid part $partIndex")
                "sent" to null
            }
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                Timber.e("SMS send failed (generic, errorCode=$errorCode): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode, errorCode)
            }
            SmsManager.RESULT_ERROR_NO_SERVICE -> {
                Timber.e("SMS send failed (no service): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            SmsManager.RESULT_ERROR_NULL_PDU -> {
                Timber.e("SMS send failed (null PDU): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            SmsManager.RESULT_ERROR_RADIO_OFF -> {
                Timber.e("SMS send failed (radio off): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> {
                Timber.e("SMS send failed (limit exceeded): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> {
                Timber.e("SMS send failed (short code not allowed): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> {
                Timber.e("SMS send failed (short code never allowed): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> {
                Timber.e("SMS send failed (FDN check failure): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            SmsManager.RESULT_NO_DEFAULT_SMS_APP -> {
                Timber.e("SMS send failed (not default app): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
            else -> {
                Timber.e("SMS send failed (code $resultCode): $messageGuid")
                "failed" to SmsErrorHelper.getSmsErrorMessage(context, resultCode)
            }
        }

        // For failures, delay briefly before marking as failed.
        // This gives the carrier time to potentially send a corrected success status,
        // and avoids the user seeing a flash of "failed" for transient issues.
        // We intentionally do NOT auto-retry to avoid duplicate SMS sends - carriers
        // sometimes report failure even when the message was actually delivered.
        if (status == "failed") {
            Timber.d("SMS reported failed, waiting ${FAILURE_DELAY_MS}ms before marking: $messageGuid")
            delay(FAILURE_DELAY_MS)

            // Check if message was already marked as sent during the delay
            // (e.g., a success broadcast came through for another part)
            val currentStatus = smsSendService.getMessageStatus(messageGuid)
            if (currentStatus == "sent" || currentStatus == "delivered") {
                Timber.d("SMS $messageGuid already marked as $currentStatus, ignoring failure")
                return
            }
        }

        smsSendService.updateMessageStatus(
            messageGuid = messageGuid,
            status = status,
            errorCode = if (status == "failed") resultCode else null,
            errorMessage = errorMessage
        )
    }

    private suspend fun handleDeliveredResult(context: Context, messageGuid: String, partIndex: Int, intent: Intent) {
        val deliveryStatus = extractDeliveryStatus(intent)

        val (status, errorMessage) = when (deliveryStatus) {
            Sms.STATUS_COMPLETE -> {
                Timber.d("SMS delivered: $messageGuid part $partIndex")
                "delivered" to null
            }
            Sms.STATUS_PENDING -> {
                Timber.d("SMS delivery pending: $messageGuid part $partIndex")
                "sent" to null // Keep as sent while pending
            }
            Sms.STATUS_FAILED -> {
                Timber.w("SMS delivery failed: $messageGuid part $partIndex")
                "delivery_failed" to SmsErrorHelper.getDeliveryFailedMessage(context)
            }
            else -> {
                Timber.w("SMS delivery unknown (status: $deliveryStatus): $messageGuid")
                "sent" to null // Keep as sent if delivery status unknown
            }
        }

        smsSendService.updateMessageStatus(
            messageGuid = messageGuid,
            status = status,
            errorCode = if (status == "delivery_failed") deliveryStatus else null,
            errorMessage = errorMessage
        )
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
            Timber.e(e, "Failed to parse delivery PDU")
            // Fall back to result code on parse failure
            when (resultCode) {
                Activity.RESULT_OK -> Sms.STATUS_COMPLETE
                Activity.RESULT_CANCELED -> Sms.STATUS_FAILED
                else -> Sms.STATUS_NONE
            }
        }
    }
}
