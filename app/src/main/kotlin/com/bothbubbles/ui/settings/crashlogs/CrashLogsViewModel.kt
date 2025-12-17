package com.bothbubbles.ui.settings.crashlogs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.acra.ACRA
import org.acra.file.ReportLocator
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class CrashReport(
    val id: String,
    val timestamp: Long,
    val file: File,
    val preview: String
)

data class CrashLogsUiState(
    val crashReports: List<CrashReport> = emptyList(),
    val isLoading: Boolean = true,
    val selectedReport: CrashReport? = null,
    val selectedReportContent: String? = null
)

@HiltViewModel
class CrashLogsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(CrashLogsUiState())
    val uiState: StateFlow<CrashLogsUiState> = _uiState.asStateFlow()

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())

    init {
        loadCrashReports()
    }

    fun loadCrashReports() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val reports = withContext(Dispatchers.IO) {
                try {
                    val reportLocator = ReportLocator(context)
                    val reportFiles = reportLocator.unapprovedReportFiles + reportLocator.approvedReportFiles

                    reportFiles.mapNotNull { file ->
                        try {
                            val content = file.readText()
                            val preview = content.take(200).replace("\n", " ").trim()
                            CrashReport(
                                id = file.name,
                                timestamp = file.lastModified(),
                                file = file,
                                preview = if (preview.length >= 200) "$preview..." else preview
                            )
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to read crash report: ${file.name}")
                            null
                        }
                    }.sortedByDescending { it.timestamp }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to load crash reports")
                    emptyList()
                }
            }

            _uiState.update { it.copy(crashReports = reports, isLoading = false) }
        }
    }

    fun selectReport(report: CrashReport) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                try {
                    report.file.readText()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to read crash report content")
                    "Error reading crash report"
                }
            }
            _uiState.update { it.copy(selectedReport = report, selectedReportContent = content) }
        }
    }

    fun clearSelectedReport() {
        _uiState.update { it.copy(selectedReport = null, selectedReportContent = null) }
    }

    fun deleteReport(report: CrashReport) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    report.file.delete()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to delete crash report")
                }
            }
            loadCrashReports()
            if (_uiState.value.selectedReport?.id == report.id) {
                clearSelectedReport()
            }
        }
    }

    fun deleteAllReports() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val reportLocator = ReportLocator(context)
                    (reportLocator.unapprovedReportFiles + reportLocator.approvedReportFiles).forEach { file ->
                        try {
                            file.delete()
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to delete: ${file.name}")
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to delete all crash reports")
                }
            }
            loadCrashReports()
            clearSelectedReport()
        }
    }

    fun formatTimestamp(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }
}
