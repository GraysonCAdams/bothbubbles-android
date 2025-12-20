package com.bothbubbles.services.eta

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * AccessibilityService that monitors Google Maps and Waze window content
 * to extract destination information for ETA sharing.
 *
 * This service complements [NavigationListenerService] (NotificationListenerService) by
 * providing direct access to the navigation UI, which enables reliable destination extraction
 * that isn't possible from notifications alone.
 *
 * ## Privacy
 * - Only monitors Google Maps and Waze (configured in accessibility_service_config.xml)
 * - Only extracts destination text
 * - Does not log or store other UI content
 *
 * ## Architecture
 * - Works alongside NavigationListenerService
 * - Delegates destination extraction to [DestinationExtractor]
 * - Reports detected destinations to [EtaSharingManager]
 *
 * ## Modes Supported
 * - Full map navigation view (destination in header/bottom card)
 * - Android Auto mode on phone (step list with destination in header)
 */
@AndroidEntryPoint
class NavigationAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NavAccessibilitySvc"

        // Debounce interval to avoid processing rapid events
        private const val MIN_EVENT_INTERVAL_MS = 500L

        // Package names (also configured in XML, but useful for logging)
        private const val PACKAGE_GOOGLE_MAPS = "com.google.android.apps.maps"
        private const val PACKAGE_WAZE = "com.waze"
    }

    @Inject
    lateinit var destinationExtractor: DestinationExtractor

    @Inject
    lateinit var etaSharingManager: EtaSharingManager

    private var lastEventTime = 0L
    private var lastDetectedDestination: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.d("$TAG: Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Package filter (should already be filtered by XML config, but double-check)
        val packageName = event.packageName?.toString() ?: return
        if (packageName != PACKAGE_GOOGLE_MAPS && packageName != PACKAGE_WAZE) return

        // Event type filter
        val eventTypeName = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
            else -> return
        }

        // Debounce rapid events
        val now = System.currentTimeMillis()
        if (now - lastEventTime < MIN_EVENT_INTERVAL_MS) return
        lastEventTime = now

        Timber.d("$TAG: Processing $eventTypeName from $packageName")
        processNavigationWindow(packageName)
    }

    override fun onInterrupt() {
        Timber.d("$TAG: Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("$TAG: Service destroyed")
    }

    /**
     * Process the navigation app window to extract destination.
     */
    private fun processNavigationWindow(packageName: String) {
        val app = NavigationApp.fromPackage(packageName) ?: return

        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to get root node")
            return
        }

        if (rootNode == null) {
            Timber.d("$TAG: Root node is null")
            return
        }

        try {
            val destination = destinationExtractor.extractDestination(rootNode, app)

            if (destination != null) {
                // Only report if destination changed (avoid duplicate reports)
                if (destination.destination != lastDetectedDestination) {
                    lastDetectedDestination = destination.destination
                    Timber.d("$TAG: Destination detected: ${destination.destination} (confidence: ${destination.confidence})")
                    etaSharingManager.onDestinationDetected(destination)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error extracting destination")
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Reset last detected destination when navigation stops.
     * Called when the service detects navigation app is no longer active.
     */
    fun resetDestinationTracking() {
        lastDetectedDestination = null
    }
}
