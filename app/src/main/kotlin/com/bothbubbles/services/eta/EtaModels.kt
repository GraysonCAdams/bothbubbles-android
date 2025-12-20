package com.bothbubbles.services.eta

/**
 * Types of ETA messages that can be sent
 */
enum class EtaMessageType {
    INITIAL,            // "On my way! ETA: X min"
    CHANGE,             // "ETA Update: Now X min..."
    ARRIVING_SOON,      // "Almost there!..."
    ARRIVED             // "I've arrived!"
}

/**
 * Navigation app types we support for ETA scraping
 */
enum class NavigationApp(val packageName: String) {
    GOOGLE_MAPS("com.google.android.apps.maps"),
    WAZE("com.waze");

    companion object {
        fun fromPackage(packageName: String): NavigationApp? {
            return entries.find { it.packageName == packageName }
        }
    }
}

/**
 * Parsed ETA data from a navigation notification
 */
data class ParsedEtaData(
    val etaMinutes: Int,
    val destination: String?,
    val distanceText: String?,  // e.g. "5 mi" or "8 km"
    val arrivalTimeText: String?,  // e.g. "12:45 PM"
    val arrivalTimeMillis: Long? = null,  // Absolute arrival timestamp for change detection
    val navigationApp: NavigationApp,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * State of an active ETA sharing session
 */
data class EtaSharingSession(
    val recipientGuid: String,
    val recipientDisplayName: String,
    val startedAt: Long = System.currentTimeMillis(),
    val lastSentTime: Long = 0,
    val lastEtaMinutes: Int = 0,
    val lastArrivalTimeMillis: Long? = null,  // For arrival-time-based change detection
    val lastChangeMessageTime: Long = 0,       // For cooldown tracking
    val updateCount: Int = 0,
    val lastMessageType: EtaMessageType = EtaMessageType.INITIAL
)

/**
 * Current state of ETA sharing
 */
data class EtaState(
    val isSharing: Boolean = false,
    val session: EtaSharingSession? = null,
    val currentEta: ParsedEtaData? = null,
    val isNavigationActive: Boolean = false
)

/**
 * Reasons why ETA sharing might not be available
 */
sealed class EtaUnavailableReason {
    data object NoNavigationActive : EtaUnavailableReason()
    data object PermissionNotGranted : EtaUnavailableReason()
    data object ServiceNotRunning : EtaUnavailableReason()
}

/**
 * Parsed destination data from accessibility events.
 * Extracted from navigation app UI via AccessibilityService.
 */
data class ParsedDestinationData(
    val destination: String,
    val address: String? = null,
    val navigationApp: NavigationApp,
    val isAndroidAutoMode: Boolean = false,
    val confidence: DestinationConfidence,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Confidence level for destination extraction.
 * Higher confidence means more reliable extraction method.
 */
enum class DestinationConfidence {
    HIGH,    // Found via content description or explicit "Navigate to" pattern
    MEDIUM,  // Found via text pattern matching
    LOW      // Found via positional heuristics or fallback
}
