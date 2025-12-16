package com.bothbubbles.ui.chatcreator.delegates

import android.util.Log
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.ui.chatcreator.ContactUiModel
import com.bothbubbles.ui.chatcreator.SelectedRecipient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate responsible for managing selected recipients.
 *
 * This delegate handles:
 * - Adding/removing recipients
 * - Toggling recipient selection
 * - Asynchronous iMessage availability checking for recipients
 * - Recipient list state management
 */
class RecipientSelectionDelegate @Inject constructor(
    private val api: BothBubblesApi,
    private val socketConnection: SocketConnection,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        private const val TAG = "RecipientSelectionDelegate"
    }

    private val _selectedRecipients = MutableStateFlow<List<SelectedRecipient>>(emptyList())
    val selectedRecipients: StateFlow<List<SelectedRecipient>> = _selectedRecipients.asStateFlow()

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
            // Not selected - add it with cached service immediately
            val recipient = SelectedRecipient(
                address = contact.address,
                displayName = contact.displayName,
                service = contact.service,
                avatarPath = contact.avatarPath,
                isManualEntry = false
            )
            addRecipientInternal(recipient)

            // If phone number and not already iMessage, check availability async
            // This allows the chip to update color if iMessage becomes available
            if (contact.address.isPhoneNumber() && !contact.service.equals("iMessage", ignoreCase = true)) {
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
     */
    private fun checkAndUpdateRecipientService(address: String, scope: CoroutineScope) {
        scope.launch {
            val smsOnlyMode = settingsDataStore.smsOnlyMode.first()
            val isConnected = socketConnection.connectionState.value == ConnectionState.CONNECTED
            Log.d(TAG, "checkAndUpdateRecipientService: address=$address, smsOnlyMode=$smsOnlyMode, isConnected=$isConnected")

            if (!smsOnlyMode && isConnected) {
                try {
                    val response = api.checkIMessageAvailability(address)
                    Log.d(TAG, "checkIMessageAvailability response: code=${response.code()}, data=${response.body()?.data}")
                    if (response.isSuccessful) {
                        val isIMessageAvailable = response.body()?.data?.available == true
                        val newService = if (isIMessageAvailable) "iMessage" else "SMS"
                        Log.d(TAG, "Updating recipient $address service to $newService")

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
                    Log.e(TAG, "Failed to check iMessage availability for $address", e)
                }
            }
        }
    }

    /**
     * Check if a string looks like a phone number
     */
    private fun String.isPhoneNumber(): Boolean {
        val cleaned = this.replace(Regex("[^0-9+]"), "")
        return cleaned.startsWith("+") || (cleaned.length >= 10 && cleaned.all { it.isDigit() })
    }
}
