package com.bluebubbles.services.spam

import android.content.Context
import android.provider.ContactsContract
import android.net.Uri
import android.util.Log
import com.bluebubbles.data.local.db.dao.HandleDao
import com.bluebubbles.data.local.prefs.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device spam detection using heuristics.
 *
 * Calculates a spam score (0-100) based on:
 * - Unknown sender (not in contacts)
 * - Short code sender (5-6 digits, common for promotional SMS)
 * - Alphanumeric sender ID
 * - Suspicious URLs (shortened links, IP addresses, sketchy TLDs)
 * - Urgency keywords ("act now", "verify account")
 * - Money/prize keywords ("won", "claim", "$")
 * - Previously reported sender
 * - ALL CAPS message
 * - First message from sender
 *
 * Score >= 70 = Auto-mark as spam, no notification
 * Whitelisted senders skip detection entirely.
 */
@Singleton
class SpamDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val handleDao: HandleDao,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        private const val TAG = "SpamDetector"

        const val SPAM_THRESHOLD = 70

        // Score weights
        private const val WEIGHT_UNKNOWN_SENDER = 25
        private const val WEIGHT_SHORT_CODE = 15
        private const val WEIGHT_ALPHANUMERIC_SENDER = 10
        private const val WEIGHT_SUSPICIOUS_URL = 20
        private const val WEIGHT_URGENCY_KEYWORDS = 15
        private const val WEIGHT_MONEY_KEYWORDS = 15
        private const val WEIGHT_PREVIOUSLY_REPORTED = 40
        private const val WEIGHT_ALL_CAPS = 10
        private const val WEIGHT_FIRST_MESSAGE = 10

        // Suspicious URL patterns
        private val SUSPICIOUS_URL_PATTERNS = listOf(
            Regex("""bit\.ly/\w+""", RegexOption.IGNORE_CASE),
            Regex("""tinyurl\.com/\w+""", RegexOption.IGNORE_CASE),
            Regex("""t\.co/\w+""", RegexOption.IGNORE_CASE),
            Regex("""goo\.gl/\w+""", RegexOption.IGNORE_CASE),
            Regex("""is\.gd/\w+""", RegexOption.IGNORE_CASE),
            Regex("""ow\.ly/\w+""", RegexOption.IGNORE_CASE),
            Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""),  // IP addresses in URLs
            Regex("""[a-z0-9]+\.(xyz|top|click|win|gift|prize|loan|work|cash|club)""", RegexOption.IGNORE_CASE)
        )

        // Urgency language patterns
        private val URGENCY_KEYWORDS = listOf(
            "act now", "urgent", "immediately", "expire", "expires", "expiring",
            "limited time", "hurry", "quick", "fast", "right now", "today only",
            "your account", "verify your", "confirm your", "suspended", "locked",
            "compromised", "unauthorized", "security alert", "action required"
        )

        // Money/prize patterns
        private val MONEY_KEYWORDS = listOf(
            "won", "winner", "winning", "prize", "reward", "claim", "claim your",
            "cash", "gift card", "gift cards", "free money", "lottery",
            "bitcoin", "btc", "crypto", "investment", "payment pending",
            "transfer", "wire", "refund", "irs", "tax refund"
        )

        // Short code pattern (5-6 digit numbers, common for promotional SMS)
        private val SHORT_CODE_PATTERN = Regex("""^\d{5,6}$""")

        // Alphanumeric sender pattern (e.g., "AMZN", "ALERT", "BANK")
        private val ALPHANUMERIC_SENDER_PATTERN = Regex("""^[A-Za-z]{3,11}$""")
    }

    /**
     * Result of spam evaluation
     */
    data class SpamResult(
        val score: Int,
        val isSpam: Boolean,
        val reasons: List<String>
    )

    /**
     * Evaluate a message for spam.
     * Returns a SpamResult with the score, whether it's spam, and the reasons.
     */
    suspend fun evaluate(
        senderAddress: String,
        messageText: String?,
        chatGuid: String
    ): SpamResult {
        // Check if spam detection is enabled
        val isEnabled = settingsDataStore.spamDetectionEnabled.first()
        if (!isEnabled) {
            return SpamResult(0, false, emptyList())
        }

        val text = messageText ?: ""
        val reasons = mutableListOf<String>()
        var score = 0

        // Check if sender is whitelisted
        val handle = handleDao.getHandleByAddressAny(senderAddress)
        if (handle?.isWhitelisted == true) {
            Log.d(TAG, "Sender $senderAddress is whitelisted, skipping spam detection")
            return SpamResult(0, false, emptyList())
        }

        // Check if sender was previously reported
        if (handle != null && handle.spamReportCount > 0) {
            score += WEIGHT_PREVIOUSLY_REPORTED
            reasons.add("Previously reported sender")
        }

        // Check if sender is in contacts
        val isInContacts = isAddressInContacts(senderAddress) || handle?.cachedDisplayName != null
        if (!isInContacts) {
            score += WEIGHT_UNKNOWN_SENDER
            reasons.add("Unknown sender")
        }

        // Check for short code sender (5-6 digit numbers)
        val normalizedAddress = senderAddress.replace(Regex("[^0-9a-zA-Z]"), "")
        if (SHORT_CODE_PATTERN.matches(normalizedAddress)) {
            score += WEIGHT_SHORT_CODE
            reasons.add("Short code sender")
        }

        // Check for alphanumeric sender (common for marketing/spam)
        if (ALPHANUMERIC_SENDER_PATTERN.matches(normalizedAddress)) {
            score += WEIGHT_ALPHANUMERIC_SENDER
            reasons.add("Alphanumeric sender ID")
        }

        // Check for suspicious URLs
        if (SUSPICIOUS_URL_PATTERNS.any { it.containsMatchIn(text) }) {
            score += WEIGHT_SUSPICIOUS_URL
            reasons.add("Suspicious URL")
        }

        // Check for urgency keywords
        val lowerText = text.lowercase()
        if (URGENCY_KEYWORDS.any { lowerText.contains(it) }) {
            score += WEIGHT_URGENCY_KEYWORDS
            reasons.add("Urgency language")
        }

        // Check for money/prize keywords
        if (MONEY_KEYWORDS.any { lowerText.contains(it) }) {
            score += WEIGHT_MONEY_KEYWORDS
            reasons.add("Money/prize mention")
        }

        // Check for ALL CAPS message (more than 50% uppercase letters)
        val letters = text.filter { it.isLetter() }
        if (letters.length > 20 && letters.count { it.isUpperCase() } > letters.length * 0.5) {
            score += WEIGHT_ALL_CAPS
            reasons.add("Excessive capitals")
        }

        // Check if this is the first message from this sender
        if (handle == null) {
            score += WEIGHT_FIRST_MESSAGE
            reasons.add("First message from sender")
        }

        // Cap score at 100
        score = score.coerceIn(0, 100)

        // Get threshold from settings
        val threshold = settingsDataStore.spamThreshold.first()
        val isSpam = score >= threshold

        if (isSpam) {
            Log.i(TAG, "Message from $senderAddress classified as spam (score: $score, threshold: $threshold, reasons: $reasons)")
        } else {
            Log.d(TAG, "Message from $senderAddress not spam (score: $score, threshold: $threshold)")
        }

        return SpamResult(score, isSpam, reasons)
    }

    /**
     * Check if a phone number or email is in the device contacts.
     */
    private fun isAddressInContacts(address: String): Boolean {
        return try {
            // Try phone lookup first
            val phoneUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
            context.contentResolver.query(
                phoneUri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.count > 0) return true
            }

            // Try email lookup if it looks like an email
            if (address.contains("@")) {
                val emailUri = Uri.withAppendedPath(
                    ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI,
                    Uri.encode(address)
                )
                context.contentResolver.query(
                    emailUri,
                    arrayOf(ContactsContract.CommonDataKinds.Email._ID),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    return cursor.count > 0
                }
            }

            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking contacts for $address", e)
            false
        }
    }
}
