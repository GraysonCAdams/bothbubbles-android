package com.bothbubbles.ui.settings.autoresponder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.core.model.entity.AutoResponderRuleEntity
import com.bothbubbles.data.local.db.dao.AutoResponderRuleDao
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.seam.hems.autoresponder.AutoResponderQuickAddExample
import com.bothbubbles.seam.stitches.StitchRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class AutoResponderRulesUiState(
    val isEnabled: Boolean = false,
    val rules: ImmutableList<AutoResponderRuleEntity> = persistentListOf(),
    val quickAddExamples: ImmutableList<QuickAddExampleWithStitch> = persistentListOf(),
    val rateLimit: Int = 10,
    val isLoading: Boolean = true
)

/**
 * Quick-add example paired with its source Stitch for display.
 */
data class QuickAddExampleWithStitch(
    val stitchId: String,
    val stitchName: String,
    val example: AutoResponderQuickAddExample
)

@HiltViewModel
class AutoResponderRulesViewModel @Inject constructor(
    private val ruleDao: AutoResponderRuleDao,
    private val stitchRegistry: StitchRegistry,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _isLoading = MutableStateFlow(true)

    val uiState: StateFlow<AutoResponderRulesUiState> = combine(
        settingsDataStore.autoResponderEnabled,
        settingsDataStore.autoResponderRateLimit,
        ruleDao.observeAllRulesByPriority(),
        _isLoading
    ) { enabled, rateLimit, rules, isLoading ->
        val examples = stitchRegistry.getAllStitches()
            .mapNotNull { stitch ->
                stitch.autoResponderQuickAddExample?.let { example ->
                    QuickAddExampleWithStitch(
                        stitchId = stitch.id,
                        stitchName = stitch.displayName,
                        example = example
                    )
                }
            }
            .toImmutableList()

        AutoResponderRulesUiState(
            isEnabled = enabled,
            rules = rules.toImmutableList(),
            quickAddExamples = examples,
            rateLimit = rateLimit,
            isLoading = isLoading
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AutoResponderRulesUiState()
    )

    init {
        viewModelScope.launch {
            _isLoading.value = false
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setAutoResponderEnabled(enabled)
        }
    }

    fun setRateLimit(limit: Int) {
        viewModelScope.launch {
            settingsDataStore.setAutoResponderRateLimit(limit)
        }
    }

    fun toggleRuleEnabled(ruleId: Long, enabled: Boolean) {
        viewModelScope.launch {
            ruleDao.setEnabled(ruleId, enabled)
        }
    }

    fun deleteRule(ruleId: Long) {
        viewModelScope.launch {
            ruleDao.deleteById(ruleId)
            Timber.d("Deleted rule $ruleId")
        }
    }

    fun createRuleFromQuickAdd(example: QuickAddExampleWithStitch) {
        viewModelScope.launch {
            val nextPriority = (uiState.value.rules.maxOfOrNull { it.priority } ?: 0) + 1
            val rule = AutoResponderRuleEntity(
                name = example.example.name,
                message = example.example.message,
                priority = nextPriority,
                isEnabled = true,
                sourceStitchIds = example.stitchId,
                firstTimeFromSender = true
            )
            ruleDao.insert(rule)
            Timber.d("Created rule from quick-add: ${example.example.name}")
        }
    }

    fun reorderRules(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val currentRules = uiState.value.rules.toMutableList()
            if (fromIndex < 0 || fromIndex >= currentRules.size ||
                toIndex < 0 || toIndex >= currentRules.size) {
                return@launch
            }

            val movedRule = currentRules.removeAt(fromIndex)
            currentRules.add(toIndex, movedRule)

            currentRules.forEachIndexed { index, rule ->
                ruleDao.updatePriority(rule.id, index)
            }
        }
    }
}
