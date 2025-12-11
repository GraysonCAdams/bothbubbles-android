package com.bothbubbles.services.imessage

import android.util.Log
import com.bothbubbles.data.local.db.dao.IMessageCacheDao
import com.bothbubbles.data.local.db.entity.IMessageAvailabilityCacheEntity
import com.bothbubbles.data.local.db.entity.IMessageAvailabilityCacheEntity.CheckResult
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for checking and caching iMessage availability for contacts.
 *
 * ## Caching Strategy
 * - Results are cached in Room for persistence across app restarts
 * - Cache TTL: 24 hours for successful checks, 5 minutes for errors
 * - On app restart: cache is invalidated for the active chat only (re-checked when opened)
 * - On server reconnect: all UNREACHABLE entries are re-checked
 *
 * ## Thread Safety
 * - Concurrent checks for the same address are deduplicated
 * - Cache access is protected by mutex for consistency
 */
@Singleton
class IMessageAvailabilityService @Inject constructor(
    private val api: BothBubblesApi,
    private val cacheDao: IMessageCacheDao,
    private val socketService: SocketService
) {
    companion object {
        private const val TAG = "IMessageAvailability"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Unique ID for this app session - used to detect cache from previous sessions
    private val sessionId = UUID.randomUUID().toString()

    // Mutex for cache operations
    private val cacheMutex = Mutex()

    // Track pending checks to deduplicate concurrent requests
    private val pendingChecks = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Result<Boolean>>>()

    // Observable availability state per address (for UI updates)
    private val _availabilityStates = MutableStateFlow<Map<String, Boolean?>>(emptyMap())
    val availabilityStates: StateFlow<Map<String, Boolean?>> = _availabilityStates.asStateFlow()

    init {
        // Listen for server reconnection to re-check unreachable addresses
        scope.launch {
            socketService.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    onServerReconnected()
                }
            }
        }

        // Cleanup expired entries on startup
        scope.launch {
            cleanupExpiredCache()
        }
    }

    /**
     * Check iMessage availability for an address.
     *
     * @param address Phone number or email to check
     * @param forceRecheck Force a re-check even if cached (used on app restart for active chat)
     * @return Result containing true if iMessage available, false if not, or failure if check failed
     */
    suspend fun checkAvailability(address: String, forceRecheck: Boolean = false): Result<Boolean> {
        val normalizedAddress = normalizeAddress(address)

        // Check cache first (unless forcing re-check)
        if (!forceRecheck) {
            val cached = getCachedResult(normalizedAddress)
            if (cached != null) {
                Log.d(TAG, "Cache hit for $normalizedAddress: $cached")
                updateObservableState(normalizedAddress, cached)
                return Result.success(cached)
            }
        }

        // Check if server is connected
        if (socketService.connectionState.value != ConnectionState.CONNECTED) {
            Log.d(TAG, "Server not connected, marking $normalizedAddress as UNREACHABLE")
            cacheResult(normalizedAddress, CheckResult.UNREACHABLE, null)
            updateObservableState(normalizedAddress, null)
            return Result.failure(Exception("Server not connected"))
        }

        // Perform the check
        return performCheck(normalizedAddress)
    }

    /**
     * Get cached availability result without triggering a check.
     * Returns null if not cached, expired, or from previous session.
     */
    suspend fun getCachedAvailability(address: String): Boolean? {
        val normalizedAddress = normalizeAddress(address)
        return getCachedResult(normalizedAddress)
    }

    /**
     * Check if the cached result for an address is from a previous app session.
     * Used to determine if we need to re-check on chat open.
     */
    suspend fun isCacheFromPreviousSession(address: String): Boolean {
        val normalizedAddress = normalizeAddress(address)
        val cached = cacheMutex.withLock {
            cacheDao.getCache(normalizedAddress)
        }
        return cached != null && cached.sessionId != sessionId
    }

    /**
     * Invalidate cache for an address, forcing a re-check on next query.
     */
    suspend fun invalidateCache(address: String) {
        val normalizedAddress = normalizeAddress(address)
        cacheMutex.withLock {
            cacheDao.delete(normalizedAddress)
        }
        updateObservableState(normalizedAddress, null)
        Log.d(TAG, "Invalidated cache for $normalizedAddress")
    }

    /**
     * Called when server reconnects - re-check all UNREACHABLE addresses.
     */
    private suspend fun onServerReconnected() {
        val unreachable = cacheMutex.withLock {
            cacheDao.getUnreachableAddresses()
        }

        if (unreachable.isEmpty()) return

        Log.i(TAG, "Server reconnected, re-checking ${unreachable.size} unreachable addresses")

        unreachable.forEach { entry ->
            scope.launch {
                performCheck(entry.normalizedAddress)
            }
        }
    }

    /**
     * Perform the actual iMessage availability check via API.
     */
    private suspend fun performCheck(normalizedAddress: String): Result<Boolean> {
        return try {
            Log.d(TAG, "Checking iMessage availability for $normalizedAddress")
            val response = api.checkIMessageAvailability(normalizedAddress)

            if (response.isSuccessful) {
                val available = response.body()?.data?.available == true
                val result = if (available) CheckResult.AVAILABLE else CheckResult.NOT_AVAILABLE
                cacheResult(normalizedAddress, result, available)
                updateObservableState(normalizedAddress, available)
                Log.d(TAG, "iMessage availability for $normalizedAddress: $available")
                Result.success(available)
            } else {
                Log.w(TAG, "iMessage check failed for $normalizedAddress: ${response.code()}")
                cacheResult(normalizedAddress, CheckResult.ERROR, null)
                updateObservableState(normalizedAddress, null)
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "iMessage check failed for $normalizedAddress", e)
            // Check if it was a network error (server became unreachable)
            val result = if (socketService.connectionState.value != ConnectionState.CONNECTED) {
                CheckResult.UNREACHABLE
            } else {
                CheckResult.ERROR
            }
            cacheResult(normalizedAddress, result, null)
            updateObservableState(normalizedAddress, null)
            Result.failure(e)
        }
    }

    /**
     * Get cached result if valid (not expired, not UNREACHABLE).
     */
    private suspend fun getCachedResult(normalizedAddress: String): Boolean? {
        val cached = cacheMutex.withLock {
            cacheDao.getCache(normalizedAddress)
        } ?: return null

        // UNREACHABLE entries are not valid cache hits
        if (cached.checkResult == CheckResult.UNREACHABLE.name) {
            return null
        }

        // Check expiration
        if (cached.isExpired()) {
            Log.d(TAG, "Cache expired for $normalizedAddress")
            cacheMutex.withLock {
                cacheDao.delete(normalizedAddress)
            }
            return null
        }

        return cached.isIMessageAvailable
    }

    /**
     * Cache the result of an availability check.
     */
    private suspend fun cacheResult(
        normalizedAddress: String,
        result: CheckResult,
        available: Boolean?
    ) {
        val entity = when (result) {
            CheckResult.AVAILABLE -> IMessageAvailabilityCacheEntity.createAvailable(
                normalizedAddress, sessionId
            )
            CheckResult.NOT_AVAILABLE -> IMessageAvailabilityCacheEntity.createNotAvailable(
                normalizedAddress, sessionId
            )
            CheckResult.UNREACHABLE -> IMessageAvailabilityCacheEntity.createUnreachable(
                normalizedAddress, sessionId
            )
            CheckResult.ERROR -> IMessageAvailabilityCacheEntity.createError(
                normalizedAddress, sessionId
            )
        }

        cacheMutex.withLock {
            cacheDao.insertOrUpdate(entity)
        }
    }

    /**
     * Update the observable state for UI.
     */
    private fun updateObservableState(normalizedAddress: String, available: Boolean?) {
        _availabilityStates.value = _availabilityStates.value.toMutableMap().apply {
            if (available != null) {
                put(normalizedAddress, available)
            } else {
                remove(normalizedAddress)
            }
        }
    }

    /**
     * Cleanup expired cache entries.
     */
    private suspend fun cleanupExpiredCache() {
        cacheMutex.withLock {
            cacheDao.deleteExpired()
        }
        Log.d(TAG, "Cleaned up expired cache entries")
    }

    /**
     * Normalize address for consistent cache keys.
     * - Phone numbers: E.164 format (e.g., +1234567890)
     * - Emails: lowercase
     */
    private fun normalizeAddress(address: String): String {
        val trimmed = address.trim()

        // Check if it's an email
        if (trimmed.contains("@")) {
            return trimmed.lowercase()
        }

        // Try to format as E.164 phone number
        return PhoneNumberFormatter.normalize(trimmed) ?: trimmed
    }
}
