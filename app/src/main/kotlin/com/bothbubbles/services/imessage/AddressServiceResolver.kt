package com.bothbubbles.services.imessage

import com.bothbubbles.data.local.db.dao.IMessageCacheDao
import com.bothbubbles.data.local.db.entity.CheckResult
import com.bothbubbles.data.local.db.entity.IMessageAvailabilityCacheEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.util.parsing.AddressValidator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for resolving an address to a messaging service (iMessage or SMS).
 *
 * This class implements a 5-tier priority hierarchy for determining the service:
 *
 * ## Tier 1: Immediate Returns (No network, no cache lookup)
 * - Format validation (invalid addresses)
 * - Email rule (emails always route to iMessage)
 * - SMS-only mode setting
 *
 * ## Tier 2: Local Lookups (Fast, no network)
 * - Fresh cache hit (within TTL)
 * - Local handle lookup (if handle.isIMessage = true)
 *
 * ## Tier 3: Server Check (Network required)
 * - Server API check with timeout
 *
 * ## Tier 4: Fallback Strategies (When server unavailable)
 * - Stale cache (expired but still useful as hint)
 * - Activity history (most recent chat with this address)
 *
 * ## Tier 5: Default
 * - SMS for phone numbers (safest default)
 *
 * @see ServiceResolution for result types
 * @see ResolutionOptions for customization
 */
