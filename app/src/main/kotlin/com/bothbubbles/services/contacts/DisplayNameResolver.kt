package com.bothbubbles.services.contacts

import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.util.PhoneNumberFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized service for resolving display names consistently across the app.
 *
 * This service eliminates inconsistencies in how contact names are displayed
 * in different parts of the app (conversation list, chat view, notifications,
 * message bubbles, thread overlay, etc.).
 *
 * ## Display Name Priority (for all modes):
 * 1. Cached contact name (from Android Contacts)
 * 2. Inferred name (with/without "Maybe:" prefix depending on mode)
 * 3. Formatted phone number / email
 *
 * ## Usage:
 * ```kotlin
 * // Get full display name with "Maybe:" prefix for inferred names
 * val name = resolver.resolveDisplayName(handle)
 *
 * // Get raw name without "Maybe:" prefix (for avatars, intents)
 * val rawName = resolver.resolveDisplayName(handle, includeMaybePrefix = false)
 *
 * // Get first name only (for group chat previews)
 * val firstName = resolver.resolveFirstName(handle)
 *
 * // Build lookup maps for batch processing
 * val maps = resolver.buildLookupMaps(participants)
 * ```
 */
@Singleton
class DisplayNameResolver @Inject constructor() {

    /**
     * Display mode for name resolution.
     */
    enum class DisplayMode {
        /** Full name with "Maybe:" prefix for inferred names */
        FULL,
        /** Full name without "Maybe:" prefix (for avatars, contact cards, intents) */
        RAW,
        /** First name only, no "Maybe:" prefix (for group chat message previews) */
        FIRST_NAME
    }

    /**
     * Resolve the display name for a handle entity.
     *
     * @param handle The handle entity to resolve
     * @param mode The display mode to use
     * @return The resolved display name
     */
    fun resolveDisplayName(handle: HandleEntity, mode: DisplayMode = DisplayMode.FULL): String {
        return when (mode) {
            DisplayMode.FULL -> resolveFullName(handle)
            DisplayMode.RAW -> resolveRawName(handle)
            DisplayMode.FIRST_NAME -> resolveFirstName(handle)
        }
    }

    /**
     * Resolve full display name with "Maybe:" prefix for inferred names.
     * Use for: conversation list titles, chat title bar, notification titles.
     */
    fun resolveFullName(handle: HandleEntity): String {
        return handle.cachedDisplayName
            ?: handle.inferredName?.let { "Maybe: $it" }
            ?: handle.formattedAddress
            ?: PhoneNumberFormatter.format(handle.address)
    }

    /**
     * Resolve raw display name WITHOUT "Maybe:" prefix.
     * Use for: avatars, contact cards, intents, sharing.
     */
    fun resolveRawName(handle: HandleEntity): String {
        return handle.cachedDisplayName
            ?: handle.inferredName
            ?: handle.formattedAddress
            ?: PhoneNumberFormatter.format(handle.address)
    }

    /**
     * Resolve first name only (no "Maybe:" prefix).
     * Use for: group chat message previews, notification sender names.
     *
     * For saved contacts, extracts first word from display name.
     * For inferred names, uses the inferred name directly (usually first name).
     * Falls back to formatted address if no name available.
     */
    fun resolveFirstName(handle: HandleEntity): String {
        // For saved contacts, extract first name from display name
        handle.cachedDisplayName?.let { name ->
            return extractFirstName(name)
        }
        // For inferred names, use the whole thing (it's usually just a first name)
        handle.inferredName?.let { name ->
            return extractFirstName(name)
        }
        // No name available - return formatted address
        return handle.formattedAddress ?: PhoneNumberFormatter.format(handle.address)
    }

