package com.bothbubbles.seam.hems.autoresponder

import com.bothbubbles.core.model.entity.AutoResponderRuleEntity
import com.bothbubbles.services.context.CallStateProvider
import com.bothbubbles.services.context.DndStateProvider
import com.bothbubbles.services.context.LocationStateProvider
import com.bothbubbles.services.eta.DrivingStateTracker
import timber.log.Timber
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Context information about an incoming message for rule evaluation.
 */
data class MessageContext(
    val senderAddress: String,
    val chatGuid: String,
    val stitchId: String,
    val isFirstFromSender: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Evaluates whether auto-responder rule conditions are met.
 *
 * For a rule to match, ALL non-null conditions must be satisfied.
 * Null conditions are treated as "don't check" (always pass).
 *
 * Condition types:
 * - Source Stitch: Message must come from one of the specified Stitches
 * - First time from sender: Must be first message ever from this sender
 * - Day of week: Current day must be in specified list
 * - Time range: Current time must be within specified range (supports overnight)
 * - DND mode: Device must be in one of the specified DND modes
 * - Driving: Device must be connected to Android Auto
 * - On call: User must be on a phone call
 * - Location: User must be inside/outside specified geofence
 */
@Singleton
class AutoResponderConditionEvaluator @Inject constructor(
    private val dndStateProvider: DndStateProvider,
    private val callStateProvider: CallStateProvider,
    private val locationStateProvider: LocationStateProvider,
    private val drivingStateTracker: DrivingStateTracker
) {
    companion object {
        private const val TAG = "AutoResponderCondition"
    }

    /**
     * Check if all conditions in a rule are met for the given message context.
     *
     * @param rule The rule to evaluate
     * @param context Information about the incoming message
     * @return true if ALL conditions are met (or null = skipped)
     */
    suspend fun allConditionsMet(rule: AutoResponderRuleEntity, context: MessageContext): Boolean {
        // Source Stitch check
        if (!checkSourceStitch(rule.sourceStitchIds, context.stitchId)) {
            Timber.d("$TAG: Rule '${rule.name}' failed source stitch check")
            return false
        }

        // First time from sender check
        if (!checkFirstTimeFromSender(rule.firstTimeFromSender, context.isFirstFromSender)) {
            Timber.d("$TAG: Rule '${rule.name}' failed first-time sender check")
            return false
        }

        // Day of week check
        if (!checkDayOfWeek(rule.daysOfWeek)) {
            Timber.d("$TAG: Rule '${rule.name}' failed day of week check")
            return false
        }

        // Time range check
        if (!checkTimeRange(rule.timeStartMinutes, rule.timeEndMinutes)) {
            Timber.d("$TAG: Rule '${rule.name}' failed time range check")
            return false
        }

        // DND mode check
        if (!checkDndMode(rule.dndModes)) {
            Timber.d("$TAG: Rule '${rule.name}' failed DND mode check")
            return false
        }

        // Driving (Android Auto) check
        if (!checkDriving(rule.requireDriving)) {
            Timber.d("$TAG: Rule '${rule.name}' failed driving check")
            return false
        }

        // On call check
        if (!checkOnCall(rule.requireOnCall)) {
            Timber.d("$TAG: Rule '${rule.name}' failed on-call check")
            return false
        }

        // Location (geofence) check
        if (!checkLocation(rule)) {
            Timber.d("$TAG: Rule '${rule.name}' failed location check")
            return false
        }

        Timber.d("$TAG: Rule '${rule.name}' passed all condition checks")
        return true
    }

    /**
     * Check if message source matches allowed Stitches.
     */
    private fun checkSourceStitch(allowedStitchIds: String?, actualStitchId: String): Boolean {
        if (allowedStitchIds == null) return true // No restriction

        val allowedIds = allowedStitchIds.split(",").map { it.trim().lowercase() }.toSet()
        return actualStitchId.lowercase() in allowedIds
    }

    /**
     * Check if this is the first message from the sender.
     */
    private fun checkFirstTimeFromSender(requireFirstTime: Boolean?, isFirstFromSender: Boolean): Boolean {
        if (requireFirstTime != true) return true // No restriction
        return isFirstFromSender
    }

    /**
     * Check if current day is in the allowed days list.
     */
    private fun checkDayOfWeek(allowedDays: String?): Boolean {
        if (allowedDays == null) return true // No restriction

        val today = LocalDate.now().dayOfWeek
        val todayAbbrev = today.name.take(3).uppercase() // "MON", "TUE", etc.

        return todayAbbrev in allowedDays.split(",").map { it.trim().uppercase() }
    }

    /**
     * Check if current time is within the specified range.
     * Supports overnight ranges (e.g., 22:00 - 07:00).
     */
    private fun checkTimeRange(startMinutes: Int?, endMinutes: Int?): Boolean {
        if (startMinutes == null || endMinutes == null) return true // No restriction

        val nowMinutes = LocalTime.now().hour * 60 + LocalTime.now().minute

        return if (startMinutes <= endMinutes) {
            // Normal range (e.g., 09:00 - 17:00)
            nowMinutes in startMinutes..endMinutes
        } else {
            // Overnight range (e.g., 22:00 - 07:00)
            nowMinutes >= startMinutes || nowMinutes <= endMinutes
        }
    }

    /**
     * Check if current DND mode matches one of the required modes.
     */
    private fun checkDndMode(requiredModes: String?): Boolean {
        if (requiredModes == null) return true // No restriction

        return dndStateProvider.matchesModes(requiredModes)
    }

    /**
     * Check if device is connected to Android Auto.
     */
    private fun checkDriving(requireDriving: Boolean?): Boolean {
        if (requireDriving != true) return true // No restriction

        return drivingStateTracker.isCarConnected.value
    }

    /**
     * Check if user is on a phone call.
     */
    private fun checkOnCall(requireOnCall: Boolean?): Boolean {
        if (requireOnCall != true) return true // No restriction

        return callStateProvider.isOnCall()
    }

    /**
     * Check if user is inside/outside the specified geofence.
     */
    private suspend fun checkLocation(rule: AutoResponderRuleEntity): Boolean {
        val lat = rule.locationLat
        val lng = rule.locationLng
        val inside = rule.locationInside

        // No location restriction if any required field is missing
        if (lat == null || lng == null || inside == null) return true

        val radiusMeters = rule.locationRadiusMeters ?: 100
        val isInside = locationStateProvider.isInsideGeofence(lat, lng, radiusMeters)

        // Match if:
        // - inside=true and user IS inside geofence, OR
        // - inside=false and user is OUTSIDE geofence
        return if (inside) isInside else !isInside
    }
}
