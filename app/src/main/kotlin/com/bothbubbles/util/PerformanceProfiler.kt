package com.bothbubbles.util

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Simple performance profiler for identifying bottlenecks.
 * Enable via Settings > Developer Mode > Performance Logging
 */
object PerformanceProfiler {
    var enabled = true // Toggle this to enable/disable logging

    // Track ongoing operations by ID
    private val activeTimers = ConcurrentHashMap<String, Long>()

    // Aggregate stats per operation type
    private val stats = ConcurrentHashMap<String, OperationStats>()

    // Observable log for UI display
    private val _logs = MutableStateFlow<List<PerfLog>>(emptyList())
    val logs: StateFlow<List<PerfLog>> = _logs

    data class PerfLog(
        val timestamp: Long,
        val operation: String,
        val durationMs: Long,
        val details: String? = null
    )

    data class OperationStats(
        val count: AtomicLong = AtomicLong(0),
        val totalMs: AtomicLong = AtomicLong(0),
        val maxMs: AtomicLong = AtomicLong(0)
    ) {
        val avgMs: Long get() = if (count.get() > 0) totalMs.get() / count.get() else 0
    }

    /**
     * Start timing an operation. Returns the operation ID.
     */
    fun start(operation: String, details: String? = null): String {
        if (!enabled) return operation
        val id = "$operation-${System.nanoTime()}"
        activeTimers[id] = System.nanoTime()
        if (details != null) {
            Timber.d("▶ START: $operation ($details)")
        } else {
            Timber.d("▶ START: $operation")
        }
        return id
    }

    /**
     * End timing an operation and log the result.
     */
    fun end(id: String, details: String? = null): Long {
        if (!enabled) return 0
        val startTime = activeTimers.remove(id) ?: return 0
        val durationNs = System.nanoTime() - startTime
        val durationMs = durationNs / 1_000_000

        val operation = id.substringBeforeLast("-")

        // Update stats
        val opStats = stats.getOrPut(operation) { OperationStats() }
        opStats.count.incrementAndGet()
        opStats.totalMs.addAndGet(durationMs)
        opStats.maxMs.updateAndGet { maxOf(it, durationMs) }

        // Log it
        val emoji = when {
            durationMs > 500 -> "🔴" // Slow
            durationMs > 100 -> "🟡" // Medium
            else -> "🟢" // Fast
        }

        val detailStr = details?.let { " ($it)" } ?: ""
        Timber.d("$emoji END: $operation took ${durationMs}ms$detailStr")

        // Add to observable log
        val newLog = PerfLog(System.currentTimeMillis(), operation, durationMs, details)
        _logs.value = (_logs.value + newLog).takeLast(100)

        return durationMs
    }

    /**
     * Inline timing helper for suspend functions.
     */
    suspend inline fun <T> trace(operation: String, details: String? = null, block: () -> T): T {
        if (!enabled) return block()
        val id = start(operation, details)
        return try {
            block()
        } finally {
            end(id)
        }
    }

    /**
     * Inline timing helper for regular functions.
     */
    inline fun <T> traceSync(operation: String, details: String? = null, block: () -> T): T {
        if (!enabled) return block()
        val id = start(operation, details)
        return try {
            block()
        } finally {
            end(id)
        }
    }

    /**
     * Print summary stats to logcat.
     */
    fun printStats() {
        if (stats.isEmpty()) {
            Timber.d("No performance stats collected yet")
            return
        }

        Timber.d("═══════════════════════════════════════════")
        Timber.d("📊 PERFORMANCE SUMMARY")
        Timber.d("═══════════════════════════════════════════")

        stats.entries
            .sortedByDescending { it.value.totalMs.get() }
            .forEach { (op, s) ->
                Timber.d("  $op:")
                Timber.d("    calls: ${s.count.get()}, avg: ${s.avgMs}ms, max: ${s.maxMs.get()}ms, total: ${s.totalMs.get()}ms")
            }

        Timber.d("═══════════════════════════════════════════")
    }

    /**
     * Clear all stats.
     */
    fun reset() {
        activeTimers.clear()
        stats.clear()
        _logs.value = emptyList()
        Timber.d("Performance stats reset")
    }

    /**
     * Get stats as a map for display.
     */
    fun getStatsSnapshot(): Map<String, OperationStats> = stats.toMap()
}
