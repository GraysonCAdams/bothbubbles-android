package com.bothbubbles.ui.settings.blocked

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.services.contacts.ContactBlocker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class BlockedContactsViewModel @Inject constructor(
    private val contactBlocker: ContactBlocker
) : ViewModel() {

    private val _uiState = MutableStateFlow(BlockedContactsUiState())
    val uiState: StateFlow<BlockedContactsUiState> = _uiState.asStateFlow()

    init {
        loadBlockedNumbers()
    }

    fun loadBlockedNumbers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                val numbers = withContext(Dispatchers.IO) {
                    contactBlocker.getBlockedNumbers()
                }
                _uiState.update {
                    it.copy(
                        blockedNumbers = numbers,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
            }
        }
    }

    fun blockNumber(number: String) {
        viewModelScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    contactBlocker.blockNumber(number)
                }
                if (success) {
                    loadBlockedNumbers()
                } else {
                    _uiState.update { it.copy(error = "Failed to block number") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun unblockNumber(number: String) {
        viewModelScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    contactBlocker.unblockNumber(number)
                }
                if (success) {
                    loadBlockedNumbers()
                } else {
                    _uiState.update { it.copy(error = "Failed to unblock number") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class BlockedContactsUiState(
    val blockedNumbers: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)
