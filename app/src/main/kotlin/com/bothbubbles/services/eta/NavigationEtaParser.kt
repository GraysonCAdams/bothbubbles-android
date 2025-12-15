package com.bothbubbles.services.eta

import android.app.Notification
import android.service.notification.StatusBarNotification
import android.util.Log
import android.widget.RemoteViews
import java.lang.reflect.Field
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses ETA data from navigation app notifications using a layered approach:
 * 1. Standard Android notification extras (most stable)
 * 2. RemoteViews reflection (fallback for custom layouts)
 */
@Singleton
class NavigationEtaParser @Inject constructor() {

    companion object {
        private const val TAG = "NavigationEtaParser"

        // Regex patterns for parsing ETA and distance
        private val ETA_MINUTES_PATTERN = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
        private val ETA_HOURS_PATTERN = Regex("""(\d+)\s*hr""", RegexOption.IGNORE_CASE)
        private val ETA_TIME_PATTERN = Regex("""(\d{1,2}):(\d{2})\s*(AM|PM)?""", RegexOption.IGNORE_CASE)
        private val DISTANCE_PATTERN = Regex("""(\d+\.?\d*)\s*(mi|km|m|ft)""", RegexOption.IGNORE_CASE)
        private val DESTINATION_PATTERN = Regex("""(?:to|→)\s+(.+)""", RegexOption.IGNORE_CASE)
    }

    /**
     * Parse ETA data from a navigation notification
     */
    fun parse(sbn: StatusBarNotification): ParsedEtaData? {
        val app = NavigationApp.fromPackage(sbn.packageName) ?: return null
        val notification = sbn.notification ?: return null

        Log.d(TAG, "Parsing notification from ${app.name}")

        // Layer 1: Try standard notification extras first
        val layer1Result = parseFromExtras(notification, app)
        if (layer1Result != null && layer1Result.etaMinutes > 0) {
            Log.d(TAG, "Layer 1 success: ${layer1Result.etaMinutes} min")
            return layer1Result
        }

        // Layer 2: Try RemoteViews reflection
        val layer2Result = parseFromRemoteViews(notification, app)
        if (layer2Result != null && layer2Result.etaMinutes > 0) {
            Log.d(TAG, "Layer 2 success: ${layer2Result.etaMinutes} min")
            return layer2Result
        }

        Log.d(TAG, "Failed to parse ETA from notification")
        return null
    }

    /**
     * Layer 1: Parse from standard notification extras
     */
    private fun parseFromExtras(notification: Notification, app: NavigationApp): ParsedEtaData? {
        val extras = notification.extras ?: run {
            Log.d(TAG, "No extras in notification")
            return null
        }

        // Log ALL keys to see what's available
        Log.d(TAG, "=== Notification Extras Keys ===")
        extras.keySet().forEach { key ->
            val value = extras.get(key)
            Log.d(TAG, "  $key = $value (${value?.javaClass?.simpleName})")
        }

        // Common fields used by navigation apps
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()

        Log.d(TAG, "Parsed fields - title: $title, text: $text, subText: $subText, bigText: $bigText, infoText: $infoText")

        // Combine all text sources for parsing
        val combinedText = listOfNotNull(title, text, subText, bigText).joinToString(" ")
        if (combinedText.isBlank()) return null

        val etaMinutes = parseEtaMinutes(combinedText)
        val destination = parseDestination(title ?: "")
        val distance = parseDistance(combinedText)

        if (etaMinutes == null && destination == null) return null

        return ParsedEtaData(
            etaMinutes = etaMinutes ?: 0,
            destination = destination,
            distanceText = distance,
            arrivalTimeText = parseArrivalTime(combinedText),
            navigationApp = app
        )
    }

    /**
     * Layer 2: Parse from RemoteViews using reflection
     * This extracts text from the notification's custom layout
     */
    private fun parseFromRemoteViews(notification: Notification, app: NavigationApp): ParsedEtaData? {
        val remoteViews = notification.contentView ?: notification.bigContentView ?: return null

        val textValues = extractTextFromRemoteViews(remoteViews)
        if (textValues.isEmpty()) return null

        val combinedText = textValues.joinToString(" ")
        Log.d(TAG, "RemoteViews text: $combinedText")

        val etaMinutes = parseEtaMinutes(combinedText)
        val destination = textValues.firstOrNull { it.contains("to ", ignoreCase = true) }
            ?.let { parseDestination(it) }
            ?: textValues.firstOrNull { it.length > 5 && !it.contains(Regex("""\d+\s*(min|hr|mi|km)""")) }
        val distance = parseDistance(combinedText)

        if (etaMinutes == null) return null

        return ParsedEtaData(
            etaMinutes = etaMinutes,
            destination = destination,
            distanceText = distance,
            arrivalTimeText = parseArrivalTime(combinedText),
            navigationApp = app
        )
    }

