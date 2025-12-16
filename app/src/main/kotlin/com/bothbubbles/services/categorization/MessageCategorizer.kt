package com.bothbubbles.services.categorization

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device message categorization using keyword matching and ML Kit Entity Extraction.
 *
 * Categories messages into:
 * - Transactions: Bank alerts, payments, purchase confirmations
 * - Deliveries: Package tracking, shipping updates
 * - Promotions: Marketing, deals, offers
 * - Reminders: Appointments, verification codes, alerts
 *
 * Categorization is optimized for automated/business messages (short codes, alphanumeric senders).
 * Personal conversations from regular phone numbers are not categorized.
 */
@Singleton
class MessageCategorizer @Inject constructor(
    private val entityExtractionService: EntityExtractionService
) {
    companion object {
        // Confidence thresholds
        const val HIGH_CONFIDENCE = 80
        const val MEDIUM_CONFIDENCE = 50
        const val LOW_CONFIDENCE = 30

        // ML Kit entity boost values
        private const val ML_MONEY_BOOST = 30
        private const val ML_TRACKING_BOOST = 40
        private const val ML_DATETIME_BOOST = 20

        // Short code pattern (5-6 digit numbers, common for business SMS)
        private val SHORT_CODE_PATTERN = Regex("""^\d{5,6}$""")

        // Alphanumeric sender pattern (e.g., "AMZN", "CHASE", "FEDEX")
        private val ALPHANUMERIC_SENDER_PATTERN = Regex("""^[A-Za-z]{3,11}$""")

        // ===== Transaction Keywords =====
        private val TRANSACTION_KEYWORDS = listOf(
            // Banking
            "payment", "paid", "deposited", "deposit", "withdrawal", "withdrew",
            "balance", "account balance", "bank", "banking", "credit card",
            "debit card", "statement", "transaction", "transferred", "transfer",
            // Payment apps
            "venmo", "zelle", "paypal", "cash app", "apple pay", "google pay",
            "square", "stripe",
            // Purchase
            "purchase", "charged", "refund", "refunded", "receipt", "invoice",
            "order total", "subtotal", "your order", "payment received",
            "payment confirmed", "payment successful"
        )

        // Strong transaction indicators (higher weight)
        private val TRANSACTION_STRONG_KEYWORDS = listOf(
            "your payment of", "you paid", "you sent", "you received",
            "direct deposit", "account ending", "card ending",
            "available balance", "current balance", "pending transaction"
        )

        // ===== Delivery Keywords =====
        private val DELIVERY_KEYWORDS = listOf(
            // Status
            "delivered", "shipped", "shipping", "in transit", "out for delivery",
            "package", "parcel", "shipment", "arrival", "arriving",
            // Carriers
            "fedex", "ups", "usps", "dhl", "amazon", "ontrac", "lasership",
            // Actions
            "tracking", "track your", "track package", "delivery update",
            "delivery attempt", "delivery scheduled", "estimated delivery"
        )

        // Strong delivery indicators
        private val DELIVERY_STRONG_KEYWORDS = listOf(
            "your package", "your order has shipped", "out for delivery",
            "has been delivered", "delivery confirmed", "tracking number"
        )

        // ===== Promotion Keywords =====
        private val PROMOTION_KEYWORDS = listOf(
            // Offers
            "sale", "discount", "% off", "off your", "deal", "deals",
            "offer", "promo", "promotion", "coupon", "code",
            // Urgency
            "limited time", "today only", "ends soon", "last chance",
            "don't miss", "exclusive", "special offer",
            // Actions
            "shop now", "buy now", "order now", "save", "savings",
            "free shipping", "flash sale", "clearance",
            // Subscription
            "subscribe", "unsubscribe", "opt out", "reply stop"
        )

        // Strong promotion indicators
        private val PROMOTION_STRONG_KEYWORDS = listOf(
            "use code", "promo code", "discount code", "% off your",
            "reply stop to", "text stop to"
        )

        // ===== Reminder Keywords =====
        private val REMINDER_KEYWORDS = listOf(
            // Appointments
            "appointment", "reminder", "scheduled", "upcoming",
            "confirm your", "don't forget", "remember to",
            // Verification
            "verification code", "security code", "one-time code",
            "otp", "2fa", "two-factor", "authentication code",
            "verify your", "confirm your identity",
            // Alerts
            "expires", "expiring", "due", "due date", "deadline",
            "renew", "renewal", "alert"
        )

        // Strong reminder indicators
        private val REMINDER_STRONG_KEYWORDS = listOf(
            "your code is", "verification code:", "security code:",
            "your appointment", "appointment reminder",
            "is expiring", "will expire"
        )
    }

    /**
     * Result of message categorization.
     */
    data class CategoryResult(
        val category: MessageCategory?,
        val confidence: Int,
        val reasons: List<String>,
        val usedMlKit: Boolean = false
    ) {
        val hasCategory: Boolean get() = category != null
    }

    /**
     * Categorize a single message.
     *
     * @param messageText The message content
     * @param senderAddress The sender's phone number or ID
     * @param useMlKit Whether to use ML Kit for enhanced detection (if model downloaded)
     * @return CategoryResult with category, confidence, and reasons
     */
    suspend fun categorize(
        messageText: String?,
        senderAddress: String,
        useMlKit: Boolean = true
    ): CategoryResult {
        val text = messageText ?: return CategoryResult(null, 0, listOf("No message text"))

        // Skip very short messages from regular phone numbers (likely personal)
        if (!isBusinessSender(senderAddress) && text.length < 40) {
            return CategoryResult(null, 0, listOf("Short message from personal sender"))
        }

        // Calculate keyword scores for each category
        val scores = calculateKeywordScores(text)

        // Apply ML Kit boost if enabled and model available
        var usedMlKit = false
        if (useMlKit && entityExtractionService.modelDownloaded.value) {
            val entities = entityExtractionService.extractEntities(text)
            if (!entities.isEmpty) {
                usedMlKit = true
                applyMlKitBoost(scores, entities)
            }
        }

        // Find the best category
        val bestCategory = scores.maxByOrNull { it.value.score }

        return if (bestCategory != null && bestCategory.value.score >= LOW_CONFIDENCE) {
            CategoryResult(
                category = bestCategory.key,
                confidence = bestCategory.value.score.coerceAtMost(100),
                reasons = bestCategory.value.reasons,
                usedMlKit = usedMlKit
            ).also {
                Timber.d("Categorized as ${it.category?.name}: confidence=${it.confidence}, reasons=${it.reasons}")
            }
        } else {
            CategoryResult(null, 0, listOf("No category match"), usedMlKit)
        }
    }

    /**
     * Check if sender appears to be a business/automated sender.
     */
    private fun isBusinessSender(address: String): Boolean {
        val normalized = address.replace(Regex("[^0-9a-zA-Z]"), "")
        return SHORT_CODE_PATTERN.matches(normalized) ||
                ALPHANUMERIC_SENDER_PATTERN.matches(normalized)
    }

    private data class ScoreData(
        var score: Int = 0,
        val reasons: MutableList<String> = mutableListOf()
    )

    private fun calculateKeywordScores(text: String): MutableMap<MessageCategory, ScoreData> {
        val lowerText = text.lowercase()
        val scores = mutableMapOf(
            MessageCategory.TRANSACTIONS to ScoreData(),
            MessageCategory.DELIVERIES to ScoreData(),
            MessageCategory.PROMOTIONS to ScoreData(),
            MessageCategory.REMINDERS to ScoreData()
        )

        // Transaction scoring
        scores[MessageCategory.TRANSACTIONS]?.apply {
            val strongMatches = TRANSACTION_STRONG_KEYWORDS.count { lowerText.contains(it) }
            val regularMatches = TRANSACTION_KEYWORDS.count { lowerText.contains(it) }

            if (strongMatches > 0) {
                score += strongMatches * 25
                reasons.add("Strong transaction keywords ($strongMatches)")
            }
            if (regularMatches > 0) {
                score += regularMatches * 10
                reasons.add("Transaction keywords ($regularMatches)")
            }
        }

        // Delivery scoring
        scores[MessageCategory.DELIVERIES]?.apply {
            val strongMatches = DELIVERY_STRONG_KEYWORDS.count { lowerText.contains(it) }
            val regularMatches = DELIVERY_KEYWORDS.count { lowerText.contains(it) }

            if (strongMatches > 0) {
                score += strongMatches * 25
                reasons.add("Strong delivery keywords ($strongMatches)")
            }
            if (regularMatches > 0) {
                score += regularMatches * 10
                reasons.add("Delivery keywords ($regularMatches)")
            }
        }

        // Promotion scoring
        scores[MessageCategory.PROMOTIONS]?.apply {
            val strongMatches = PROMOTION_STRONG_KEYWORDS.count { lowerText.contains(it) }
            val regularMatches = PROMOTION_KEYWORDS.count { lowerText.contains(it) }

            if (strongMatches > 0) {
                score += strongMatches * 25
                reasons.add("Strong promotion keywords ($strongMatches)")
            }
            if (regularMatches > 0) {
                score += regularMatches * 10
                reasons.add("Promotion keywords ($regularMatches)")
            }
        }

        // Reminder scoring
        scores[MessageCategory.REMINDERS]?.apply {
            val strongMatches = REMINDER_STRONG_KEYWORDS.count { lowerText.contains(it) }
            val regularMatches = REMINDER_KEYWORDS.count { lowerText.contains(it) }

            if (strongMatches > 0) {
                score += strongMatches * 25
                reasons.add("Strong reminder keywords ($strongMatches)")
            }
            if (regularMatches > 0) {
                score += regularMatches * 10
                reasons.add("Reminder keywords ($regularMatches)")
            }
        }

        return scores
    }

    private fun applyMlKitBoost(
        scores: MutableMap<MessageCategory, ScoreData>,
        entities: ExtractedEntities
    ) {
        // Money entities boost Transactions
        if (entities.hasMoney) {
            scores[MessageCategory.TRANSACTIONS]?.apply {
                score += ML_MONEY_BOOST
                reasons.add("ML: Money detected (${entities.moneyEntities.size})")
            }
        }

        // Tracking numbers boost Deliveries
        if (entities.hasTrackingNumber) {
            scores[MessageCategory.DELIVERIES]?.apply {
                score += ML_TRACKING_BOOST
                reasons.add("ML: Tracking number detected")
            }
        }

        // DateTime entities boost Reminders (if reminder keywords present)
        if (entities.hasDateTime && (scores[MessageCategory.REMINDERS]?.score ?: 0) > 0) {
            scores[MessageCategory.REMINDERS]?.apply {
                score += ML_DATETIME_BOOST
                reasons.add("ML: DateTime detected")
            }
        }
    }

    /**
     * Determine the best category for a chat based on recent messages.
     * Uses a weighted scoring approach favoring recent messages.
     */
    suspend fun categorizeChatFromMessages(
        messages: List<Pair<String?, String>> // (messageText, senderAddress)
    ): CategoryResult {
        if (messages.isEmpty()) {
            return CategoryResult(null, 0, listOf("No messages to analyze"))
        }

        val categoryCounts = mutableMapOf<MessageCategory, Int>()
        var totalCategorized = 0

        // Weight recent messages more heavily
        messages.forEachIndexed { index, (text, sender) ->
            val result = categorize(text, sender, useMlKit = true)
            if (result.category != null && result.confidence >= MEDIUM_CONFIDENCE) {
                val weight = if (index >= messages.size - 3) 2 else 1 // Last 3 messages count double
                categoryCounts[result.category] = (categoryCounts[result.category] ?: 0) + weight
                totalCategorized += weight
            }
        }

        if (totalCategorized == 0) {
            return CategoryResult(null, 0, listOf("No categorizable messages"))
        }

        val bestCategory = categoryCounts.maxByOrNull { it.value }!!
        val confidence = ((bestCategory.value * 100) / totalCategorized).coerceAtMost(100)

        return CategoryResult(
            category = bestCategory.key,
            confidence = confidence,
            reasons = listOf("${bestCategory.value} of $totalCategorized weighted messages")
        )
    }
}
