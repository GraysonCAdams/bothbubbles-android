package com.bothbubbles.util.parsing

import com.bothbubbles.util.PhoneNumberFormatter

/**
 * Unified address validation and classification for iMessage availability checks.
 *
 * This is the single source of truth for determining whether an address is:
 * - An email (always routes to iMessage)
 * - A phone number (requires availability check)
 * - Invalid
 *
 * Email validation rules:
 * - Contains "@"
 * - Contains "." after "@"
 * - At least 5 characters (minimum: a@b.c)
 *
 * Phone validation rules:
 * - After removing non-digits (except +): starts with "+" OR has 7+ digits
 */
object AddressValidator {

    /**
     * Classification of an address for routing purposes.
     */
    sealed class AddressType {
        /** Valid email address - always routes to iMessage */
        data object Email : AddressType()

        /** Valid phone number - requires availability check */
        data object Phone : AddressType()

        /** Invalid address format */
        data object Invalid : AddressType()
    }

    /**
     * Validate and classify an address.
     *
     * @param address The raw address string to validate
     * @return The address type classification
     */
    fun validate(address: String): AddressType {
        val trimmed = address.trim()
        if (trimmed.isBlank()) return AddressType.Invalid

        // Email check (most specific)
        if (isEmail(trimmed)) return AddressType.Email

        // Phone check
        if (isPhone(trimmed)) return AddressType.Phone

        return AddressType.Invalid
    }

    /**
     * Check if the address is a valid email.
     *
     * Rules:
     * - Contains "@" (not at the start)
     * - Contains "." after "@"
     * - At least 5 characters total (minimum: a@b.c)
     */
    fun isEmail(address: String): Boolean {
        val atIndex = address.indexOf('@')
        if (atIndex < 1) return false // No @ or @ at start

        val afterAt = address.substring(atIndex + 1)
        if (!afterAt.contains('.')) return false // No . after @
        if (afterAt.indexOf('.') == 0) return false // . immediately after @
        if (afterAt.endsWith('.')) return false // Ends with .

        return address.length >= 5 // Minimum: a@b.c
    }

    /**
     * Check if the address is a valid phone number.
     *
     * Rules:
     * - After removing non-digits (except +): starts with "+" OR has 7+ digits
     */
    fun isPhone(address: String): Boolean {
        // Quick email rejection
        if (address.contains('@')) return false

        val cleaned = address.replace(Regex("[^0-9+]"), "")
        if (cleaned.isEmpty()) return false

        // International format with +
        if (cleaned.startsWith("+")) {
            val digitsAfterPlus = cleaned.substring(1).filter { it.isDigit() }
            return digitsAfterPlus.length >= 7 // E.164 minimum
        }

        // Domestic format
        val digitsOnly = cleaned.filter { it.isDigit() }
        return digitsOnly.length >= 7
    }

    /**
     * Normalize an address for consistent cache keys and lookups.
     *
     * - Emails: lowercase, trimmed
     * - Phones: E.164 format via PhoneNumberFormatter, or cleaned digits
     *
     * @param address The raw address string
     * @return Normalized address, or the trimmed original if normalization fails
     */
    fun normalize(address: String): String {
        val trimmed = address.trim()

        if (isEmail(trimmed)) {
            return trimmed.lowercase()
        }

        // Try PhoneNumberFormatter first (uses libphonenumber for proper E.164)
        val phoneNormalized = PhoneNumberFormatter.normalize(trimmed)
        if (phoneNormalized != null) {
            return phoneNormalized
        }

        // Fallback: clean digits with + prefix handling
        return PhoneAndCodeParsingUtils.normalizePhoneNumber(trimmed)
    }

    /**
     * Extract the phone number digits from an address.
     * Returns null if not a valid phone number.
     */
    fun extractPhoneDigits(address: String): String? {
        if (!isPhone(address)) return null
        return address.replace(Regex("[^0-9+]"), "")
    }

    /**
     * Check if two addresses represent the same contact.
     * Normalizes both and compares.
     */
    fun isSameAddress(address1: String, address2: String): Boolean {
        return normalize(address1) == normalize(address2)
    }
}
