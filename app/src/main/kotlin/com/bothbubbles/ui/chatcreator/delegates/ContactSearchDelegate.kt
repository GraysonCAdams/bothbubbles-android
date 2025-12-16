package com.bothbubbles.ui.chatcreator.delegates

import timber.log.Timber
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.ui.chatcreator.ManualAddressEntry
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate responsible for search functionality and address validation.
 *
 * This delegate handles:
 * - Search query changes
 * - Address validation (phone number and email detection)
 * - iMessage availability checking
 */
class ContactSearchDelegate @Inject constructor(
    private val api: BothBubblesApi,
    private val socketConnection: SocketConnection,
    private val settingsDataStore: SettingsDataStore,
    private val handleRepository: HandleRepository
) {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var iMessageCheckJob: Job? = null

    /**
     * Data class for address validation results
     */
    data class AddressValidationResult(
        val isValidating: Boolean,
        val manualEntry: ManualAddressEntry?
    )

    /**
     * Update the search query
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Observe search query and detect valid addresses (phone numbers or emails).
     * Returns a Flow that emits AddressValidationResult whenever a valid address is detected.
     */
    fun observeSearchQueryForAddressDetection(
        scope: CoroutineScope
    ): Flow<AddressValidationResult> = _searchQuery
        .debounce(500) // Wait 500ms after user stops typing
        .distinctUntilChanged()
        .map { query ->
            checkIfValidAddress(query)
        }

    /**
     * Check if the query is a valid address and determine iMessage availability
     */
    private suspend fun checkIfValidAddress(query: String): AddressValidationResult {
        val trimmedQuery = query.trim()

        // Check if the query looks like a phone number or email
        if (!trimmedQuery.isPhoneNumber() && !trimmedQuery.isEmail()) {
            return AddressValidationResult(
                isValidating = false,
                manualEntry = null
            )
        }

        // Check if we're in SMS-only mode
        val smsOnlyMode = settingsDataStore.smsOnlyMode.first()
        val isConnected = socketConnection.connectionState.value == ConnectionState.CONNECTED

        var isIMessageAvailable = false
        var service = "SMS"

        // Only check iMessage availability if not in SMS-only mode and server is connected
        if (!smsOnlyMode && isConnected && trimmedQuery.isPhoneNumber()) {
            try {
                val response = api.checkIMessageAvailability(trimmedQuery)
                if (response.isSuccessful && response.body()?.data?.available == true) {
                    isIMessageAvailable = true
                    service = "iMessage"
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to check iMessage availability")
            }
        } else if (!smsOnlyMode && isConnected && trimmedQuery.isEmail()) {
            // Emails are always iMessage
            isIMessageAvailable = true
            service = "iMessage"
        }

        return AddressValidationResult(
            isValidating = false,
            manualEntry = ManualAddressEntry(
                address = trimmedQuery,
                isIMessageAvailable = isIMessageAvailable,
                service = service
            )
        )
    }

    /**
     * Check if a string looks like a phone number
     */
    private fun String.isPhoneNumber(): Boolean {
        val cleaned = this.replace(Regex("[^0-9+]"), "")
        return cleaned.startsWith("+") || (cleaned.length >= 10 && cleaned.all { it.isDigit() })
    }

    /**
     * Check if a string looks like an email address
     */
    private fun String.isEmail(): Boolean {
        return this.contains("@") && this.contains(".") && this.length > 5
    }

    /**
     * Get the service for a manually entered address (for immediate use, before async check completes)
     */
    suspend fun getServiceForManualAddress(address: String): String {
        val trimmedAddress = address.trim()

        when {
            trimmedAddress.isEmail() -> {
                // Emails are typically iMessage
                return "iMessage"
            }
            trimmedAddress.isPhoneNumber() -> {
                // Check cached handle for last known service
                val cachedHandle = handleRepository.getHandleByAddressAny(trimmedAddress)
                    ?: handleRepository.getHandleByAddressAny(
                        PhoneAndCodeParsingUtils.normalizePhoneNumber(trimmedAddress)
                    )
                return cachedHandle?.service ?: "SMS"
            }
            else -> {
                return "SMS"
            }
        }
    }

    /**
     * Check if the current search query is a valid address
     */
    fun isCurrentQueryValidAddress(): Boolean {
        val query = _searchQuery.value.trim()
        return query.isPhoneNumber() || query.isEmail()
    }
}
