package com.bothbubbles.data.local.db.entity

import androidx.compose.runtime.Stable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bothbubbles.util.PhoneNumberFormatter

@Stable
@Entity(
    tableName = "handles",
    indices = [
        Index(value = ["address", "service"], unique = true)
    ]
)
data class HandleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "original_row_id")
    val originalRowId: Int? = null,

    @ColumnInfo(name = "address")
    val address: String,

    @ColumnInfo(name = "formatted_address")
    val formattedAddress: String? = null,

    @ColumnInfo(name = "service")
    val service: String = "iMessage", // iMessage or SMS

    @ColumnInfo(name = "country")
    val country: String? = null,

    @ColumnInfo(name = "color")
    val color: String? = null,

    @ColumnInfo(name = "default_email")
    val defaultEmail: String? = null,

    @ColumnInfo(name = "default_phone")
    val defaultPhone: String? = null,

    // Cached contact info
    @ColumnInfo(name = "cached_display_name")
    val cachedDisplayName: String? = null,

    @ColumnInfo(name = "cached_avatar_path")
    val cachedAvatarPath: String? = null,

    // Inferred name from self-introduction messages (e.g., "Hey it's John")
    @ColumnInfo(name = "inferred_name")
    val inferredName: String? = null,

    // Spam tracking
    @ColumnInfo(name = "spam_report_count", defaultValue = "0")
    val spamReportCount: Int = 0,

    @ColumnInfo(name = "is_whitelisted", defaultValue = "0")
    val isWhitelisted: Boolean = false
) {
    /**
     * Unique identifier for this handle (address + service)
     */
    val uniqueAddressAndService: String
        get() = "$address/$service"

    /**
     * Whether this is an SMS handle
     */
    val isSms: Boolean
        get() = service.equals("SMS", ignoreCase = true)

    /**
     * Whether this is an iMessage handle
     */
    val isIMessage: Boolean
        get() = service.equals("iMessage", ignoreCase = true)

    /**
     * Display name with priority: saved contact > "Maybe: inferred" > formatted address > formatted raw address
     * The final fallback formats the address to strip service suffixes and pretty-print phone numbers.
     */
    val displayName: String
        get() = cachedDisplayName
            ?: inferredName?.let { "Maybe: $it" }
            ?: formattedAddress
            ?: PhoneNumberFormatter.format(address)

    /**
     * Raw display name WITHOUT "Maybe:" prefix - use for contact cards, intents, and avatars
     * The final fallback formats the address to strip service suffixes and pretty-print phone numbers.
     */
    val rawDisplayName: String
        get() = cachedDisplayName
            ?: inferredName
            ?: formattedAddress
            ?: PhoneNumberFormatter.format(address)

    /**
     * Whether this handle has an inferred (unconfirmed) name
     */
    val hasInferredName: Boolean
        get() = cachedDisplayName == null && inferredName != null

    /**
     * Generate initials from the actual name (without "Maybe: " prefix)
     * Strips emojis and non-letter/digit characters before extracting initials
     */
    val initials: String
        get() {
            // Use actual name without "Maybe: " prefix for initials
            val name = cachedDisplayName ?: inferredName ?: formattedAddress ?: address
            // Strip emojis and other non-letter/non-digit characters
            val cleanedName = name.trim()
                .split(" ")
                .map { word -> word.filter { it.isLetterOrDigit() } }
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val parts = cleanedName.split(" ").filter { it.isNotBlank() }
            return when {
                parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}"
                parts.size == 1 && parts.first().length >= 2 -> parts.first().take(2)
                parts.size == 1 -> parts.first().take(1)
                else -> "?"
            }.uppercase()
        }
}
