package com.bothbubbles.data

/**
 * Server capabilities based on the macOS version running the BlueBubbles server.
 *
 * Feature support varies by macOS version:
 * - High Sierra (10.13) - Catalina (10.15): Basic iMessage features
 * - Big Sur (11.x) - Monterey (12.x): Added FindMy, full reply support
 * - Ventura (13.x)+: Added edit/unsend message support
 */
data class ServerCapabilities(
    val osVersion: String?,
    val serverVersion: String?,
    val privateApiEnabled: Boolean,
    val helperConnected: Boolean
) {
    /**
     * Parsed macOS version as a comparable pair (major, minor).
     * Examples: "10.15.7" -> (10, 15), "13.4" -> (13, 4), "14.0" -> (14, 0)
     */
    val macOsVersion: Pair<Int, Int>? by lazy {
        osVersion?.let { parseOsVersion(it) }
    }

    /**
     * Human-readable macOS name (e.g., "Ventura", "Monterey")
     */
    val macOsName: String? by lazy {
        macOsVersion?.let { (major, minor) ->
            when {
                major >= 15 -> "Sequoia"
                major >= 14 -> "Sonoma"
                major >= 13 -> "Ventura"
                major >= 12 -> "Monterey"
                major >= 11 -> "Big Sur"
                major == 10 && minor >= 15 -> "Catalina"
                major == 10 && minor >= 14 -> "Mojave"
                major == 10 && minor >= 13 -> "High Sierra"
                else -> "macOS $major.$minor"
            }
        }
    }

    // ===== Feature Flags =====

    /**
     * Core messaging is supported on all versions.
     */
    val canSendMessages: Boolean = true
    val canReceiveMessages: Boolean = true

    /**
     * Attachments are supported on all versions.
     */
    val canSendAttachments: Boolean = true
    val canReceiveAttachments: Boolean = true

    /**
     * Tapbacks, stickers, and mentions are supported on all versions.
     */
    val canReceiveTapbacks: Boolean = true
    val canReceiveStickers: Boolean = true
    val canReceiveMentions: Boolean = true

    /**
     * Delivery and read receipts are supported on all versions.
     */
    val canReceiveDeliveryReceipts: Boolean = true
    val canReceiveReadReceipts: Boolean = true

    /**
     * Reply threads have partial support on older versions (High Sierra - Catalina).
     * Full support on Big Sur (11.0)+.
     */
    val canReceiveReplies: Boolean
        get() = macOsVersion?.let { (major, _) -> major >= 11 } ?: true

    /**
     * Partial reply support indicator for High Sierra - Catalina.
     */
    val hasPartialReplySupport: Boolean
        get() = macOsVersion?.let { (major, minor) ->
            major == 10 && minor in 13..15
        } ?: false

    /**
     * Creating DMs is supported on all versions.
     */
    val canCreateDMs: Boolean = true

    /**
     * Creating group chats is supported on all versions, but has some limitations
     * on Big Sur and Monterey (indicated by ** in compatibility matrix).
     */
    val canCreateGroupChats: Boolean = true

    /**
     * Group chat creation has known limitations on Big Sur (11.x) and Monterey (12.x).
     */
    val hasGroupChatLimitations: Boolean
        get() = macOsVersion?.let { (major, _) -> major in 11..12 } ?: false

    /**
     * Edited/unsent message support requires Ventura (13.0)+.
     */
    val canReceiveEditedMessages: Boolean
        get() = macOsVersion?.let { (major, _) -> major >= 13 } ?: false

    val canReceiveUnsentMessages: Boolean
        get() = macOsVersion?.let { (major, _) -> major >= 13 } ?: false

    /**
     * FindMy device support requires Big Sur (11.0)+.
     */
    val canUseFindMyDevices: Boolean
        get() = macOsVersion?.let { (major, _) -> major >= 11 } ?: false

    /**
     * Sending tapbacks requires Private API to be enabled on the server.
     */
    val canSendTapbacks: Boolean
        get() = privateApiEnabled

    /**
     * Sending replies requires Private API on the server.
     */
    val canSendReplies: Boolean
        get() = privateApiEnabled

    /**
     * Sending with effects requires Private API.
     */
    val canSendWithEffects: Boolean
        get() = privateApiEnabled

    /**
     * Typing indicators require Private API.
     */
    val canSendTypingIndicators: Boolean
        get() = privateApiEnabled

    /**
     * Mark as read requires Private API.
     */
    val canMarkAsRead: Boolean
        get() = privateApiEnabled

    /**
     * Edit messages requires Private API and Ventura (13.0)+.
     */
    val canEditMessages: Boolean
        get() = privateApiEnabled && (macOsVersion?.let { (major, _) -> major >= 13 } ?: false)

    /**
     * Unsend messages requires Private API and Ventura (13.0)+.
     */
    val canUnsendMessages: Boolean
        get() = privateApiEnabled && (macOsVersion?.let { (major, _) -> major >= 13 } ?: false)

    companion object {
        /**
         * Default capabilities when server info is not yet available.
         * Assumes conservative defaults (minimum features).
         */
        val Default = ServerCapabilities(
            osVersion = null,
            serverVersion = null,
            privateApiEnabled = false,
            helperConnected = false
        )

        /**
         * Parse OS version string to (major, minor) pair.
         * Handles formats like "10.15.7", "13.4", "14.0", etc.
         */
        fun parseOsVersion(version: String): Pair<Int, Int>? {
            return try {
                val parts = version.trim().split(".")
                if (parts.isEmpty()) return null
                val major = parts[0].toIntOrNull() ?: return null
                val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
                major to minor
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Create capabilities from server info DTO values.
         */
        fun fromServerInfo(
            osVersion: String?,
            serverVersion: String?,
            privateApiEnabled: Boolean,
            helperConnected: Boolean
        ) = ServerCapabilities(
            osVersion = osVersion,
            serverVersion = serverVersion,
            privateApiEnabled = privateApiEnabled,
            helperConnected = helperConnected
        )
    }
}
