package com.bothbubbles.util

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

    @Volatile
    private var phoneUtil: PhoneNumberUtil? = null
    @Volatile
    private var defaultCountryCode: String = "US"
    private val initLock = Any()

    /**
     * Initialize the formatter with application context.
     * Should be called once during app startup.
     * Thread-safe: multiple calls are safe, only the first call initializes.
     */
    fun init(context: Context) {
        if (phoneUtil != null) return // Fast path - already initialized
        synchronized(initLock) {
            if (phoneUtil == null) {
                phoneUtil = PhoneNumberUtil.createInstance(context)
                defaultCountryCode = getDeviceCountryCode(context)
            }
        }
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
        val util = phoneUtil ?: return stripServiceSuffix(phoneNumber)

        // Skip formatting for email addresses
        if (phoneNumber.contains("@")) {
            return phoneNumber
        }

        // Strip any service suffixes like (smsfp), (smsft), (smsft_fi), etc.
        val cleanedNumber = stripServiceSuffix(phoneNumber)

        // Skip if it's clearly not a phone number
        val digitsOnly = cleanedNumber.replace(Regex("[^0-9]"), "")
        if (digitsOnly.length < 7) {
            return cleanedNumber
        }

        return try {
            val region = countryCode?.uppercase() ?: defaultCountryCode
            val parsedNumber: Phonenumber.PhoneNumber = util.parse(cleanedNumber, region)

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
            // Return cleaned number if parsing fails
            cleanedNumber
        }
    }

    /**
     * Strip service suffixes from phone numbers/identifiers.
     * Removes patterns like (smsfp), (smsft), (smsft_fi), (smsft_rm), (filtered), etc.
     * These are internal identifiers added by the BlueBubbles server for SMS text forwarding chats.
     *
     * This is a public utility that can be used to clean any string that might contain
     * service suffixes before displaying to the user.
     */
    fun stripServiceSuffix(identifier: String): String {
        // Pattern matches one or more consecutive service suffixes at the end of the string
        // Patterns: (smsfp), (smsft), (smsft_fi), (smsft_rm), (smsfp_rm), (ft_rm), (filtered)
        // Handles duplicates like (smsft)(smsft) or (filtered)(filtered)
        return identifier.replace(
            Regex("(\\((sms|ft)[a-z_]*\\)|\\(filtered\\))+$", RegexOption.IGNORE_CASE),
            ""
        )
    }

    /**
     * Check if a string appears to be a phone number.
     */
    fun isPhoneNumber(text: String): Boolean {
        val digitsOnly = text.replace(Regex("[^0-9]"), "")
        return digitsOnly.length >= 7 && text.matches(Regex("^[+\\d\\s()\\-]+$"))
    }

    // ===== Phone Number Normalization for Conversation Merging =====

    /**
     * Normalize a phone number to E.164 format for comparison.
     * E.164 format: +[country code][subscriber number], e.g., +16175551234
     *
     * @param phoneNumber The phone number to normalize
     * @param countryCode Optional country code override
     * @return E.164 formatted number, or null if parsing fails
     */
    fun normalize(phoneNumber: String, countryCode: String? = null): String? {
        val util = phoneUtil ?: return null

        // Skip non-phone addresses (emails, etc.)
        if (phoneNumber.contains("@")) {
            return null
        }

        // Strip any service suffixes before normalizing
        val cleanedNumber = stripServiceSuffix(phoneNumber)

        val digitsOnly = cleanedNumber.replace(Regex("[^0-9]"), "")
        if (digitsOnly.length < 7) {
            return null
        }

        return try {
            val region = countryCode?.uppercase() ?: defaultCountryCode
            val parsedNumber = util.parse(cleanedNumber, region)

            if (util.isValidNumber(parsedNumber)) {
                util.format(parsedNumber, PhoneNumberUtil.PhoneNumberFormat.E164)
            } else {
                // Try to format even if not strictly valid
                util.format(parsedNumber, PhoneNumberUtil.PhoneNumberFormat.E164)
            }
        } catch (e: NumberParseException) {
            null
        }
    }

    /**
     * Check if two addresses refer to the same contact.
     * Handles phone number normalization and email comparison.
     *
     * @param address1 First address (phone or email)
     * @param address2 Second address (phone or email)
     * @return True if the addresses match the same contact
     */
    fun isSameContact(address1: String, address2: String): Boolean {
        // Strip service suffixes before comparison
        val clean1 = stripServiceSuffix(address1)
        val clean2 = stripServiceSuffix(address2)

        // Direct string match (handles email-to-email)
        if (clean1.equals(clean2, ignoreCase = true)) {
            return true
        }

        // Both are emails - already checked above with ignoreCase
        if (clean1.contains("@") || clean2.contains("@")) {
            return false
        }

        // Try normalizing both as phone numbers
        val normalized1 = normalize(clean1)
        val normalized2 = normalize(clean2)

        // If both normalize successfully, compare normalized forms
        if (normalized1 != null && normalized2 != null) {
            return normalized1 == normalized2
        }

        // Fallback: compare digits only (last 10 digits for US-style matching)
        val digits1 = clean1.replace(Regex("[^0-9]"), "")
        val digits2 = clean2.replace(Regex("[^0-9]"), "")

        // Match if last 10 digits are the same (handles +1 vs no country code)
        if (digits1.length >= 10 && digits2.length >= 10) {
            return digits1.takeLast(10) == digits2.takeLast(10)
        }

        return false
    }

    /**
     * Get a normalized key for grouping contacts.
     * Used for merging iMessage and SMS conversations for the same person.
     *
     * @param address The contact address (phone or email)
     * @return A normalized key for grouping, or the original address if normalization fails
     */
    fun getContactKey(address: String): String {
        // Email addresses: lowercase
        if (address.contains("@")) {
            return address.lowercase()
        }

        // Strip service suffixes before processing
        val cleanedAddress = stripServiceSuffix(address)

        // Phone numbers: normalize to E.164
        val normalized = normalize(cleanedAddress)
        if (normalized != null) {
            return normalized
        }

        // Fallback: use last 10 digits or original
        val digits = cleanedAddress.replace(Regex("[^0-9]"), "")
        return if (digits.length >= 10) {
            digits.takeLast(10)
        } else {
            cleanedAddress.lowercase()
        }
    }
}
