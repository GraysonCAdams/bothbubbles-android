package com.bluebubbles.ui.components

import java.util.regex.Pattern

/**
 * Represents a detected phone number in message text with its position
 */
data class DetectedPhoneNumber(
    val startIndex: Int,
    val endIndex: Int,
    val matchedText: String,
    val normalizedNumber: String // E.164 format or cleaned digits
)

/**
 * Represents a detected verification/PIN code in message text
 */
data class DetectedCode(
    val startIndex: Int,
    val endIndex: Int,
    val matchedText: String,
    val code: String // The actual code digits/characters
)

/**
 * Utility object for detecting phone numbers and verification codes from message text
 */
object PhoneAndCodeParsingUtils {

    // Phone number patterns (ordered from most specific to least specific)
    private val PHONE_PATTERNS = listOf(
        // International format: +1 (555) 123-4567 or +44 20 7946 0958
        Pattern.compile(
            "\\+\\d{1,3}[\\s.-]?\\(?\\d{1,4}\\)?[\\s.-]?\\d{1,4}[\\s.-]?\\d{1,4}[\\s.-]?\\d{0,4}\\b"
        ),
        // US format with parentheses: (555) 123-4567
        Pattern.compile(
            "\\(\\d{3}\\)[\\s.-]?\\d{3}[\\s.-]?\\d{4}\\b"
        ),
        // US format with dashes/dots: 555-123-4567 or 555.123.4567
        Pattern.compile(
            "\\b\\d{3}[\\s.-]\\d{3}[\\s.-]\\d{4}\\b"
        ),
        // US format: 5551234567 (10 digits)
        Pattern.compile(
            "\\b\\d{10}\\b"
        ),
        // International without +: 1-555-123-4567
        Pattern.compile(
            "\\b1[\\s.-]?\\d{3}[\\s.-]?\\d{3}[\\s.-]?\\d{4}\\b"
        ),
        // Short codes: 5-6 digit numbers that aren't likely to be codes
        // Only match if surrounded by phone-related context
        Pattern.compile(
            "(?i)(?:call|text|dial|phone|contact)[\\s:]*\\b(\\d{5,6})\\b"
        )
    )

    // Verification/PIN code patterns
    private val CODE_PATTERNS = listOf(
        // Explicit verification code context: "Your code is 123456"
        Pattern.compile(
            "(?i)(?:code|pin|otp|verification|verify|passcode|password)[\\s:]+(?:is[\\s:]+)?([A-Z0-9]{4,8})\\b"
        ),
        // "123456 is your code"
        Pattern.compile(
            "(?i)\\b([A-Z0-9]{4,8})\\s+(?:is\\s+)?(?:your\\s+)?(?:code|pin|otp|verification|passcode)\\b"
        ),
        // Code with hyphen: "123-456" or "ABC-123"
        Pattern.compile(
            "(?i)(?:code|pin|otp|verification|verify|passcode)[\\s:]+(?:is[\\s:]+)?([A-Z0-9]{3,4}-[A-Z0-9]{3,4})\\b"
        ),
        // G- prefix (Google): "G-123456"
        Pattern.compile(
            "\\bG-\\d{6}\\b"
        ),
        // Common format with dash separator in code context
        Pattern.compile(
            "(?i)\\b([A-Z0-9]{3}-[A-Z0-9]{3,4})\\b(?=.*(?:code|verify|enter))"
        ),
        // Standalone 6-digit code with verification context nearby
        Pattern.compile(
            "(?i)(?:enter|use|type)[\\s:]+(?:code[\\s:]+)?\\b(\\d{6})\\b"
        ),
        // Apple/iCloud style: "Your Apple ID Code is: 123456"
        Pattern.compile(
            "(?i)(?:apple|icloud|id)\\s+code[\\s:]+(?:is[\\s:]+)?([A-Z0-9]{6})\\b"
        ),
        // WhatsApp/Signal style: "Your WhatsApp code is 123-456"
        Pattern.compile(
            "(?i)(?:whatsapp|signal|telegram)\\s+code[\\s:]+(?:is[\\s:]+)?([A-Z0-9-]{6,8})\\b"
        ),
        // Generic "verification code: XXXXXX"
        Pattern.compile(
            "(?i)verification\\s+code[\\s:]+([A-Z0-9]{4,8})\\b"
        ),
        // Banking style: "One-time password: 123456"
        Pattern.compile(
            "(?i)(?:one-time|onetime)\\s+(?:password|code|pin)[\\s:]+([A-Z0-9]{4,8})\\b"
        ),
        // Security code context
        Pattern.compile(
            "(?i)security\\s+code[\\s:]+(?:is[\\s:]+)?([A-Z0-9]{4,8})\\b"
        )
    )

