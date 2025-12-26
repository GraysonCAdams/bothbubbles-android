package com.bothbubbles.ui.settings.autoresponder

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.core.model.entity.AutoResponderRuleEntity
import com.bothbubbles.data.local.db.dao.AutoResponderRuleDao
import com.bothbubbles.seam.stitches.Stitch
import com.bothbubbles.seam.stitches.StitchRegistry
import com.bothbubbles.services.context.DndModeType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.DayOfWeek
import javax.inject.Inject

data class AutoResponderRuleEditorUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val ruleId: Long? = null,

    // Basic info
    val name: String = "",
    val message: String = "",
    val isEnabled: Boolean = true,

    // Available options
    val availableStitches: ImmutableList<StitchOption> = persistentListOf(),

    // Source filtering
    val selectedStitchIds: ImmutableSet<String> = persistentSetOf(),
    val firstTimeFromSender: Boolean = false,

    // Time conditions
    val daysOfWeek: ImmutableSet<DayOfWeek> = persistentSetOf(),
    val timeStartMinutes: Int? = null,
    val timeEndMinutes: Int? = null,

    // System state conditions
    val dndModes: ImmutableSet<DndModeType> = persistentSetOf(),
    val requireDriving: Boolean = false,
    val requireOnCall: Boolean = false,

    // Location conditions
    val locationName: String = "",
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val locationRadiusMeters: Int = 100,
    val locationInside: Boolean = true,

    // Validation
    val nameError: String? = null,
    val messageError: String? = null,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false
)

data class StitchOption(
    val id: String,
    val displayName: String
)

