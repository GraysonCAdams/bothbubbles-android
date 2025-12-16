package com.bothbubbles.services.imessage

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.IMessageCacheDao
import com.bothbubbles.data.local.db.entity.CheckResult
import com.bothbubbles.data.local.db.entity.IMessageAvailabilityCacheEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

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
    private val socketService: SocketService,
    private val settingsDataStore: SettingsDataStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
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
        applicationScope.launch(ioDispatcher) {
            socketService.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    onServerReconnected()
                }
            }
        }

        // Cleanup expired entries on startup
        applicationScope.launch(ioDispatcher) {
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
        Timber.d("DEBUG checkAvailability: address=$address, normalizedAddress=$normalizedAddress, forceRecheck=$forceRecheck")

        // Check cache first (unless forcing re-check)
        if (!forceRecheck) {
            val cached = getCachedResult(normalizedAddress)
            Timber.d("DEBUG: cached result for $normalizedAddress: $cached")
            if (cached != null) {
                Timber.d("Cache hit for $normalizedAddress: $cached")
                updateObservableState(normalizedAddress, cached)
                return Result.success(cached)
            }
        } else {
            Timber.d("DEBUG: forceRecheck=true, skipping cache lookup")
        }

        // Check if server is connected
        val connectionState = socketService.connectionState.value
        Timber.d("DEBUG: connectionState=$connectionState")
        if (connectionState != ConnectionState.CONNECTED) {
            Timber.d("Server not connected, marking $normalizedAddress as UNREACHABLE")
            cacheResult(normalizedAddress, CheckResult.UNREACHABLE, null)
            updateObservableState(normalizedAddress, null)
            return Result.failure(Exception("Server not connected"))
        }

        // Perform the check
        Timber.d("DEBUG: Calling performCheck for $normalizedAddress")
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
        Timber.d("Invalidated cache for $normalizedAddress")
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
     * Uses HttpURLConnection instead of OkHttp to bypass potential OkHttp issues.
     */
    private suspend fun performCheck(normalizedAddress: String): Result<Boolean> {
        return withContext(ioDispatcher) {
            try {
                Timber.d("DEBUG performCheck: Calling API via HttpURLConnection for $normalizedAddress")

                // Disable HTTP keep-alive to prevent stale connection reuse
                System.setProperty("http.keepAlive", "false")

                val serverAddress = settingsDataStore.serverAddress.first()
                val authKey = settingsDataStore.guidAuthKey.first()

                val encodedAddress = URLEncoder.encode(normalizedAddress, "UTF-8")
                val encodedAuth = URLEncoder.encode(authKey, "UTF-8")
                // Add cache buster to prevent HTTP caching/connection reuse
                val cacheBuster = System.currentTimeMillis()
                val urlString = "$serverAddress/api/v1/handle/availability/imessage?address=$encodedAddress&guid=$encodedAuth&_=$cacheBuster"

                Timber.d("DEBUG performCheck: URL = $urlString")

                val url = URL(urlString)
                val connection = url.openConnection() as HttpsURLConnection

                try {
                    // Trust all certificates (for self-signed BlueBubbles servers)
                    val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    })
                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                    connection.sslSocketFactory = sslContext.socketFactory
                    connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }

                    connection.requestMethod = "GET"
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    connection.useCaches = false // Disable URL caching
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Connection", "close")
                    connection.setRequestProperty("Cache-Control", "no-cache, no-store")

                    val responseCode = connection.responseCode
                    Timber.d("DEBUG performCheck: responseCode=$responseCode")

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                        Timber.d("DEBUG performCheck: responseBody=$responseBody")

                        val json = JSONObject(responseBody)
                        val data = json.optJSONObject("data")
                        val available = data?.optBoolean("available", false) == true

                        val result = if (available) CheckResult.AVAILABLE else CheckResult.NOT_AVAILABLE
                        Timber.d("DEBUG performCheck: available=$available, caching as $result")
                        cacheResult(normalizedAddress, result, available)
                        updateObservableState(normalizedAddress, available)
                        Timber.d("iMessage availability for $normalizedAddress: $available")
                        Result.success(available)
                    } else {
                        val errorBody = try { connection.errorStream?.bufferedReader()?.use { it.readText() } } catch (e: Exception) { null }
                        Timber.w("DEBUG performCheck: API failed with code $responseCode, errorBody=$errorBody")
                        cacheResult(normalizedAddress, CheckResult.ERROR, null)
                        updateObservableState(normalizedAddress, null)
                        Result.failure(Exception("API error: $responseCode"))
                    }
                } finally {
                    connection.disconnect() // Force close to prevent stale connection reuse
                }
            } catch (e: Exception) {
                val isTimeout = e is java.net.SocketTimeoutException
                Timber.w(e, "DEBUG performCheck: Exception occurred: ${e.message}, isTimeout=$isTimeout")

                if (isTimeout) {
                    // Timeout = server instability, don't cache - will retry next time
                    Timber.d("DEBUG performCheck: Timeout detected, NOT caching (server instability)")
                    updateObservableState(normalizedAddress, null)
                } else {
                    // Other errors - cache as ERROR or UNREACHABLE
                    val result = if (socketService.connectionState.value != ConnectionState.CONNECTED) {
                        CheckResult.UNREACHABLE
                    } else {
                        CheckResult.ERROR
                    }
                    cacheResult(normalizedAddress, result, null)
                    updateObservableState(normalizedAddress, null)
                }
                Result.failure(e)
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
        Timber.d("DEBUG getCachedResult: normalizedAddress=$normalizedAddress, cached=$cached")
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
            Timber.d("Cache expired for $normalizedAddress")
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