    /**
     * Extract text values from RemoteViews using reflection.
     *
     * ## How This Works
     * RemoteViews stores UI updates as a list of "actions" in a private field called `mActions`.
     * Each action is a subclass of RemoteViews.Action. The `ReflectionAction` type is used for
     * method calls like `setText()`. We iterate through these actions looking for setText calls
     * and extract the text values.
     *
     * ## Why Reflection?
     * Google Maps and Waze use custom notification layouts. The standard notification extras
     * (EXTRA_TITLE, EXTRA_TEXT) may not contain the ETA data - it might only be in the custom
     * layout. Reflection lets us extract ALL text from the notification regardless of layout.
     *
     * ## Android Source Reference
     * - RemoteViews.java: https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/widget/RemoteViews.java
     * - mActions field: https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/widget/RemoteViews.java;l=567
     * - ReflectionAction inner class: ~line 1500+ in RemoteViews.java
     *
     * ## Stability Notes
     * - This reflection pattern has worked across Android 8-14 (API 26-34)
     * - Field names (`mActions`, `methodName`, `value`) are internal but stable
     * - We catch Throwable to handle weird OEM modifications gracefully
     *
     * IMPORTANT: This uses reflection which can be fragile across Android versions
     * and custom ROMs. We catch Throwable (not just Exception) to handle LinkageError,
     * NoSuchFieldError, and other non-Exception throwables that can occur.
     */
    private fun extractTextFromRemoteViews(remoteViews: RemoteViews): List<String> {
        val texts = mutableListOf<String>()

        try {
            // Access the private mActions field
            val actionsField: Field = RemoteViews::class.java.getDeclaredField("mActions")
            actionsField.isAccessible = true

            @Suppress("UNCHECKED_CAST")
            val actions = actionsField.get(remoteViews) as? ArrayList<Any> ?: return texts

            for (action in actions) {
                try {
                    val actionClassName = action.javaClass.simpleName

                    // Look for ReflectionAction which contains setText calls
                    if (actionClassName == "ReflectionAction") {
                        val methodNameField = action.javaClass.getDeclaredField("methodName")
                        methodNameField.isAccessible = true
                        val methodName = methodNameField.get(action) as? String

                        if (methodName == "setText") {
                            val valueField = action.javaClass.getDeclaredField("value")
                            valueField.isAccessible = true
                            val value = valueField.get(action)

                            if (value is CharSequence) {
                                val text = value.toString().trim()
                                if (text.isNotBlank()) {
                                    texts.add(text)
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    // Field not found in this action type, or other reflection error
                    // Continue to next action - don't crash the entire parse
                }
            }
        } catch (t: Throwable) {
            // Catch all Throwables including LinkageError, NoSuchFieldError, etc.
            // This can happen on certain ROMs or Android versions
            Log.w(TAG, "Failed to extract text from RemoteViews: ${t.javaClass.simpleName}: ${t.message}")
        }

        return texts
    }

    /**
     * Parse ETA in minutes from text.
     * Handles both duration format ("15 min") and arrival time format ("Arrive 17:30")
     */
    private fun parseEtaMinutes(text: String): Int? {
        var totalMinutes = 0
        var found = false

        // Parse hours (duration format)
        ETA_HOURS_PATTERN.find(text)?.let { match ->
            totalMinutes += (match.groupValues[1].toIntOrNull() ?: 0) * 60
            found = true
        }

        // Parse minutes (duration format)
        ETA_MINUTES_PATTERN.find(text)?.let { match ->
            totalMinutes += match.groupValues[1].toIntOrNull() ?: 0
            found = true
        }

        if (found && totalMinutes > 0) return totalMinutes

        // Try parsing arrival time format like "Arrive 17:30" or "17:30"
        val arrivalMinutes = parseArrivalTimeToMinutes(text)
        if (arrivalMinutes != null && arrivalMinutes > 0) {
            Log.d(TAG, "Parsed arrival time to $arrivalMinutes minutes")
            return arrivalMinutes
        }

        return null
    }

    /**
     * Parse arrival time format ("Arrive 17:30" or "Arrive 5:30 PM") and convert to minutes from now
     */
    private fun parseArrivalTimeToMinutes(text: String): Int? {
        // Match "Arrive HH:MM" or just "HH:MM" with optional AM/PM
        val arrivePattern = Regex("""(?:Arrive\s+)?(\d{1,2}):(\d{2})\s*(AM|PM)?""", RegexOption.IGNORE_CASE)
        val match = arrivePattern.find(text) ?: return null

        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val amPm = match.groupValues[3].uppercase().takeIf { it.isNotEmpty() }

        // Convert to 24-hour format
        val hour24 = when {
            amPm == "AM" && hour == 12 -> 0
            amPm == "PM" && hour != 12 -> hour + 12
            amPm == null && hour in 0..23 -> hour  // Already 24-hour format
            else -> hour
        }

        val now = Calendar.getInstance()
        val arrival = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour24)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            // If arrival time is before now, assume it's tomorrow
            if (before(now)) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val diffMs = arrival.timeInMillis - now.timeInMillis
        val diffMinutes = (diffMs / 60000).toInt()

        Log.d(TAG, "Arrival time parsed: $hour24:$minute, diff from now: $diffMinutes min")
        return if (diffMinutes > 0) diffMinutes else null
    }

    /**
     * Parse destination from text
     */
    private fun parseDestination(text: String): String? {
        DESTINATION_PATTERN.find(text)?.let { match ->
            return match.groupValues[1].trim()
        }

        // Google Maps often has "Navigating to X" format
        if (text.startsWith("Navigating to", ignoreCase = true)) {
            return text.substringAfter("to").trim()
        }

        return null
    }

    /**
     * Parse distance from text
     */
    private fun parseDistance(text: String): String? {
        return DISTANCE_PATTERN.find(text)?.value
    }

    /**
     * Parse arrival time from text
     */
    private fun parseArrivalTime(text: String): String? {
        return ETA_TIME_PATTERN.find(text)?.value
    }

}