    /**
     * Detects all phone numbers in the given text
     * @return List of detected phone numbers sorted by their position in the text
     */
    fun detectPhoneNumbers(text: String): List<DetectedPhoneNumber> {
        val detectedNumbers = mutableListOf<DetectedPhoneNumber>()
        val coveredRanges = mutableListOf<IntRange>()

        PHONE_PATTERNS.forEach { pattern ->
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                // For patterns with capturing groups, use the group; otherwise use full match
                val groupCount = matcher.groupCount()
                val matchedText = if (groupCount > 0 && matcher.group(1) != null) {
                    matcher.group(1)
                } else {
                    matcher.group()
                }
                val startIndex = if (groupCount > 0 && matcher.group(1) != null) {
                    matcher.start(1)
                } else {
                    matcher.start()
                }
                val endIndex = if (groupCount > 0 && matcher.group(1) != null) {
                    matcher.end(1)
                } else {
                    matcher.end()
                }

                // Check if this range overlaps with already detected numbers
                val overlaps = coveredRanges.any { range ->
                    startIndex < range.last && endIndex > range.first
                }

                if (!overlaps && isValidPhoneNumber(matchedText)) {
                    val normalizedNumber = normalizePhoneNumber(matchedText)
                    detectedNumbers.add(
                        DetectedPhoneNumber(
                            startIndex = startIndex,
                            endIndex = endIndex,
                            matchedText = matchedText,
                            normalizedNumber = normalizedNumber
                        )
                    )
                    coveredRanges.add(startIndex until endIndex)
                }
            }
        }

        return detectedNumbers.sortedBy { it.startIndex }
    }

    /**
     * Detects all verification/PIN codes in the given text
     * @return List of detected codes sorted by their position in the text
     */
    fun detectCodes(text: String): List<DetectedCode> {
        val detectedCodes = mutableListOf<DetectedCode>()
        val coveredRanges = mutableListOf<IntRange>()

        CODE_PATTERNS.forEach { pattern ->
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                // Most patterns have a capturing group for just the code
                val groupCount = matcher.groupCount()
                val code = if (groupCount > 0 && matcher.group(1) != null) {
                    matcher.group(1)
                } else {
                    matcher.group()
                }

                // Find where the code appears in the original match
                val fullMatch = matcher.group()
                val codeStartInMatch = fullMatch.indexOf(code)
                val startIndex = matcher.start() + codeStartInMatch
                val endIndex = startIndex + code.length

                // Check if this range overlaps with already detected codes
                val overlaps = coveredRanges.any { range ->
                    startIndex < range.last && endIndex > range.first
                }

                if (!overlaps && isValidCode(code)) {
                    detectedCodes.add(
                        DetectedCode(
                            startIndex = startIndex,
                            endIndex = endIndex,
                            matchedText = code,
                            code = code.replace("-", "").replace(" ", "")
                        )
                    )
                    coveredRanges.add(startIndex until endIndex)
                }
            }
        }

        return detectedCodes.sortedBy { it.startIndex }
    }

    /**
     * Validates that a matched string is likely a real phone number
     */
    private fun isValidPhoneNumber(number: String): Boolean {
        val digitsOnly = number.filter { it.isDigit() }

        // Must have at least 7 digits (local number) and no more than 15 (E.164 max)
        if (digitsOnly.length < 7 || digitsOnly.length > 15) return false

        // Avoid matching years (1900-2099)
        if (digitsOnly.length == 4 && digitsOnly.toIntOrNull()?.let { it in 1900..2099 } == true) {
            return false
        }

        // Avoid matching common non-phone patterns
        if (digitsOnly.all { it == digitsOnly[0] }) return false // All same digit

        return true
    }

    /**
     * Validates that a matched string is likely a verification code
     */
    private fun isValidCode(code: String): Boolean {
        val cleanCode = code.replace("-", "").replace(" ", "")

        // Must be 4-8 characters
        if (cleanCode.length < 4 || cleanCode.length > 8) return false

        // Must contain at least some digits
        if (!cleanCode.any { it.isDigit() }) return false

        // Avoid common non-code patterns
        if (cleanCode.all { it == cleanCode[0] }) return false // All same character

        return true
    }

    /**
     * Normalizes a phone number to a clean format for dialing and chat identification.
     * Returns a consistent format regardless of input formatting.
     */
    fun normalizePhoneNumber(number: String): String {
        val digitsOnly = number.filter { it.isDigit() || it == '+' }

        // If it starts with +, keep it; otherwise add +1 for US numbers with 10 digits
        return when {
            digitsOnly.startsWith("+") -> digitsOnly
            digitsOnly.length == 10 -> "+1$digitsOnly"
            digitsOnly.length == 11 && digitsOnly.startsWith("1") -> "+$digitsOnly"
            else -> digitsOnly
        }
    }

    /**
     * Checks if the given text contains any detectable phone numbers
     */
    fun containsPhoneNumbers(text: String): Boolean {
        return detectPhoneNumbers(text).isNotEmpty()
    }

    /**
     * Checks if the given text contains any detectable verification codes
     */
    fun containsCodes(text: String): Boolean {
        return detectCodes(text).isNotEmpty()
    }

    /**
     * Detects the first verification code in the text (useful for notifications)
     */
    fun detectFirstCode(text: String): DetectedCode? {
        return detectCodes(text).firstOrNull()
    }
}
