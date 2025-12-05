package com.bluebubbles.ui.settings.blocked

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class BlockedContactsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
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
                    getBlockedNumbers()
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
                withContext(Dispatchers.IO) {
                    val values = ContentValues().apply {
                        put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
                    }
                    context.contentResolver.insert(BlockedNumbers.CONTENT_URI, values)
                }
                loadBlockedNumbers()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun unblockNumber(number: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.delete(
                        BlockedNumbers.CONTENT_URI,
                        "${BlockedNumbers.COLUMN_ORIGINAL_NUMBER} = ?",
                        arrayOf(number)
                    )
                }
                loadBlockedNumbers()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    private fun getBlockedNumbers(): List<String> {
        val numbers = mutableListOf<String>()

        try {
            context.contentResolver.query(
                BlockedNumbers.CONTENT_URI,
                arrayOf(BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
                null,
                null,
                null
            )?.use { cursor ->
                val columnIndex = cursor.getColumnIndex(BlockedNumbers.COLUMN_ORIGINAL_NUMBER)
                while (cursor.moveToNext()) {
                    val number = cursor.getString(columnIndex)
                    if (number != null) {
                        numbers.add(number)
                    }
                }
            }
        } catch (e: SecurityException) {
            // Not authorized to access blocked numbers
        }

        return numbers
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
