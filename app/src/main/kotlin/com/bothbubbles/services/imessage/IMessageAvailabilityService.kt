package com.bothbubbles.services.imessage

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.IMessageCacheDao
import com.bothbubbles.data.local.db.entity.CheckResult
import com.bothbubbles.data.local.db.entity.IMessageAvailabilityCacheEntity
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
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
    private val serverChecker: ServerAvailabilityChecker,
    private val cacheDao: IMessageCacheDao,
    private val socketService: SocketService,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    // Unique ID for this app session - used to detect cache from previous sessions
    private val sessionId = UUID.randomUUID().toString()

    // Mutex for cache operations
    private val cacheMutex = Mutex()

    // Observable availability state per address (for UI updates)
    private val _availabilityStates = MutableStateFlow<Map<String, Boolean?>>(emptyMap())
    val availabilityStates: StateFlow<Map<String, Boolean?>> = _availabilityStates.asStateFlow()

    // Job references for proper lifecycle management
    private var connectionStateJob: Job? = null
    private var cleanupJob: Job? = null

    init {
        // Listen for server reconnection to re-check unreachable addresses
        connectionStateJob = applicationScope.launch(ioDispatcher) {
            socketService.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    onServerReconnected()
                }
            }
        }

        // Cleanup expired entries on startup
        cleanupJob = applicationScope.launch(ioDispatcher) {
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
        Timber.d("DEBUG checkAvailability: forceRecheck=$forceRecheck")

        // Check cache first (unless forcing re-check)
        if (!forceRecheck) {
            val cached = getCachedResult(normalizedAddress)
            Timber.d("DEBUG: cached result found: $cached")
            if (cached != null) {
                Timber.d("Cache hit: $cached")
                updateObservableState(normalizedAddress, cached)
                return Result.success(cached)
            }
        } else {
            Timber.d("DEBUG: forceRecheck=true, skipping cache lookup")
        }

        // Check if server is connected
        if (!serverChecker.isServerConnected()) {
            Timber.d("Server not connected, marking as UNREACHABLE")
            cacheResult(normalizedAddress, CheckResult.UNREACHABLE, null)
            updateObservableState(normalizedAddress, null)
            return Result.failure(Exception("Server not connected"))
        }

        // Perform the check
        Timber.d("DEBUG: Calling performCheck")
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
        Timber.d("Invalidated cache")
    }

    /**
     * Cleanup method for testing - cancels all active collectors.
     * Should only be called in test scenarios.
     */
    fun cleanup() {
        connectionStateJob?.cancel()
        cleanupJob?.cancel()
        connectionStateJob = null
        cleanupJob = null
    }

    /**
     * Called when server reconnects - re-check all UNREACHABLE addresses.
     */
    private suspend fun onServerReconnected() {
        val unreachable = cacheMutex.withLock {
            cacheDao.getUnreachableAddresses()
        }

        if (unreachable.isEmpty()) return

        Timber.i("Server reconnected, re-checking ${unreachable.size} unreachable addresses")

        unreachable.forEach { entry ->
            applicationScope.launch(ioDispatcher) {
                performCheck(entry.normalizedAddress)
            }
        }
    }

    /**
     * Perform the actual iMessage availability check via API.
     * Uses ServerAvailabilityChecker (Retrofit) for consistent networking.
     */
    private suspend fun performCheck(normalizedAddress: String): Result<Boolean> {
        return withContext(ioDispatcher) {
            Timber.d("performCheck: Calling API via ServerAvailabilityChecker")

            val checkResult = serverChecker.check(normalizedAddress)

            when (checkResult) {
                is ServerAvailabilityChecker.CheckResult.Available -> {
                    cacheResult(normalizedAddress, CheckResult.AVAILABLE, true)
                    updateObservableState(normalizedAddress, true)
                    Timber.d("iMessage availability: true")
                    Result.success(true)
                }
                is ServerAvailabilityChecker.CheckResult.NotAvailable -> {
                    cacheResult(normalizedAddress, CheckResult.NOT_AVAILABLE, false)
                    updateObservableState(normalizedAddress, false)
                    Timber.d("iMessage availability: false")
                    Result.success(false)
                }
                is ServerAvailabilityChecker.CheckResult.Timeout -> {
                    // Timeout = server instability, don't cache - will retry next time
                    Timber.d("Timeout detected, NOT caching (server instability)")
                    updateObservableState(normalizedAddress, null)
                    Result.failure(Exception("Request timed out"))
                }
                is ServerAvailabilityChecker.CheckResult.Error -> {
                    cacheResult(normalizedAddress, CheckResult.ERROR, null)
                    updateObservableState(normalizedAddress, null)
                    Result.failure(checkResult.cause)
                }
                is ServerAvailabilityChecker.CheckResult.ServerDisconnected -> {
                    cacheResult(normalizedAddress, CheckResult.UNREACHABLE, null)
                    updateObservableState(normalizedAddress, null)
                    Result.failure(Exception("Server disconnected"))
                }
            }
        }
    }

    /**
     * Get cached result if valid (not expired, not UNREACHABLE).
     */
    private suspend fun getCachedResult(normalizedAddress: String): Boolean? {
        val cached = cacheMutex.withLock {
            cacheDao.getCache(normalizedAddress)
        }
        Timber.d("DEBUG getCachedResult: cached=$cached")
        if (cached == null) {
            Timber.d("DEBUG getCachedResult: No cache entry found")
            return null
        }

        Timber.d("DEBUG getCachedResult: checkResult=${cached.checkResult}, isIMessageAvailable=${cached.isIMessageAvailable}, expiresAt=${cached.expiresAt}, sessionId=${cached.sessionId}")

        // UNREACHABLE entries are not valid cache hits
        if (cached.checkResult == CheckResult.UNREACHABLE.name) {
            Timber.d("DEBUG getCachedResult: Cache entry is UNREACHABLE, returning null")
            return null
        }

        // Check expiration
        if (cached.isExpired()) {
            Timber.d("Cache expired")
            cacheMutex.withLock {
                cacheDao.delete(normalizedAddress)
            }
            return null
        }

        Timber.d("DEBUG getCachedResult: Returning cached value ${cached.isIMessageAvailable}")
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
        Timber.d("Cleaned up expired cache entries")
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
