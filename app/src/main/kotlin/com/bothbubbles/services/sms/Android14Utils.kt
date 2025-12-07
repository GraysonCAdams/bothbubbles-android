package com.bothbubbles.services.sms

import android.content.ContentValues
import android.os.Build
import android.provider.Telephony

/**
 * Utility object for handling Android 14+ compatibility issues with SMS/MMS.
 *
 * Android 14 (API 34, UPSIDE_DOWN_CAKE) introduced stricter restrictions on
 * SMS/MMS permissions and subscription IDs. When writing to the SMS/MMS ContentProvider,
 * setting SUBSCRIPTION_ID can cause permission errors on Android 14+.
 *
 * See: https://developer.android.com/about/versions/14/behavior-changes-14
 */
object Android14Utils {

    /**
     * Sanitize ContentValues for Android 14+ compatibility when writing SMS.
     *
     * On Android 14+, removes the SUBSCRIPTION_ID field which can cause
     * SecurityExceptions when writing to the SMS ContentProvider.
     *
     * @param values The ContentValues to sanitize (modified in place)
     */
    fun sanitizeSmsContentValues(values: ContentValues) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            values.remove(Telephony.Sms.SUBSCRIPTION_ID)
        }
    }

    /**
     * Sanitize ContentValues for Android 14+ compatibility when writing MMS.
     *
     * On Android 14+, removes the SUBSCRIPTION_ID field which can cause
     * SecurityExceptions when writing to the MMS ContentProvider.
     *
     * @param values The ContentValues to sanitize (modified in place)
     */
    fun sanitizeMmsContentValues(values: ContentValues) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            values.remove(Telephony.Mms.SUBSCRIPTION_ID)
        }
    }

    /**
     * Check if we're running on Android 14 or higher.
     */
    fun isAndroid14OrHigher(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }
}
