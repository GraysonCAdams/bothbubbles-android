package com.bothbubbles.ui.chatcreator.delegates

import timber.log.Timber
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.ui.chatcreator.ContactUiModel
import com.bothbubbles.ui.chatcreator.SelectedRecipient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Delegate responsible for managing selected recipients.
 *
 * This delegate handles:
 * - Adding/removing recipients
 * - Toggling recipient selection
 * - Asynchronous iMessage availability checking for recipients
 * - Recipient list state management
 * - Waiting for pending iMessage checks before group creation
 */
class RecipientSelectionDelegate @Inject constructor(
    private val api: BothBubblesApi,
    private val socketConnection: SocketConnection,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        /** Timeout for waiting for all iMessage checks to complete */
        private const val IMESSAGE_CHECK_TIMEOUT_MS = 5000L
    }

    private val _selectedRecipients = MutableStateFlow<List<SelectedRecipient>>(emptyList())
    val selectedRecipients: StateFlow<List<SelectedRecipient>> = _selectedRecipients.asStateFlow()

    /** Track addresses with pending iMessage availability checks */
    private val pendingChecks = ConcurrentHashMap<String, Boolean>()

    /** Observable state for pending check count (for UI indicator) */
    private val _pendingCheckCount = MutableStateFlow(0)
    val pendingCheckCount: StateFlow<Int> = _pendingCheckCount.asStateFlow()

    /**
     * Toggle a recipient from contact selection (add if not selected, remove if selected)
     */
    fun toggleRecipient(contact: ContactUiModel, scope: CoroutineScope) {
        val currentRecipients = _selectedRecipients.value
        val existingIndex = currentRecipients.indexOfFirst { it.address == contact.address }

        if (existingIndex >= 0) {
            // Already selected - remove it
            removeRecipient(contact.address)
        } else {
            // Determine initial service: default non-iMessage types (RCS, SMS, unknown) to SMS
            val initialService = if (contact.service.equals("iMessage", ignoreCase = true)) {
                "iMessage"
            } else {
                "SMS"  // Default RCS, SMS, MMS, unknown to SMS
            }

            val recipient = SelectedRecipient(
                address = contact.address,
                displayName = contact.displayName,
                service = initialService,
                avatarPath = contact.avatarPath,
                isManualEntry = false
            )
            addRecipientInternal(recipient)

            // If phone number and not already confirmed as iMessage, check availability async
            // This allows the chip to update if iMessage becomes available
            if (contact.address.isPhoneNumber() && initialService != "iMessage") {
                checkAndUpdateRecipientService(contact.address, scope)
            }
        }
    }

    /**
     * Add a recipient from contact selection
     */
    fun addRecipient(contact: ContactUiModel) {
        val recipient = SelectedRecipient(
            address = contact.address,
            displayName = contact.displayName,
            service = contact.service,
            avatarPath = contact.avatarPath,
            isManualEntry = false
        )
        addRecipientInternal(recipient)
    }

    /**
     * Add a recipient from manual address entry (phone number or email)
     */
    fun addManualRecipient(address: String, service: String) {
        val recipient = SelectedRecipient(
            address = address,
            displayName = address,
            service = service,
            avatarPath = null,
            isManualEntry = true
        )
        addRecipientInternal(recipient)
    }

    /**
     * Remove a recipient by address
     */
    fun removeRecipient(address: String) {
        val currentRecipients = _selectedRecipients.value.toMutableList()
        currentRecipients.removeAll { it.address == address }
        _selectedRecipients.value = currentRecipients

        // Cancel any pending check for this address
        if (pendingChecks.remove(address) != null) {
            _pendingCheckCount.value = pendingChecks.size
        }
    }

    /**
     * Remove the last recipient (for backspace handling when input is empty)
     */
    fun removeLastRecipient() {
        val currentRecipients = _selectedRecipients.value.toMutableList()
        if (currentRecipients.isNotEmpty()) {
            currentRecipients.removeAt(currentRecipients.lastIndex)
            _selectedRecipients.value = currentRecipients
        }
    }

    /**
     * Clear all selected recipients
     */
    fun clearRecipients() {
        _selectedRecipients.value = emptyList()
    }

    /**
     * Internal method to add a recipient to the list
     */
    private fun addRecipientInternal(recipient: SelectedRecipient) {
        val currentRecipients = _selectedRecipients.value.toMutableList()
        // Don't add duplicates
        if (currentRecipients.none { it.address == recipient.address }) {
            currentRecipients.add(recipient)
            _selectedRecipients.value = currentRecipients
        }
    }

    /**
     * Asynchronously check iMessage availability for a recipient and update their service.
     * This allows the chip color to update after selection (bidirectional: SMS↔iMessage).
     * Tracks pending checks so we can wait for them before group creation.
     */
    private fun checkAndUpdateRecipientService(address: String, scope: CoroutineScope) {
        // Mark as pending
        pendingChecks[address] = true
        _pendingCheckCount.value = pendingChecks.size

        scope.launch {
            try {
                val smsOnlyMode = settingsDataStore.smsOnlyMode.first()
                val isConnected = socketConnection.connectionState.value == ConnectionState.CONNECTED
                Timber.d("checkAndUpdateRecipientService: address=$address, smsOnlyMode=$smsOnlyMode, isConnected=$isConnected")

                if (!smsOnlyMode && isConnected) {
                    try {
                        val response = api.checkIMessageAvailability(address)
                        Timber.d("checkIMessageAvailability response: code=${response.code()}, data=${response.body()?.data}")
                        if (response.isSuccessful) {
                            val isIMessageAvailable = response.body()?.data?.available == true
                            val newService = if (isIMessageAvailable) "iMessage" else "SMS"
                            Timber.d("Updating recipient $address service to $newService")

                            val updated = _selectedRecipients.value.map { recipient ->
                                if (recipient.address == address) {
                                    recipient.copy(service = newService)
                                } else {
                                    recipient
                                }
                            }
                            _selectedRecipients.value = updated
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to check iMessage availability for $address")
                        // Keep as SMS on failure
                    }
                }
            } finally {
                // Mark check as complete
                pendingChecks.remove(address)
                _pendingCheckCount.value = pendingChecks.size
            }
        }
    }

    /**
     * Wait for all pending iMessage availability checks to complete.
     * Returns true if all checks completed, false if timed out.
     * Use this before creating a group to ensure correct service detection.
     */
    suspend fun awaitPendingChecks(): Boolean {
        if (pendingChecks.isEmpty()) {
            Timber.d("No pending iMessage checks")
            return true
        }

        Timber.d("Waiting for ${pendingChecks.size} pending iMessage checks...")

        val result = withTimeoutOrNull(IMESSAGE_CHECK_TIMEOUT_MS) {
            // Wait for pending check count to reach 0
            _pendingCheckCount.first { it == 0 }
            true
        }

        return if (result == true) {
            Timber.d("All iMessage checks completed")
            true
        } else {
            Timber.w("iMessage checks timed out after ${IMESSAGE_CHECK_TIMEOUT_MS}ms, proceeding with current services")
            // Clear pending checks on timeout
            pendingChecks.clear()
            _pendingCheckCount.value = 0
            false
        }
    }

    /**
     * Check if there are pending iMessage availability checks
     */
    fun hasPendingChecks(): Boolean = pendingChecks.isNotEmpty()

    /**
     * Check if a string looks like a phone number
     */
    private fun String.isPhoneNumber(): Boolean {
        val cleaned = this.replace(Regex("[^0-9+]"), "")
        return cleaned.startsWith("+") || (cleaned.length >= 10 && cleaned.all { it.isDigit() })
    }
}