    /**
     * Resolve sender name from available data sources.
     *
     * Priority: senderAddress lookup > handleId lookup > formatted address
     *
     * @param senderAddress The sender's address (phone/email)
     * @param handleId The sender's handle ID (database foreign key)
     * @param lookupMaps Prebuilt lookup maps from buildLookupMaps()
     * @param mode The display mode to use
     * @return The resolved sender name, or null if sender info is unavailable
     */
    fun resolveSenderName(
        senderAddress: String?,
        handleId: Long?,
        lookupMaps: LookupMaps,
        mode: DisplayMode = DisplayMode.FULL
    ): String? {
        val maps = when (mode) {
            DisplayMode.FULL -> lookupMaps.fullName
            DisplayMode.RAW -> lookupMaps.rawName
            DisplayMode.FIRST_NAME -> lookupMaps.firstName
        }

        // 1. Try looking up by senderAddress (most accurate for group chats)
        senderAddress?.let { address ->
            val normalized = normalizeAddress(address)
            maps.addressToName[normalized]?.let { return it }
            // No match in map - return formatted phone number
            return PhoneNumberFormatter.format(address)
        }

        // 2. Fall back to handleId lookup
        return handleId?.let { maps.handleIdToName[it] }
    }

    /**
     * Build lookup maps for batch name resolution.
     *
     * Creates efficient lookup structures for resolving names from either
     * address or handle ID. Maps are built for all display modes.
     *
     * @param participants List of handle entities to build maps from
     * @return LookupMaps containing address-to-name and handleId-to-name maps
     */
    fun buildLookupMaps(participants: List<HandleEntity>): LookupMaps {
        val fullAddressToName = mutableMapOf<String, String>()
        val fullHandleIdToName = mutableMapOf<Long, String>()
        val rawAddressToName = mutableMapOf<String, String>()
        val rawHandleIdToName = mutableMapOf<Long, String>()
        val firstAddressToName = mutableMapOf<String, String>()
        val firstHandleIdToName = mutableMapOf<Long, String>()
        val addressToAvatarPath = mutableMapOf<String, String?>()
        val addressToHasContactInfo = mutableMapOf<String, Boolean>()

        for (handle in participants) {
            val normalized = normalizeAddress(handle.address)

            // Full names (with "Maybe:" prefix)
            val fullName = resolveFullName(handle)
            fullAddressToName[normalized] = fullName
            fullHandleIdToName[handle.id] = fullName

            // Raw names (without "Maybe:" prefix)
            val rawName = resolveRawName(handle)
            rawAddressToName[normalized] = rawName
            rawHandleIdToName[handle.id] = rawName

            // First names only
            val firstName = resolveFirstName(handle)
            firstAddressToName[normalized] = firstName
            firstHandleIdToName[handle.id] = firstName

            // Avatar paths
            addressToAvatarPath[normalized] = handle.cachedAvatarPath

            // Has contact info (prevents false business detection)
            addressToHasContactInfo[normalized] = handle.cachedDisplayName != null
        }

        return LookupMaps(
            fullName = NameMaps(fullAddressToName, fullHandleIdToName),
            rawName = NameMaps(rawAddressToName, rawHandleIdToName),
            firstName = NameMaps(firstAddressToName, firstHandleIdToName),
            addressToAvatarPath = addressToAvatarPath,
            addressToHasContactInfo = addressToHasContactInfo
        )
    }

    /**
     * Resolve sender avatar path from address.
     *
     * @param senderAddress The sender's address
     * @param lookupMaps Prebuilt lookup maps from buildLookupMaps()
     * @return The avatar path or null if not available
     */
    fun resolveSenderAvatarPath(
        senderAddress: String?,
        lookupMaps: LookupMaps
    ): String? {
        senderAddress?.let { address ->
            val normalized = normalizeAddress(address)
            return lookupMaps.addressToAvatarPath[normalized]
        }
        return null
    }

    /**
     * Resolve whether sender has saved contact info.
     * Used to prevent false business icon detection for contacts named like "ALICE".
     *
     * @param senderAddress The sender's address
     * @param lookupMaps Prebuilt lookup maps from buildLookupMaps()
     * @return True if the sender has saved contact info (cachedDisplayName != null)
     */
    fun resolveSenderHasContactInfo(
        senderAddress: String?,
        lookupMaps: LookupMaps
    ): Boolean {
        senderAddress?.let { address ->
            val normalized = normalizeAddress(address)
            return lookupMaps.addressToHasContactInfo[normalized] ?: false
        }
        return false
    }

