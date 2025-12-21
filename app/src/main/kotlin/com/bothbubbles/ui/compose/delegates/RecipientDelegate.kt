package com.bothbubbles.ui.compose.delegates

import timber.log.Timber
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.ui.compose.RecipientChip
import com.bothbubbles.ui.compose.RecipientService
import com.bothbubbles.ui.compose.RecipientSuggestion
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Delegate managing recipient chips in the compose screen.
 *
 * Handles:
 * - Adding/removing chips
 * - Service detection (iMessage/SMS/Invalid)
 * - Validation (format checking)
 * - Group chat locking rules
 */
class RecipientDelegate @Inject constructor(
    private val api: BothBubblesApi,
    private val socketConnection: SocketConnection,
    private val settingsDataStore: SettingsDataStore,
    private val handleRepository: HandleRepository
) {
    private val _chips = MutableStateFlow<ImmutableList<RecipientChip>>(persistentListOf())
    val chips: StateFlow<ImmutableList<RecipientChip>> = _chips.asStateFlow()

    private val _isRecipientFieldLocked = MutableStateFlow(false)
    val isRecipientFieldLocked: StateFlow<Boolean> = _isRecipientFieldLocked.asStateFlow()

    private var scope: CoroutineScope? = null

    /**
     * Initialize the delegate with a coroutine scope.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
    }

    /**
     * Add a chip from a suggestion selection.
     */
    fun addChipFromSuggestion(suggestion: RecipientSuggestion) {
        when (suggestion) {
            is RecipientSuggestion.Contact -> {
                val service = when {
                    suggestion.service.equals("iMessage", ignoreCase = true) -> RecipientService.IMESSAGE
                    suggestion.service.equals("RCS", ignoreCase = true) -> RecipientService.SMS // RCS shows as green
                    else -> RecipientService.SMS
                }

                val chip = RecipientChip(
                    id = UUID.randomUUID().toString(),
                    address = suggestion.address,
                    displayName = suggestion.displayName,
                    service = service,
                    isGroup = false,
                    // Use chatGuid from unified groups for direct navigation
                    chatGuid = suggestion.chatGuid,
                    avatarPath = suggestion.avatarPath
                )

                addChip(chip)
            }
            is RecipientSuggestion.Group -> {
                val chip = RecipientChip(
                    id = UUID.randomUUID().toString(),
                    address = "", // Groups don't have an address
                    displayName = suggestion.displayName,
                    service = RecipientService.IMESSAGE, // Groups are iMessage by default
                    isGroup = true,
                    chatGuid = suggestion.chatGuid,
                    avatarPath = suggestion.avatarPath
                )

                addChip(chip)

                // Lock the field after adding a group
                _isRecipientFieldLocked.value = true
            }
        }
    }

    /**
     * Add a chip from raw text input (Enter pressed).
     * Validates the format and determines service asynchronously.
     */
    fun addChipFromText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        // Create chip with initial service based on format validation
        val (isValid, initialService) = validateAddressFormat(trimmed)

        val chip = RecipientChip(
            id = UUID.randomUUID().toString(),
            address = trimmed,
            displayName = formatDisplayName(trimmed),
            service = if (isValid) initialService else RecipientService.INVALID,
            isGroup = false
        )

        addChip(chip)

        // If valid, check iMessage availability asynchronously
        if (isValid) {
            scope?.launch {
                checkAndUpdateService(chip.id, trimmed)
            }
        }
    }

    /**
     * Remove a chip by reference.
     */
    fun removeChip(chip: RecipientChip) {
        val currentChips = _chips.value.toMutableList()
        currentChips.removeIf { it.id == chip.id }
        _chips.value = currentChips.toImmutableList()

        // If the removed chip was a group, unlock the field
        if (chip.isGroup) {
            _isRecipientFieldLocked.value = false
        }
    }

    /**
     * Clear all chips.
     */
    fun clearChips() {
        _chips.value = persistentListOf()
        _isRecipientFieldLocked.value = false
    }

    /**
     * Check if groups are allowed in suggestions.
     * Groups are only allowed if there are no chips yet.
     */
    fun areGroupsAllowed(): Boolean {
        return _chips.value.isEmpty()
    }

    /**
     * Get the effective service for all chips.
     * Used for determining chip colors.
     */
    fun getEffectiveService(): RecipientService {
        val chips = _chips.value
        return when {
            chips.any { it.service == RecipientService.INVALID } -> RecipientService.INVALID
            chips.any { it.service == RecipientService.SMS } -> RecipientService.SMS
            else -> RecipientService.IMESSAGE
        }
    }

    private fun addChip(chip: RecipientChip) {
        // Don't allow adding if locked (group selected)
        if (_isRecipientFieldLocked.value) return

        // Don't allow groups if there are already chips
        if (chip.isGroup && _chips.value.isNotEmpty()) return

        // Don't allow non-groups if a group is selected
        if (!chip.isGroup && _chips.value.any { it.isGroup }) return

        val currentChips = _chips.value.toMutableList()
        currentChips.add(chip)
        _chips.value = currentChips.toImmutableList()
    }

    /**
     * Validate address format and return initial service type.
     */
    private fun validateAddressFormat(address: String): Pair<Boolean, RecipientService> {
        return when {
            address.isEmail() -> true to RecipientService.IMESSAGE // Emails are always iMessage
            address.isPhoneNumber() -> true to RecipientService.SMS // Default to SMS, will check iMessage
            else -> false to RecipientService.INVALID
        }
    }

    /**
     * Check iMessage availability and update chip service.
     */
    private suspend fun checkAndUpdateService(chipId: String, address: String) {
        try {
            val smsOnlyMode = settingsDataStore.smsOnlyMode.first()
            val isConnected = socketConnection.connectionState.value == ConnectionState.CONNECTED

            // In SMS-only mode or disconnected, keep as SMS
            if (smsOnlyMode || !isConnected) return

            // Check if it's an email (always iMessage)
            if (address.isEmail()) {
                updateChipService(chipId, RecipientService.IMESSAGE)
                return
            }

            // Check cached handle first
            val normalizedAddress = PhoneAndCodeParsingUtils.normalizePhoneNumber(address)
            val cachedHandle = handleRepository.getHandleByAddressAny(address)
                ?: handleRepository.getHandleByAddressAny(normalizedAddress)

            if (cachedHandle?.isIMessage == true) {
                updateChipService(chipId, RecipientService.IMESSAGE)
                return
            }

            // Check with server
            val response = api.checkIMessageAvailability(address)
            if (response.isSuccessful && response.body()?.data?.available == true) {
                updateChipService(chipId, RecipientService.IMESSAGE)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to check iMessage availability")
            // Keep existing service on error
        }
    }

    private fun updateChipService(chipId: String, service: RecipientService) {
        val currentChips = _chips.value.toMutableList()
        val index = currentChips.indexOfFirst { it.id == chipId }
        if (index >= 0) {
            currentChips[index] = currentChips[index].copy(service = service)
            _chips.value = currentChips.toImmutableList()
        }
    }

    private fun formatDisplayName(address: String): String {
        // For now, just return the address. Could enhance with contact lookup.
        return address
    }

    private fun String.isPhoneNumber(): Boolean {
        val cleaned = this.replace(Regex("[^0-9+]"), "")
        return cleaned.startsWith("+") || (cleaned.length >= 10 && cleaned.all { it.isDigit() })
    }

    private fun String.isEmail(): Boolean {
        return this.contains("@") && this.contains(".") && this.length > 5
    }
}
