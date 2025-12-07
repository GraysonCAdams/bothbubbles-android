package com.bothbubbles.services.categorization

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Categories for automated message classification.
 *
 * Messages are categorized using a combination of:
 * - Keyword pattern matching
 * - ML Kit Entity Extraction (money, tracking numbers, dates)
 * - Sender type analysis (short codes, alphanumeric IDs)
 */
enum class MessageCategory(
    val displayName: String,
    val icon: ImageVector,
    val description: String
) {
    TRANSACTIONS(
        displayName = "Transactions",
        icon = Icons.Outlined.Receipt,
        description = "Bank alerts, payments, and purchase confirmations"
    ),
    DELIVERIES(
        displayName = "Deliveries",
        icon = Icons.Outlined.LocalShipping,
        description = "Package tracking and shipping updates"
    ),
    PROMOTIONS(
        displayName = "Promotions",
        icon = Icons.Outlined.LocalOffer,
        description = "Marketing messages, deals, and offers"
    ),
    REMINDERS(
        displayName = "Reminders",
        icon = Icons.Outlined.Alarm,
        description = "Appointments, verification codes, and alerts"
    );

    companion object {
        /**
         * Get category by name (case-insensitive).
         * Returns null if not found.
         */
        fun fromName(name: String?): MessageCategory? {
            if (name == null) return null
            return entries.find { it.name.equals(name, ignoreCase = true) }
        }
    }
}