    /**
     * Resolve display name from raw handle data values.
     *
     * Used by DAOs that return raw handle fields (like MediaWithSender)
     * instead of full HandleEntity objects.
     *
     * @param cachedDisplayName The cached contact name (from Android Contacts)
     * @param inferredName The inferred name (from message signatures, etc.)
     * @param formattedAddress The pre-formatted phone/email address
     * @param senderAddress The raw sender address
     * @param mode The display mode to use
     * @return The resolved display name
     */
    fun resolveFromRawValues(
        cachedDisplayName: String?,
        inferredName: String?,
        formattedAddress: String?,
        senderAddress: String?,
        mode: DisplayMode = DisplayMode.FULL
    ): String {
        return when (mode) {
            DisplayMode.FULL -> {
                cachedDisplayName
                    ?: inferredName?.let { "Maybe: $it" }
                    ?: formattedAddress
                    ?: senderAddress?.let { formatAddress(it) }
                    ?: "Unknown"
            }
            DisplayMode.RAW -> {
                cachedDisplayName
                    ?: inferredName
                    ?: formattedAddress
                    ?: senderAddress?.let { formatAddress(it) }
                    ?: "Unknown"
            }
            DisplayMode.FIRST_NAME -> {
                val fullName = cachedDisplayName ?: inferredName
                if (fullName != null) {
                    extractFirstName(fullName)
                } else {
                    formattedAddress ?: senderAddress?.let { formatAddress(it) } ?: "Unknown"
                }
            }
        }
    }

    /**
     * Format an address for display.
     */
    private fun formatAddress(address: String): String {
        return if (address.contains("@")) {
            address
        } else {
            // Simple formatting for phone numbers
            val digits = address.filter { it.isDigit() || it == '+' }
            if (digits.length >= 10) {
                val normalized = digits.takeLast(10)
                "(${normalized.take(3)}) ${normalized.substring(3, 6)}-${normalized.takeLast(4)}"
            } else {
                address
            }
        }
    }

    /**
     * Extract the first name from a full name, excluding emojis and non-letter characters.
     * If the input is a phone number (no letters), returns the full input unchanged.
     *
     * This is the single canonical implementation - all other extractFirstName
     * functions have been removed in favor of this one.
     */
    fun extractFirstName(fullName: String): String {
        // If it looks like a phone number, return as-is
        val stripped = fullName.replace(PHONE_NUMBER_CHARS, "")
        if (stripped.all { it.isDigit() } && stripped.length >= 5) {
            return fullName
        }

        // Split by whitespace and find the first word that has letters
        val words = fullName.trim().split(WHITESPACE_REGEX)
        for (word in words) {
            // Filter to only letters/digits
            val cleaned = word.filter { it.isLetterOrDigit() }
            // Check if it has at least one letter (not just digits/emojis)
            if (cleaned.isNotEmpty() && cleaned.any { it.isLetter() }) {
                return cleaned
            }
        }

        // No letters found - this is likely a phone number, return as-is
        return fullName
    }

    /**
     * Normalize an address for comparison/lookup.
     * Strips non-essential characters from phone numbers, lowercases emails.
     */
    fun normalizeAddress(address: String): String {
        return if (address.contains("@")) {
            address.lowercase()
        } else {
            address.replace(NON_PHONE_CHARS, "")
        }
    }

    /**
     * Data class containing name lookup maps for all display modes.
     */
    data class LookupMaps(
        /** Maps for full names (with "Maybe:" prefix) */
        val fullName: NameMaps,
        /** Maps for raw names (without prefix) */
        val rawName: NameMaps,
        /** Maps for first names only */
        val firstName: NameMaps,
        /** Map of normalized address to avatar path */
        val addressToAvatarPath: Map<String, String?>,
        /** Map of normalized address to hasContactInfo (true if cachedDisplayName is not null) */
        val addressToHasContactInfo: Map<String, Boolean> = emptyMap()
    )

    /**
     * Data class containing address-to-name and handleId-to-name maps.
     */
    data class NameMaps(
        val addressToName: Map<String, String>,
        val handleIdToName: Map<Long, String>
    )

    companion object {
        // Precompiled regex patterns for performance
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val PHONE_NUMBER_CHARS = Regex("[+\\-()\\s]")
        private val NON_PHONE_CHARS = Regex("[^0-9+]")
    }
}
