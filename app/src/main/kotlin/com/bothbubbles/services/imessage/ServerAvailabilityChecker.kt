package com.bothbubbles.services.imessage

import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.services.socket.SocketConnection
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encapsulates server API calls for iMessage availability with timeout and retry logic.
 *
 * This class provides a clean interface for checking iMessage availability via the
 * BlueBubbles server API, with proper timeout handling and connection state checking.
 *
 * All API calls use Retrofit (not HttpURLConnection) for consistency with the rest
 * of the app's networking layer.
 */
@Singleton
class ServerAvailabilityChecker @Inject constructor(
    private val api: BothBubblesApi,
    private val socketConnection: SocketConnection
) {

    companion object {
        private const val TAG = "ServerAvailability"
        private const val DEFAULT_TIMEOUT_MS = 3000L
    }

    /**
     * Result of an availability check.
     */
    sealed class CheckResult {
        /** iMessage is available for this address */
        data class Available(val address: String) : CheckResult()

        /** iMessage is NOT available for this address */
        data class NotAvailable(val address: String) : CheckResult()

        /** Request timed out */
        data class Timeout(val address: String) : CheckResult()

        /** Request failed with an error */
        data class Error(val address: String, val cause: Throwable) : CheckResult()

        /** Server is disconnected - cannot check */
        data object ServerDisconnected : CheckResult()
    }

    /**
     * Check iMessage availability for an address.
     *
     * @param address Normalized address to check (phone number or email)
     * @param timeoutMs Maximum time to wait for response (default: 3 seconds)
     * @param retryOnTimeout Whether to retry once on timeout with doubled timeout
     * @return The check result
     */
    suspend fun check(
        address: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        retryOnTimeout: Boolean = false
    ): CheckResult {
        // Pre-check connection state
        if (socketConnection.connectionState.value != ConnectionState.CONNECTED) {
            Timber.tag(TAG).d("Server disconnected, cannot check availability for %s", address)
            return CheckResult.ServerDisconnected
        }

        return try {
            val result = withTimeout(timeoutMs) {
                val response = api.checkIMessageAvailability(address)

                if (response.isSuccessful) {
                    val available = response.body()?.data?.available == true
                    if (available) {
                        Timber.tag(TAG).d("iMessage available for %s", address)
                        CheckResult.Available(address)
                    } else {
                        Timber.tag(TAG).d("iMessage NOT available for %s", address)
                        CheckResult.NotAvailable(address)
                    }
                } else {
                    Timber.tag(TAG).w("API error checking %s: HTTP %d", address, response.code())
                    CheckResult.Error(address, Exception("HTTP ${response.code()}"))
                }
            }
            result
        } catch (e: TimeoutCancellationException) {
            Timber.tag(TAG).w("Timeout checking availability for %s (timeout=%dms)", address, timeoutMs)

            if (retryOnTimeout) {
                // Single retry with longer timeout
                Timber.tag(TAG).d("Retrying with %dms timeout", timeoutMs * 2)
                check(address, timeoutMs * 2, retryOnTimeout = false)
            } else {
                CheckResult.Timeout(address)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error checking availability for %s", address)
            CheckResult.Error(address, e)
        }
    }

    /**
     * Check if the server is currently connected.
     */
    fun isServerConnected(): Boolean {
        return socketConnection.connectionState.value == ConnectionState.CONNECTED
    }
}