@Singleton
class AddressServiceResolver @Inject constructor(
    private val serverChecker: ServerAvailabilityChecker,
    private val cacheDao: IMessageCacheDao,
    private val handleRepository: HandleRepository,
    private val chatRepository: ChatRepository,
    private val settingsDataStore: SettingsDataStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "AddressResolver"
    }

    // Unique ID for this app session - used to detect cache from previous sessions
    private val sessionId = UUID.randomUUID().toString()

    // Mutex for cache operations
    private val cacheMutex = Mutex()

    // Job for connection state observation
    private var connectionStateJob: Job? = null

    init {
        // Listen for server reconnection to re-check unreachable addresses
        connectionStateJob = applicationScope.launch(ioDispatcher) {
            // Re-check unreachable entries when server reconnects
            // This is handled by observing connection state changes
        }
    }

    /**
     * Resolve an address to a messaging service.
     *
     * @param address The raw address (phone number or email)
     * @param options Resolution options (timeout, cache behavior, etc.)
     * @return The service resolution result
     */
    suspend fun resolveService(
        address: String,
        options: ResolutionOptions = ResolutionOptions()
    ): ServiceResolution = withContext(ioDispatcher) {
        // ═══════════════════════════════════════════════════════════════
        // TIER 1: IMMEDIATE RETURNS (No network, no cache lookup)
        // ═══════════════════════════════════════════════════════════════

        // 1.1 Format validation
        val addressType = AddressValidator.validate(address)
        if (addressType == AddressValidator.AddressType.Invalid) {
            Timber.tag(TAG).d("Invalid address format: %s", address)
            return@withContext ServiceResolution.Invalid("Invalid address format")
        }

        // 1.2 Email → Always iMessage (no lookup needed)
        if (addressType == AddressValidator.AddressType.Email) {
            Timber.tag(TAG).d("Email address, returning iMessage: %s", address)
            return@withContext ServiceResolution.Resolved(
                service = MessagingService.IMESSAGE,
                source = ResolutionSource.EMAIL_RULE,
                confidence = ResolutionConfidence.HIGH
            )
        }

        // 1.3 SMS-only mode → Always SMS
        if (settingsDataStore.smsOnlyMode.first()) {
            Timber.tag(TAG).d("SMS-only mode enabled, returning SMS")
            return@withContext ServiceResolution.Resolved(
                service = MessagingService.SMS,
                source = ResolutionSource.SMS_ONLY_MODE,
                confidence = ResolutionConfidence.HIGH
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // TIER 2: LOCAL LOOKUPS (Fast, no network)
        // ═══════════════════════════════════════════════════════════════

        val normalized = AddressValidator.normalize(address)

        // 2.1 Fresh cache hit (within TTL, not from previous session if forceRecheck)
        if (!options.forceRecheck) {
            val cached = getFreshCacheResult(normalized)
            if (cached != null) {
                Timber.tag(TAG).d("Fresh cache hit for %s: %s", normalized, cached)
                return@withContext ServiceResolution.Resolved(
                    service = if (cached) MessagingService.IMESSAGE else MessagingService.SMS,
                    source = ResolutionSource.CACHE,
                    confidence = ResolutionConfidence.MEDIUM
                )
            }
        }

        // 2.2 Local handle with known iMessage status
        val handle = handleRepository.getHandleByAddressAny(normalized)
        if (handle?.isIMessage == true) {
            Timber.tag(TAG).d("Local handle is iMessage: %s", normalized)
            return@withContext ServiceResolution.Resolved(
                service = MessagingService.IMESSAGE,
                source = ResolutionSource.LOCAL_HANDLE,
                confidence = ResolutionConfidence.MEDIUM
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // TIER 3: SERVER CHECK (Network required)
        // ═══════════════════════════════════════════════════════════════

        // 3.1 Check server connection
        if (!serverChecker.isServerConnected()) {
            Timber.tag(TAG).d("Server disconnected, marking as unreachable")
            cacheUnreachable(normalized)
            // Fall through to Tier 4
        } else {
            // 3.2 API check with timeout
            val apiResult = serverChecker.check(
                address = normalized,
                timeoutMs = options.timeoutMs,
                retryOnTimeout = false
            )

            when (apiResult) {
                is ServerAvailabilityChecker.CheckResult.Available -> {
                    cacheResult(normalized, CheckResult.AVAILABLE, true)
                    return@withContext ServiceResolution.Resolved(
                        service = MessagingService.IMESSAGE,
                        source = ResolutionSource.SERVER_API,
                        confidence = ResolutionConfidence.HIGH
                    )
                }
                is ServerAvailabilityChecker.CheckResult.NotAvailable -> {
                    cacheResult(normalized, CheckResult.NOT_AVAILABLE, false)
                    return@withContext ServiceResolution.Resolved(
                        service = MessagingService.SMS,
                        source = ResolutionSource.SERVER_API,
                        confidence = ResolutionConfidence.HIGH
                    )
                }
                is ServerAvailabilityChecker.CheckResult.Timeout -> {
                    // Don't cache timeouts (server instability) - fall through to Tier 4
                    Timber.tag(TAG).d("Server timeout for %s, using fallback", normalized)
                }
                is ServerAvailabilityChecker.CheckResult.Error -> {
                    // Cache as error (short TTL) - fall through to Tier 4
                    cacheResult(normalized, CheckResult.ERROR, null)
                    Timber.tag(TAG).d("Server error for %s, using fallback", normalized)
                }
                is ServerAvailabilityChecker.CheckResult.ServerDisconnected -> {
                    cacheUnreachable(normalized)
                    Timber.tag(TAG).d("Server disconnected during check for %s", normalized)
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // TIER 4: FALLBACK STRATEGIES (When server unavailable)
        // ═══════════════════════════════════════════════════════════════

        // 4.1 Stale cache (expired but still useful as hint)
        if (options.allowStaleCache) {
            val staleCache = getStaleCacheResult(normalized)
            if (staleCache != null) {
                Timber.tag(TAG).d("Using stale cache for %s: %s", normalized, staleCache)
                return@withContext ServiceResolution.Resolved(
                    service = if (staleCache) MessagingService.IMESSAGE else MessagingService.SMS,
                    source = ResolutionSource.STALE_CACHE,
                    confidence = ResolutionConfidence.LOW
                )
            }
        }

        // 4.2 Activity history (most recent chat with messages)
        val activityService = chatRepository.getServiceFromMostRecentActiveChat(normalized)
        if (activityService != null) {
            Timber.tag(TAG).d("Using activity history for %s: %s", normalized, activityService)
            return@withContext ServiceResolution.Resolved(
                service = if (activityService.equals("iMessage", ignoreCase = true))
                    MessagingService.IMESSAGE else MessagingService.SMS,
                source = ResolutionSource.ACTIVITY_HISTORY,
                confidence = ResolutionConfidence.LOW
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // TIER 5: DEFAULT (Last resort)
        // ═══════════════════════════════════════════════════════════════

        // Phone numbers default to SMS (safer default)
        Timber.tag(TAG).d("No data for %s, defaulting to SMS", normalized)
        return@withContext ServiceResolution.Resolved(
            service = MessagingService.SMS,
            source = ResolutionSource.DEFAULT,
            confidence = ResolutionConfidence.LOW
        )
    }

    /**
     * Check if the cached result for an address is from a previous app session.
     * Used to determine if we need to re-check on chat open.
     */
    suspend fun isCacheFromPreviousSession(address: String): Boolean {
        val normalized = AddressValidator.normalize(address)
        val cached = cacheMutex.withLock {
            cacheDao.getCache(normalized)
        }
        return cached != null && cached.sessionId != sessionId
    }

    /**
     * Invalidate cache for an address, forcing a re-check on next query.
     */
    suspend fun invalidateCache(address: String) {
        val normalized = AddressValidator.normalize(address)
        cacheMutex.withLock {
            cacheDao.delete(normalized)
        }
        Timber.tag(TAG).d("Invalidated cache for %s", normalized)
    }

    /**
     * Get all addresses marked as unreachable (for re-checking on reconnect).
     */
    suspend fun getUnreachableAddresses(): List<String> {
        return cacheMutex.withLock {
            cacheDao.getUnreachableAddresses().map { it.normalizedAddress }
        }
    }

    /**
     * Re-check all unreachable addresses. Called when server reconnects.
     */
    suspend fun recheckUnreachableAddresses() {
        val unreachable = getUnreachableAddresses()
        if (unreachable.isEmpty()) return

        Timber.tag(TAG).i("Re-checking ${unreachable.size} unreachable addresses")

        unreachable.forEach { address ->
            applicationScope.launch(ioDispatcher) {
                resolveService(address, ResolutionOptions(forceRecheck = true))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Private Cache Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get a fresh (non-expired) cache result.
     */
    private suspend fun getFreshCacheResult(normalizedAddress: String): Boolean? {
        val cached = cacheMutex.withLock {
            cacheDao.getCache(normalizedAddress)
        }

        if (cached == null) return null

        // UNREACHABLE entries are not valid cache hits
        if (cached.checkResult == CheckResult.UNREACHABLE.name) return null

        // Check expiration
        if (cached.isExpired()) {
            // Don't delete - keep for stale cache fallback
            return null
        }

        return cached.isIMessageAvailable
    }

    /**
     * Get a stale (expired) cache result for fallback.
     */
    private suspend fun getStaleCacheResult(normalizedAddress: String): Boolean? {
        val cached = cacheMutex.withLock {
            cacheDao.getCache(normalizedAddress)
        }

        if (cached == null) return null

        // UNREACHABLE entries are not useful
        if (cached.checkResult == CheckResult.UNREACHABLE.name) return null

        // ERROR entries are not useful as fallback
        if (cached.checkResult == CheckResult.ERROR.name) return null

        // Return even if expired (for fallback)
        return cached.isIMessageAvailable
    }

    /**
     * Cache a successful availability check result.
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
     * Mark an address as unreachable (server disconnected).
     */
    private suspend fun cacheUnreachable(normalizedAddress: String) {
        val entity = IMessageAvailabilityCacheEntity.createUnreachable(normalizedAddress, sessionId)
        cacheMutex.withLock {
            cacheDao.insertOrUpdate(entity)
        }
    }

    /**
     * Cleanup method for testing.
     */
    fun cleanup() {
        connectionStateJob?.cancel()
        connectionStateJob = null
    }
}
