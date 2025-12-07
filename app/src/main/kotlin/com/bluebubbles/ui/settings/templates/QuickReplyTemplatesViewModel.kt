package com.bluebubbles.ui.settings.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.local.db.entity.QuickReplyTemplateEntity
import com.bluebubbles.data.repository.QuickReplyTemplateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QuickReplyTemplatesViewModel @Inject constructor(
    private val repository: QuickReplyTemplateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuickReplyTemplatesUiState())
    val uiState: StateFlow<QuickReplyTemplatesUiState> = _uiState.asStateFlow()

    init {
        observeTemplates()
    }

    private fun observeTemplates() {
        repository.observeAllTemplates()
            .onEach { templates ->
                _uiState.update {
                    it.copy(
                        templates = templates,
                        isLoading = false
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun createTemplate(title: String, text: String = title) {
        if (title.isBlank()) return

        viewModelScope.launch {
            try {
                repository.createTemplate(title, text)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun updateTemplate(template: QuickReplyTemplateEntity) {
        viewModelScope.launch {
            try {
                repository.updateTemplate(template)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteTemplate(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteTemplate(id)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun toggleFavorite(id: Long) {
        viewModelScope.launch {
            try {
                repository.toggleFavorite(id)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            try {
                repository.deleteAllTemplates()
                repository.createDefaultTemplatesIfNeeded()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class QuickReplyTemplatesUiState(
    val templates: List<QuickReplyTemplateEntity> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)
