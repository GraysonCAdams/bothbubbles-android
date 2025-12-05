package com.bluebubbles.util

import android.content.Context
import android.telephony.TelephonyManager
import io.michaelrocks.libphonenumber.android.NumberParseException
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import io.michaelrocks.libphonenumber.android.Phonenumber

/**
 * Utility for formatting phone numbers in locale-appropriate formats.
 *
 * For US numbers: (xxx) xxx-xxxx
 * For international numbers: Uses the local format for that country
 */
object PhoneNumberFormatter {

    private var phoneUtil: PhoneNumberUtil? = null
    private var defaultCountryCode: String = "US"

    /**
     * Initialize the formatter with application context.
     * Should be called once during app startup.
     */
    fun init(context: Context) {
        phoneUtil = PhoneNumberUtil.createInstance(context)
        defaultCountryCode = getDeviceCountryCode(context)
    }

    /**
     * Get the device's country code from the telephony manager.
     */
    private fun getDeviceCountryCode(context: Context): String {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return telephonyManager?.networkCountryIso?.uppercase()
            ?: telephonyManager?.simCountryIso?.uppercase()
            ?: "US"
    }

    /**
     * Format a phone number for display.
     *
     * @param phoneNumber The raw phone number string (e.g., "+16175551234")
     * @param countryCode Optional country code override (e.g., "US", "GB")
     * @return Formatted phone number (e.g., "(617) 555-1234") or original if parsing fails
     */
    fun format(phoneNumber: String, countryCode: String? = null): String {
        val util = phoneUtil ?: return phoneNumber

        // Skip formatting for email addresses
        if (phoneNumber.contains("@")) {
            return phoneNumber
        }

        // Skip if it's clearly not a phone number
        val digitsOnly = phoneNumber.replace(Regex("[^0-9]"), "")
        if (digitsOnly.length < 7) {
            return phoneNumber
        }

        return try {
            val region = countryCode?.uppercase() ?: defaultCountryCode
            val parsedNumber: Phonenumber.PhoneNumber = util.parse(phoneNumber, region)

            // Use national format for the number's own country
            if (util.isValidNumber(parsedNumber)) {
                val numberRegion = util.getRegionCodeForNumber(parsedNumber)

                // If the number is from the user's country, use national format
                // Otherwise, use international format
                if (numberRegion == defaultCountryCode) {
                    util.format(parsedNumber, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
                } else {
                    util.format(parsedNumber, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
                }
            } else {
                // Try to format even if not strictly valid (partial numbers, etc.)
                util.format(parsedNumber, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
            }
        } catch (e: NumberParseException) {
            // Return original if parsing fails
            phoneNumber
        }
    }

    /**
     * Check if a string appears to be a phone number.
     */
    fun isPhoneNumber(text: String): Boolean {
        val digitsOnly = text.replace(Regex("[^0-9]"), "")
        return digitsOnly.length >= 7 && text.matches(Regex("^[+\\d\\s()\\-]+$"))
    }
}
