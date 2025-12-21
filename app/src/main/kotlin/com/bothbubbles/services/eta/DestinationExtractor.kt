package com.bothbubbles.services.eta

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts navigation destination from Google Maps and Waze UI via AccessibilityService.
 *
 * Uses a multi-strategy approach:
 * 1. Content descriptions (high confidence) - e.g., "Navigate to Home"
 * 2. Text patterns (medium confidence) - e.g., address patterns
 * 3. Positional heuristics (low confidence) - text in expected UI locations
 *
 * Supports both full map navigation view and Android Auto mode (step list).
 */
@Singleton
class DestinationExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DestinationExtractor"

        // Traversal limits for performance
        private const val MAX_DEPTH = 20
        private const val MAX_NODES = 500

        // Google Maps patterns - content descriptions
        // Key pattern: "Destination, Work (111 Lower Wacker Dr)" -> captures "Work (111 Lower Wacker Dr)"
        private val GOOGLE_MAPS_DEST_PATTERNS = listOf(
            Regex("""Destination,\s*(.+)""", RegexOption.IGNORE_CASE),  // Primary: "Destination, Work (address)"
            Regex("""Navigate to (.+)""", RegexOption.IGNORE_CASE),
            Regex("""Navigating to (.+)""", RegexOption.IGNORE_CASE),
            Regex("""Arriving at (.+)""", RegexOption.IGNORE_CASE),
            Regex("""Destination[:\s]+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""Head (?:to|toward) (.+)""", RegexOption.IGNORE_CASE)
        )

        // Waze patterns
        private val WAZE_DEST_PATTERNS = listOf(
            Regex("""Destination,\s*(.+)""", RegexOption.IGNORE_CASE),  // "Destination, Place Name"
            Regex("""To\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""Driving to (.+)""", RegexOption.IGNORE_CASE),
            Regex("""Destination[:\s]+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""Navigate to (.+)""", RegexOption.IGNORE_CASE)
        )

        // Turn instructions to filter out (NOT destinations)
        private val TURN_INSTRUCTION_PATTERNS = listOf(
            Regex("""^Turn\s+(left|right)""", RegexOption.IGNORE_CASE),
            Regex("""^Continue\s+(straight|on|for)""", RegexOption.IGNORE_CASE),
            Regex("""^Keep\s+(left|right)""", RegexOption.IGNORE_CASE),
            Regex("""^Merge\s+""", RegexOption.IGNORE_CASE),
            Regex("""^Exit\s+\d""", RegexOption.IGNORE_CASE),
            Regex("""^Take\s+(the\s+)?(exit|ramp|left|right)""", RegexOption.IGNORE_CASE),
            Regex("""^In\s+\d+""", RegexOption.IGNORE_CASE),
            Regex("""^Arrive\s+""", RegexOption.IGNORE_CASE),
            Regex("""^Head\s+(north|south|east|west|northeast|northwest|southeast|southwest)""", RegexOption.IGNORE_CASE),
            Regex("""^Make a U-turn""", RegexOption.IGNORE_CASE),
            Regex("""^Slight\s+(left|right)""", RegexOption.IGNORE_CASE),
            Regex("""^Sharp\s+(left|right)""", RegexOption.IGNORE_CASE),
            Regex("""^Roundabout""", RegexOption.IGNORE_CASE),
            Regex("""^Enter\s+(the\s+)?roundabout""", RegexOption.IGNORE_CASE),
            Regex("""^Use\s+(the\s+)?(left|right)""", RegexOption.IGNORE_CASE)
        )

        // ETA/time/distance patterns to filter out
        private val ETA_PATTERNS = listOf(
            Regex("""^\d+\s*(min|hr|sec|h|m|s)""", RegexOption.IGNORE_CASE),
            Regex("""^\d{1,2}:\d{2}"""),
            Regex("""^\d+\.?\d*\s*(mi|km|ft|m|miles|kilometers)$""", RegexOption.IGNORE_CASE),
            Regex("""^ETA""", RegexOption.IGNORE_CASE),
            Regex("""^Arrives?\s+at""", RegexOption.IGNORE_CASE),
            Regex("""^Arriving\s+in""", RegexOption.IGNORE_CASE),
            Regex("""^\d+\s*min\s+\(\d""", RegexOption.IGNORE_CASE)
        )

        // Valid destination patterns (addresses and places)
        private val ADDRESS_PATTERN = Regex(
            """^\d+\s+\w+.*(St|Ave|Blvd|Rd|Dr|Ln|Ct|Way|Pl|Cir|Ter|Pkwy|Highway|Hwy)""",
            RegexOption.IGNORE_CASE
        )
        private val SAVED_PLACES_PATTERN = Regex("""^(Home|Work)$""")
    }

    private var nodesVisited = 0

    /**
     * Extract destination from the accessibility window root node.
     * @param rootNode The root AccessibilityNodeInfo from rootInActiveWindow
     * @param app The navigation app (Google Maps or Waze)
     * @return ParsedDestinationData if destination found, null otherwise
     */
    fun extractDestination(
        rootNode: AccessibilityNodeInfo,
        app: NavigationApp
    ): ParsedDestinationData? {
        nodesVisited = 0
        val isAndroidAuto = isAndroidAutoMode()

        Timber.d("$TAG: ========== EXTRACTING DESTINATION ==========")
        Timber.d("$TAG: App: ${app.name}, Android Auto: $isAndroidAuto")

        // Always log screen content for debugging
        Timber.d("$TAG: Screen content:")
        val allContent = debugCollectAllText(rootNode, maxItems = 100)
        allContent.forEachIndexed { index, text ->
            Timber.d("$TAG: [$index] $text")
        }
        Timber.d("$TAG: ==========================================")

        val patterns = when (app) {
            NavigationApp.GOOGLE_MAPS -> GOOGLE_MAPS_DEST_PATTERNS
            NavigationApp.WAZE -> WAZE_DEST_PATTERNS
        }

        // Strategy 1: Google Maps step-list format (Android Auto or expanded directions)
        // Look for "Destination will be..." marker and extract place/address before it
        // This runs BEFORE content description patterns to avoid matching "will be on the right"
        if (app == NavigationApp.GOOGLE_MAPS) {
            val stepListDestination = findGoogleMapsStepListDestination(rootNode)
            if (stepListDestination != null) {
                Timber.d("$TAG: Found Google Maps step-list destination: $stepListDestination")
                return ParsedDestinationData(
                    destination = stepListDestination,
                    navigationApp = app,
                    isAndroidAutoMode = isAndroidAuto,
                    confidence = DestinationConfidence.HIGH
                )
            }
        }

        // Strategy 2: Search content descriptions (highest confidence)
        // Google Maps phone: "Destination, Work (111 Lower Wacker Dr)"
        val fromContentDesc = findDestinationInContentDescription(rootNode, patterns, 0)
        if (fromContentDesc != null) {
            Timber.d("$TAG: Found destination via content description: $fromContentDesc")
            return ParsedDestinationData(
                destination = fromContentDesc,
                navigationApp = app,
                isAndroidAutoMode = isAndroidAuto,
                confidence = DestinationConfidence.HIGH
            )
        }

        // Strategy 3: Waze-specific
        if (app == NavigationApp.WAZE) {
            // 3a: Try step-list format first (destination after last distance instruction)
            // Works for Android Auto and expanded directions - try this before "Your location" method
            val wazeStepListDestination = findWazeStepListDestination(rootNode)
            if (wazeStepListDestination != null) {
                Timber.d("$TAG: Found Waze step-list destination: $wazeStepListDestination")
                return ParsedDestinationData(
                    destination = wazeStepListDestination,
                    navigationApp = app,
                    isAndroidAutoMode = isAndroidAuto,
                    confidence = DestinationConfidence.HIGH
                )
            }

            // 3b: Regular phone - find text after "Your location"
            val wazeDestination = findWazeDestinationAfterYourLocation(rootNode)
            if (wazeDestination != null) {
                Timber.d("$TAG: Found Waze destination after 'Your location': $wazeDestination")
                return ParsedDestinationData(
                    destination = wazeDestination,
                    navigationApp = app,
                    isAndroidAutoMode = isAndroidAuto,
                    confidence = DestinationConfidence.HIGH
                )
            }
        }

        // Strategy 4: Search text content with patterns (medium confidence)
        val fromText = findDestinationInText(rootNode, patterns, 0)
        if (fromText != null) {
            Timber.d("$TAG: Found destination via text pattern: $fromText")
            return ParsedDestinationData(
                destination = fromText,
                navigationApp = app,
                isAndroidAutoMode = isAndroidAuto,
                confidence = DestinationConfidence.MEDIUM
            )
        }

        // Strategy 5: Heuristic search for addresses/places (low confidence)
        // Only use heuristic when on step-list/directions screen, NOT on map screen
        // Map screen is detected by presence of "Map bearing" in content
        val isOnMapScreen = allContent.any { it.contains("Map bearing", ignoreCase = true) }
        if (isOnMapScreen) {
            Timber.d("$TAG: Skipping heuristic - on map screen (detected 'Map bearing')")
        } else {
            val fromHeuristic = findDestinationByHeuristic(rootNode, 0)
            if (fromHeuristic != null) {
                Timber.d("$TAG: Found destination via heuristic: $fromHeuristic")
                return ParsedDestinationData(
                    destination = fromHeuristic,
                    navigationApp = app,
                    isAndroidAutoMode = isAndroidAuto,
                    confidence = DestinationConfidence.LOW
                )
            }
        }

        Timber.w("$TAG: No destination found (visited $nodesVisited nodes)")
        return null
    }

    /**
     * Log all screen content when destination extraction fails.
     * Helps debug what text is available on the navigation screen.
     */
    private fun logScreenContentForDebug(rootNode: AccessibilityNodeInfo, app: NavigationApp) {
        Timber.d("$TAG: ========== DEBUG: Screen Content from ${app.name} ==========")

        val allText = debugCollectAllText(rootNode, maxItems = 100)
        if (allText.isEmpty()) {
            Timber.d("$TAG: No text content found on screen")
        } else {
            Timber.d("$TAG: Found ${allText.size} text items:")
            allText.forEachIndexed { index, text ->
                Timber.d("$TAG: [$index] $text")
            }
        }

        Timber.d("$TAG: ========== END DEBUG ==========")
    }

    /**
     * Search content descriptions for destination patterns.
     */
    private fun findDestinationInContentDescription(
        node: AccessibilityNodeInfo,
        patterns: List<Regex>,
        depth: Int
    ): String? {
        if (depth > MAX_DEPTH || nodesVisited > MAX_NODES) return null
        nodesVisited++

        // Check this node's content description
        node.contentDescription?.toString()?.let { desc ->
            for (pattern in patterns) {
                pattern.find(desc)?.let { match ->
                    val destination = match.groupValues.getOrNull(1)?.trim()
                    if (destination != null && isValidDestination(destination)) {
                        return destination
                    }
                }
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val result = findDestinationInContentDescription(child, patterns, depth + 1)
                if (result != null) return result
            } finally {
                child.recycle()
            }
        }

        return null
    }

    /**
     * Search text content for destination patterns.
     */
    private fun findDestinationInText(
        node: AccessibilityNodeInfo,
        patterns: List<Regex>,
        depth: Int
    ): String? {
        if (depth > MAX_DEPTH || nodesVisited > MAX_NODES) return null
        nodesVisited++

        // Check this node's text
        node.text?.toString()?.let { text ->
            // First check if it matches any destination pattern
            for (pattern in patterns) {
                pattern.find(text)?.let { match ->
                    val destination = match.groupValues.getOrNull(1)?.trim() ?: text.trim()
                    if (isValidDestination(destination)) {
                        return destination
                    }
                }
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val result = findDestinationInText(child, patterns, depth + 1)
                if (result != null) return result
            } finally {
                child.recycle()
            }
        }

        return null
    }

    /**
     * Heuristic search: look for text that looks like a destination
     * (address or saved place name) without explicit pattern prefix.
     */
    private fun findDestinationByHeuristic(
        node: AccessibilityNodeInfo,
        depth: Int
    ): String? {
        if (depth > MAX_DEPTH || nodesVisited > MAX_NODES) return null
        nodesVisited++

        // Check text and content description
        val candidates = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString()
        )

        for (text in candidates) {
            val trimmed = text.trim()
            if (trimmed.isBlank()) continue

            // Check for saved places (Home, Work)
            if (SAVED_PLACES_PATTERN.matches(trimmed)) {
                return trimmed
            }

            // Check for address pattern
            if (ADDRESS_PATTERN.containsMatchIn(trimmed) && isValidDestination(trimmed)) {
                return trimmed
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val result = findDestinationByHeuristic(child, depth + 1)
                if (result != null) return result
            } finally {
                child.recycle()
            }
        }

        return null
    }

    /**
     * Check if the text is a valid destination (not a turn instruction or ETA).
     */
    private fun isValidDestination(text: String): Boolean {
        // Too short or too long
        if (text.length < 2 || text.length > 200) return false

        // Filter out turn instructions
        if (TURN_INSTRUCTION_PATTERNS.any { it.containsMatchIn(text) }) return false

        // Filter out ETA/time/distance
        if (ETA_PATTERNS.any { it.matches(text) || it.containsMatchIn(text) }) return false

        // Filter out single numbers
        if (text.all { it.isDigit() || it.isWhitespace() }) return false

        // Filter out "via" route descriptions
        if (text.startsWith("via ", ignoreCase = true)) return false

        return true
    }

    /**
     * Google Maps step-list format: Find destination by looking for the "Destination will be..."
     * marker at the end of the step list, then extracting place name and address before it.
     * Works for Android Auto and expanded directions view on phone.
     * Example: "The EuroLab" + "1425 Wright Blvd" -> "The EuroLab (1425 Wright Blvd)"
     */
    private fun findGoogleMapsStepListDestination(rootNode: AccessibilityNodeInfo): String? {
        val allTexts = mutableListOf<String>()
        collectAllTextNodes(rootNode, allTexts, 0)

        // Strategy: Find "Destination will be..." marker and look at items before it
        // Pattern in step list:
        //   [N-3] T: 100 ft                         <- distance (skip)
        //   [N-2] T: The EuroLab                    <- place name (WANT)
        //   [N-1] T: 1425 Wright Blvd               <- address (WANT)
        //   [N]   T: Destination will be on the right  <- marker

        // Matches "Destination will be on the right/left", "Destination is ahead", etc.
        val destinationMarkerPattern = Regex(
            """^Destination\s+(will be|is)\s+""",
            RegexOption.IGNORE_CASE
        )

        // Distance patterns to skip (supports all measurement systems)
        val distancePattern = Regex(
            """^\d+\.?\d*\s*(ft|feet|mi|miles|m|meters|km|kilometers|yd|yards)$""",
            RegexOption.IGNORE_CASE
        )

        // Find the destination marker
        val markerIndex = allTexts.indexOfFirst { destinationMarkerPattern.containsMatchIn(it) }

        if (markerIndex > 0) {
            // Look backwards from marker to find place name and address
            var placeName: String? = null
            var address: String? = null

            // Check up to 3 items before the marker
            for (i in (markerIndex - 1) downTo maxOf(0, markerIndex - 3)) {
                val text = allTexts[i].trim()

                // Skip distance values and "Continue for X" patterns
                if (distancePattern.matches(text)) continue
                if (text.startsWith("Continue for", ignoreCase = true)) continue

                // Check if this looks like an address (has numbers at start)
                val looksLikeAddress = text.matches(Regex("""^\d+\s+.+"""))

                if (looksLikeAddress && address == null) {
                    address = text
                } else if (!looksLikeAddress && placeName == null) {
                    placeName = text
                }

                // Stop if we found both
                if (placeName != null && address != null) break
            }

            // Build the destination string
            val destination = when {
                placeName != null && address != null -> "$placeName ($address)"
                placeName != null -> placeName
                address != null -> address
                else -> null
            }

            if (destination != null) {
                Timber.d("$TAG: Android Auto GMaps destination: '$destination'")
                return destination
            }
        }

        return null
    }

    /**
     * Collect all text AND content descriptions from nodes.
     */
    private fun collectAllContent(
        node: AccessibilityNodeInfo,
        content: MutableList<String>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH || content.size > 100) return

        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { content.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { content.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectAllContent(child, content, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    /**
     * Waze step-list format: Find destination after the last distance instruction.
     * Works for Android Auto and expanded directions view.
     * Pattern: "250 feet" -> "Jewel-Osco" (destination)
     * The destination is the text immediately following the last distance value.
     */
    private fun findWazeStepListDestination(rootNode: AccessibilityNodeInfo): String? {
        val allTexts = mutableListOf<String>()
        collectAllTextNodes(rootNode, allTexts, 0)

        // Distance pattern: "X feet", "X miles", "X.X miles", "0.1 miles", "500 meters", "2 km", etc.
        val distancePattern = Regex("""^\d+\.?\d*\s*(feet|ft|miles|mi|m|km|meters|kilometers)$""", RegexOption.IGNORE_CASE)

        // Noise patterns to filter out
        val noisePatterns = listOf(
            "NEXT TURNS",
            "REPORTS AHEAD",
            "NO REPORTS",
            "WAZE",
            "YOUR ROUTE"
        )

        // Find all distance indices
        val distanceIndices = allTexts.mapIndexedNotNull { index, text ->
            if (distancePattern.matches(text.trim())) index else null
        }

        // Get the text after the last distance (working backwards)
        for (distIndex in distanceIndices.reversed()) {
            if (distIndex + 1 < allTexts.size) {
                val candidate = allTexts[distIndex + 1].trim()

                // Skip if it's noise
                if (noisePatterns.any { candidate.contains(it, ignoreCase = true) }) continue

                // Skip if it's another distance
                if (distancePattern.matches(candidate)) continue

                // Skip if it looks like a time (e.g., "0:25")
                if (candidate.matches(Regex("""^\d+:\d+$"""))) continue

                // Must be valid destination
                if (candidate.isNotBlank() && isValidDestination(candidate)) {
                    Timber.d("$TAG: Waze AA candidate after '${allTexts[distIndex]}': '$candidate'")
                    return candidate
                }
            }
        }

        return null
    }

    /**
     * Waze-specific: Find destination text that appears after "Your location".
     * Waze trip overview shows: T: Your location, T: <destination>
     */
    private fun findWazeDestinationAfterYourLocation(rootNode: AccessibilityNodeInfo): String? {
        val allTexts = mutableListOf<String>()
        collectAllTextNodes(rootNode, allTexts, 0)

        // Find "Your location" and get the text that follows
        val yourLocationIndex = allTexts.indexOfFirst {
            it.equals("Your location", ignoreCase = true)
        }

        if (yourLocationIndex >= 0 && yourLocationIndex < allTexts.size - 1) {
            val destination = allTexts[yourLocationIndex + 1].trim()
            if (destination.isNotBlank() && isValidDestination(destination)) {
                return destination
            }
        }

        return null
    }

    /**
     * Collect all text (not content descriptions) from nodes in order.
     */
    private fun collectAllTextNodes(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH || texts.size > 50) return

        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectAllTextNodes(child, texts, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    /**
     * Check if device is in Android Auto mode.
     */
    private fun isAndroidAutoMode(): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_CAR
    }

    /**
     * Collect all text from node tree for debugging.
     * Only used in debug builds.
     */
    fun debugCollectAllText(node: AccessibilityNodeInfo, maxItems: Int = 50): List<String> {
        val texts = mutableListOf<String>()
        collectTextRecursive(node, texts, maxItems, 0)
        return texts
    }

    private fun collectTextRecursive(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        maxItems: Int,
        depth: Int
    ) {
        if (texts.size >= maxItems || depth > MAX_DEPTH) return

        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add("T: $it") }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add("D: $it") }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectTextRecursive(child, texts, maxItems, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }
}
