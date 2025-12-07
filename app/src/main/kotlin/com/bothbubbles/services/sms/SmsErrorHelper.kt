package com.bothbubbles.services.sms

import android.app.Activity
import android.content.Context
import android.telephony.SmsManager
import com.bothbubbles.R

/**
 * Helper class for mapping SMS/MMS error codes to user-friendly messages.
 *
 * Based on Fossify Messages approach for comprehensive error handling.
 * Maps all SmsManager error codes to localized, actionable error messages.
 */
object SmsErrorHelper {

    /**
     * Get a user-friendly error message for an SMS send result.
     *
     * @param context Application context for string resources
     * @param resultCode The result code from the sent PendingIntent
     * @param errorCode Additional error code from the intent (for generic failures)
     * @return User-friendly error message, or null if the message was sent successfully
     */
    fun getSmsErrorMessage(context: Context, resultCode: Int, errorCode: Int = 0): String? {
        return when (resultCode) {
            Activity.RESULT_OK -> null // Success, no error message needed

            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                // For generic failures, check the error code for more specific info
                getGenericFailureMessage(context, errorCode)
            }

            SmsManager.RESULT_ERROR_NO_SERVICE -> {
                context.getString(R.string.sms_error_no_service)
            }

            SmsManager.RESULT_ERROR_NULL_PDU -> {
                context.getString(R.string.sms_error_invalid_message)
            }

            SmsManager.RESULT_ERROR_RADIO_OFF -> {
                context.getString(R.string.sms_error_airplane_mode)
            }

            SmsManager.RESULT_NO_DEFAULT_SMS_APP -> {
                context.getString(R.string.sms_error_not_default_app)
            }

            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> {
                context.getString(R.string.sms_error_rate_limit)
            }

            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> {
                context.getString(R.string.sms_error_short_code_blocked)
            }

            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> {
                context.getString(R.string.sms_error_fdn_check_failed)
            }

            // API 28+ error codes
            RESULT_RIL_ENCODING_ERR,
            RESULT_ENCODING_NOT_SUPPORTED -> {
                context.getString(R.string.sms_error_encoding)
            }

            RESULT_RIL_MODEM_ERR,
            RESULT_MODEM_ERR -> {
                context.getString(R.string.sms_error_network_error)
            }

            RESULT_RIL_NETWORK_ERR,
            RESULT_NETWORK_ERR -> {
                context.getString(R.string.sms_error_network_error)
            }

            RESULT_RIL_NETWORK_NOT_READY -> {
                context.getString(R.string.sms_error_no_service)
            }

            RESULT_RIL_INVALID_SMSC_ADDRESS,
            RESULT_INVALID_SMSC_ADDRESS,
            RESULT_RIL_INVALID_SMS_FORMAT,
            RESULT_INVALID_SMS_FORMAT -> {
                context.getString(R.string.sms_error_invalid_message)
            }

            RESULT_RIL_SIM_ABSENT,
            RESULT_NO_RESOURCES -> {
                context.getString(R.string.sms_error_no_sim)
            }

            RESULT_RIL_NETWORK_REJECT,
            RESULT_NETWORK_REJECT -> {
                context.getString(R.string.sms_error_network_reject)
            }

            RESULT_INVALID_ARGUMENTS -> {
                context.getString(R.string.sms_error_invalid_address)
            }

            RESULT_RIL_SMS_SEND_FAIL_RETRY,
            RESULT_OPERATION_NOT_ALLOWED,
            RESULT_CANCELLED -> {
                context.getString(R.string.sms_error_generic)
            }

            else -> context.getString(R.string.sms_error_unknown, resultCode)
        }
    }

    /**
     * Get error message for generic failure with additional error code.
     */
    private fun getGenericFailureMessage(context: Context, errorCode: Int): String {
        return when (errorCode) {
            SmsManager.RESULT_ERROR_NO_SERVICE -> {
                context.getString(R.string.sms_error_no_service)
            }
            SmsManager.RESULT_ERROR_NULL_PDU -> {
                context.getString(R.string.sms_error_invalid_message)
            }
            SmsManager.RESULT_ERROR_RADIO_OFF -> {
                context.getString(R.string.sms_error_airplane_mode)
            }
            else -> context.getString(R.string.sms_error_generic)
        }
    }

    /**
     * Get a user-friendly error message for an SMS delivery failure.
     *
     * @param context Application context for string resources
     * @return User-friendly delivery failure message
     */
    fun getDeliveryFailedMessage(context: Context): String {
        return context.getString(R.string.sms_delivery_failed)
    }

    /**
     * Check if an error is likely temporary and retrying might help.
     *
     * @param resultCode The SMS send result code
     * @return true if retrying the message might succeed
     */
    fun isRetryable(resultCode: Int): Boolean {
        return when (resultCode) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE,
            SmsManager.RESULT_ERROR_NO_SERVICE,
            SmsManager.RESULT_ERROR_RADIO_OFF,
            RESULT_RIL_NETWORK_NOT_READY,
            RESULT_RIL_MODEM_ERR,
            RESULT_MODEM_ERR,
            RESULT_RIL_NETWORK_ERR,
            RESULT_NETWORK_ERR,
            RESULT_RIL_SMS_SEND_FAIL_RETRY -> true
            else -> false
        }
    }

    // Additional error codes not defined as public constants in SmsManager
    // These are from TelephonyManager internal constants (API 28+)
    private const val RESULT_RIL_ENCODING_ERR = 18
    private const val RESULT_RIL_MODEM_ERR = 16
    private const val RESULT_RIL_NETWORK_ERR = 17
    private const val RESULT_RIL_NETWORK_NOT_READY = 19
    private const val RESULT_RIL_INVALID_SMSC_ADDRESS = 20
    private const val RESULT_RIL_INVALID_SMS_FORMAT = 24
    private const val RESULT_RIL_SIM_ABSENT = 23
    private const val RESULT_RIL_NETWORK_REJECT = 21
    private const val RESULT_RIL_SMS_SEND_FAIL_RETRY = 22

    // API 30+ constants that may not be available on all devices
    private const val RESULT_ENCODING_NOT_SUPPORTED = 109
    private const val RESULT_MODEM_ERR = 16
    private const val RESULT_NETWORK_ERR = 17
    private const val RESULT_INVALID_SMSC_ADDRESS = 102
    private const val RESULT_INVALID_SMS_FORMAT = 106
    private const val RESULT_NO_RESOURCES = 111
    private const val RESULT_NETWORK_REJECT = 103
    private const val RESULT_INVALID_ARGUMENTS = 104
    private const val RESULT_OPERATION_NOT_ALLOWED = 115
    private const val RESULT_CANCELLED = 116
}