@HiltViewModel
class AutoResponderRuleEditorViewModel @Inject constructor(
    private val ruleDao: AutoResponderRuleDao,
    private val stitchRegistry: StitchRegistry,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val ruleId: Long? = savedStateHandle.get<Long>("ruleId")?.takeIf { it != -1L }

    private val _uiState = MutableStateFlow(AutoResponderRuleEditorUiState())
    val uiState: StateFlow<AutoResponderRuleEditorUiState> = _uiState.asStateFlow()

    init {
        loadStitches()
        if (ruleId != null) {
            loadRule(ruleId)
        } else {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun loadStitches() {
        val stitchOptions = stitchRegistry.getAllStitches()
            .map { StitchOption(it.id, it.displayName) }
            .toImmutableList()
        _uiState.update { it.copy(availableStitches = stitchOptions) }
    }

    private fun loadRule(id: Long) {
        viewModelScope.launch {
            val rule = ruleDao.getById(id)
            if (rule != null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isEditing = true,
                        ruleId = rule.id,
                        name = rule.name,
                        message = rule.message,
                        isEnabled = rule.isEnabled,
                        selectedStitchIds = rule.sourceStitchIds
                            ?.split(",")
                            ?.map { s -> s.trim() }
                            ?.filter { s -> s.isNotBlank() }
                            ?.toImmutableSet()
                            ?: persistentSetOf(),
                        firstTimeFromSender = rule.firstTimeFromSender ?: false,
                        daysOfWeek = rule.daysOfWeek
                            ?.split(",")
                            ?.mapNotNull { d -> parseDayOfWeek(d.trim()) }
                            ?.toImmutableSet()
                            ?: persistentSetOf(),
                        timeStartMinutes = rule.timeStartMinutes,
                        timeEndMinutes = rule.timeEndMinutes,
                        dndModes = rule.dndModes
                            ?.split(",")
                            ?.mapNotNull { m -> DndModeType.entries.find { it.name == m.trim() } }
                            ?.toImmutableSet()
                            ?: persistentSetOf(),
                        requireDriving = rule.requireDriving ?: false,
                        requireOnCall = rule.requireOnCall ?: false,
                        locationName = rule.locationName ?: "",
                        locationLat = rule.locationLat,
                        locationLng = rule.locationLng,
                        locationRadiusMeters = rule.locationRadiusMeters ?: 100,
                        locationInside = rule.locationInside ?: true
                    )
                }
            } else {
                Timber.w("Rule not found: $id")
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun parseDayOfWeek(str: String): DayOfWeek? {
        return when (str.uppercase()) {
            "MON", "MONDAY" -> DayOfWeek.MONDAY
            "TUE", "TUESDAY" -> DayOfWeek.TUESDAY
            "WED", "WEDNESDAY" -> DayOfWeek.WEDNESDAY
            "THU", "THURSDAY" -> DayOfWeek.THURSDAY
            "FRI", "FRIDAY" -> DayOfWeek.FRIDAY
            "SAT", "SATURDAY" -> DayOfWeek.SATURDAY
            "SUN", "SUNDAY" -> DayOfWeek.SUNDAY
            else -> null
        }
    }

    fun setName(name: String) {
        _uiState.update { it.copy(name = name, nameError = null) }
    }

    fun setMessage(message: String) {
        _uiState.update { it.copy(message = message, messageError = null) }
    }

    fun setEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isEnabled = enabled) }
    }

    fun toggleStitch(stitchId: String) {
        _uiState.update { state ->
            val newSet = if (stitchId in state.selectedStitchIds) {
                state.selectedStitchIds.minus(stitchId).toImmutableSet()
            } else {
                state.selectedStitchIds.plus(stitchId).toImmutableSet()
            }
            state.copy(selectedStitchIds = newSet)
        }
    }

    fun setFirstTimeFromSender(enabled: Boolean) {
        _uiState.update { it.copy(firstTimeFromSender = enabled) }
    }

    fun toggleDayOfWeek(day: DayOfWeek) {
        _uiState.update { state ->
            val newSet = if (day in state.daysOfWeek) {
                state.daysOfWeek.minus(day).toImmutableSet()
            } else {
                state.daysOfWeek.plus(day).toImmutableSet()
            }
            state.copy(daysOfWeek = newSet)
        }
    }

    fun setTimeRange(startMinutes: Int?, endMinutes: Int?) {
        _uiState.update { it.copy(timeStartMinutes = startMinutes, timeEndMinutes = endMinutes) }
    }

    fun clearTimeRange() {
        _uiState.update { it.copy(timeStartMinutes = null, timeEndMinutes = null) }
    }

    fun toggleDndMode(mode: DndModeType) {
        _uiState.update { state ->
            val newSet = if (mode in state.dndModes) {
                state.dndModes.minus(mode).toImmutableSet()
            } else {
                state.dndModes.plus(mode).toImmutableSet()
            }
            state.copy(dndModes = newSet)
        }
    }

    fun setRequireDriving(enabled: Boolean) {
        _uiState.update { it.copy(requireDriving = enabled) }
    }

    fun setRequireOnCall(enabled: Boolean) {
        _uiState.update { it.copy(requireOnCall = enabled) }
    }

    fun setLocation(name: String, lat: Double?, lng: Double?, radiusMeters: Int, inside: Boolean) {
        _uiState.update {
            it.copy(
                locationName = name,
                locationLat = lat,
                locationLng = lng,
                locationRadiusMeters = radiusMeters,
                locationInside = inside
            )
        }
    }

    fun clearLocation() {
        _uiState.update {
            it.copy(
                locationName = "",
                locationLat = null,
                locationLng = null,
                locationRadiusMeters = 100,
                locationInside = true
            )
        }
    }

    fun save() {
        val state = _uiState.value

        // Validate
        if (state.name.isBlank()) {
            _uiState.update { it.copy(nameError = "Name is required") }
            return
        }
        if (state.message.isBlank()) {
            _uiState.update { it.copy(messageError = "Message is required") }
            return
        }

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            try {
                val rule = AutoResponderRuleEntity(
                    id = state.ruleId ?: 0,
                    name = state.name.trim(),
                    message = state.message.trim(),
                    priority = if (state.isEditing) {
                        ruleDao.getById(state.ruleId!!)?.priority ?: 0
                    } else {
                        (ruleDao.getEnabledRulesByPriority().maxOfOrNull { it.priority } ?: 0) + 1
                    },
                    isEnabled = state.isEnabled,
                    sourceStitchIds = state.selectedStitchIds.takeIf { it.isNotEmpty() }
                        ?.joinToString(","),
                    firstTimeFromSender = state.firstTimeFromSender.takeIf { it },
                    daysOfWeek = state.daysOfWeek.takeIf { it.isNotEmpty() }
                        ?.joinToString(",") { dayToString(it) },
                    timeStartMinutes = state.timeStartMinutes,
                    timeEndMinutes = state.timeEndMinutes,
                    dndModes = state.dndModes.takeIf { it.isNotEmpty() }
                        ?.joinToString(",") { it.name },
                    requireDriving = state.requireDriving.takeIf { it },
                    requireOnCall = state.requireOnCall.takeIf { it },
                    locationName = state.locationName.takeIf { it.isNotBlank() },
                    locationLat = state.locationLat,
                    locationLng = state.locationLng,
                    locationRadiusMeters = state.locationRadiusMeters.takeIf { state.locationLat != null },
                    locationInside = state.locationInside.takeIf { state.locationLat != null },
                    updatedAt = System.currentTimeMillis()
                )

                if (state.isEditing) {
                    ruleDao.update(rule)
                    Timber.d("Updated rule: ${rule.name}")
                } else {
                    ruleDao.insert(rule)
                    Timber.d("Created rule: ${rule.name}")
                }

                _uiState.update { it.copy(isSaving = false, saveSuccess = true) }
            } catch (e: Exception) {
                Timber.e(e, "Failed to save rule")
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun dayToString(day: DayOfWeek): String {
        return when (day) {
            DayOfWeek.MONDAY -> "MON"
            DayOfWeek.TUESDAY -> "TUE"
            DayOfWeek.WEDNESDAY -> "WED"
            DayOfWeek.THURSDAY -> "THU"
            DayOfWeek.FRIDAY -> "FRI"
            DayOfWeek.SATURDAY -> "SAT"
            DayOfWeek.SUNDAY -> "SUN"
        }
    }
}
