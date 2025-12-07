package com.bothbubbles.ui.settings.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.services.export.ExportConfig
import com.bothbubbles.services.export.ExportFormat
import com.bothbubbles.services.export.ExportProgress
import com.bothbubbles.services.export.ExportStyle
import com.bothbubbles.services.export.MessageExportService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val exportService: MessageExportService,
    private val chatDao: ChatDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    private var exportJob: Job? = null

    init {
        loadChats()
    }

    private fun loadChats() {
        viewModelScope.launch {
            chatDao.getAllChats().collect { chats ->
                val chatInfoList = chats.map { chat ->
                    ExportableChatInfo(
                        guid = chat.guid,
                        displayName = chat.displayName ?: chat.chatIdentifier ?: "Unknown",
                        isGroup = chat.isGroup
                    )
                }
                _uiState.update { it.copy(availableChats = chatInfoList) }
            }
        }
    }

    fun setFormat(format: ExportFormat) {
        _uiState.update { it.copy(format = format) }
    }

    fun setStyle(style: ExportStyle) {
        _uiState.update { it.copy(style = style) }
    }

    fun setExportAllChats(exportAll: Boolean) {
        _uiState.update { it.copy(exportAllChats = exportAll) }
    }

    fun toggleChatSelection(chatGuid: String) {
        _uiState.update { state ->
            val newSelection = if (state.selectedChatGuids.contains(chatGuid)) {
                state.selectedChatGuids - chatGuid
            } else {
                state.selectedChatGuids + chatGuid
            }
            state.copy(selectedChatGuids = newSelection)
        }
    }

    fun setAllChatsSelected(selected: Boolean) {
        _uiState.update { state ->
            val newSelection = if (selected) {
                state.availableChats.map { it.guid }.toSet()
            } else {
                emptySet()
            }
            state.copy(selectedChatGuids = newSelection)
        }
    }

    fun setDateRangeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(dateRangeEnabled = enabled) }
    }

    fun setStartDate(date: Long?) {
        _uiState.update { it.copy(startDate = date) }
    }

    fun setEndDate(date: Long?) {
        _uiState.update { it.copy(endDate = date) }
    }

    fun startExport() {
        val state = _uiState.value

        // Validate selection
        if (!state.exportAllChats && state.selectedChatGuids.isEmpty()) {
            _uiState.update {
                it.copy(exportProgress = ExportProgress.Error("Please select at least one conversation"))
            }
            return
        }

        val config = ExportConfig(
            format = state.format,
            style = state.style,
            chatGuids = if (state.exportAllChats) emptyList() else state.selectedChatGuids.toList(),
            startDate = if (state.dateRangeEnabled) state.startDate else null,
            endDate = if (state.dateRangeEnabled) state.endDate else null
        )

        exportJob = viewModelScope.launch {
            exportService.export(config).collect { progress ->
                _uiState.update { it.copy(exportProgress = progress) }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _uiState.update { it.copy(exportProgress = ExportProgress.Cancelled) }
    }

    fun resetExportState() {
        exportService.resetProgress()
        _uiState.update { it.copy(exportProgress = ExportProgress.Idle) }
    }

    override fun onCleared() {
        super.onCleared()
        exportJob?.cancel()
    }
}

data class ExportUiState(
    val format: ExportFormat = ExportFormat.HTML,
    val style: ExportStyle = ExportStyle.CHAT_BUBBLES,
    val exportAllChats: Boolean = true,
    val selectedChatGuids: Set<String> = emptySet(),
    val dateRangeEnabled: Boolean = false,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val availableChats: List<ExportableChatInfo> = emptyList(),
    val exportProgress: ExportProgress = ExportProgress.Idle
) {
    val isExporting: Boolean
        get() = exportProgress is ExportProgress.Loading ||
                exportProgress is ExportProgress.Generating ||
                exportProgress is ExportProgress.Saving

    val canExport: Boolean
        get() = !isExporting && (exportAllChats || selectedChatGuids.isNotEmpty())
}

data class ExportableChatInfo(
    val guid: String,
    val displayName: String,
    val isGroup: Boolean
)
