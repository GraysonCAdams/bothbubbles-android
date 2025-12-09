package com.bothbubbles.services.developer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Source of an event (Socket.IO or FCM)
 */
enum class EventSource {
    SOCKET,
    FCM
}

/**
 * A logged developer event
 */
data class DeveloperEvent(
    val id: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val source: EventSource,
    val eventType: String,
    val details: String? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
}

/**
 * Singleton service for logging developer events from Socket.IO and FCM.
 * Events are kept in memory and displayed in the developer mode overlay.
 */
@Singleton
class DeveloperEventLog @Inject constructor() {

    companion object {
        private const val MAX_EVENTS = 500
    }

    private val eventIdCounter = AtomicLong(0)
    // Use ArrayDeque for efficient prepend operations (O(1) instead of O(n))
    private val eventsDeque = ArrayDeque<DeveloperEvent>(MAX_EVENTS)
    private val _events = MutableStateFlow<List<DeveloperEvent>>(emptyList())
    val events: StateFlow<List<DeveloperEvent>> = _events.asStateFlow()

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    /**
     * Enable or disable event logging.
     * When disabled, no new events are logged (but existing events are retained).
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
    }

    /**
     * Log a Socket.IO event
     */
    fun logSocketEvent(eventType: String, details: String? = null) {
        if (!_isEnabled.value) return
        addEvent(DeveloperEvent(
            id = eventIdCounter.incrementAndGet(),
            source = EventSource.SOCKET,
            eventType = eventType,
            details = details
        ))
    }

    /**
     * Log an FCM event
     */
    fun logFcmEvent(eventType: String, details: String? = null) {
        if (!_isEnabled.value) return
        addEvent(DeveloperEvent(
            id = eventIdCounter.incrementAndGet(),
            source = EventSource.FCM,
            eventType = eventType,
            details = details
        ))
    }

    /**
     * Log a connection state change
     */
    fun logConnectionChange(source: EventSource, state: String, details: String? = null) {
        if (!_isEnabled.value) return
        addEvent(DeveloperEvent(
            id = eventIdCounter.incrementAndGet(),
            source = source,
            eventType = "CONNECTION: $state",
            details = details
        ))
    }

    @Synchronized
    private fun addEvent(event: DeveloperEvent) {
        // Add to front of deque (O(1) operation)
        eventsDeque.addFirst(event)
        // Remove from back if over capacity (O(1) operation)
        while (eventsDeque.size > MAX_EVENTS) {
            eventsDeque.removeLast()
        }
        // Update StateFlow with new list
        _events.value = eventsDeque.toList()
    }

    /**
     * Clear all logged events
     */
    @Synchronized
    fun clear() {
        eventsDeque.clear()
        _events.value = emptyList()
    }
}
