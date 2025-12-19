package com.bothbubbles.ui.settings.life360

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.core.data.prefs.FeaturePreferences
import com.bothbubbles.core.model.Life360Circle
import com.bothbubbles.core.model.Life360Member
import com.bothbubbles.core.model.entity.HandleEntity
import com.bothbubbles.core.model.entity.displayNameSimple
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.repository.Life360Repository
import com.bothbubbles.services.life360.Life360Service
import com.bothbubbles.services.life360.Life360TokenStorage
import com.bothbubbles.util.error.Life360Error
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class Life360SettingsViewModel @Inject constructor(
    private val life360Service: Life360Service,
    private val life360Repository: Life360Repository,
    private val tokenStorage: Life360TokenStorage,
    private val featurePreferences: FeaturePreferences,
    private val handleDao: HandleDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(Life360UiState())
    val uiState: StateFlow<Life360UiState> = _uiState.asStateFlow()

    private val _circles = MutableStateFlow<ImmutableList<Life360Circle>>(persistentListOf())
    val circles: StateFlow<ImmutableList<Life360Circle>> = _circles.asStateFlow()

    val members: StateFlow<ImmutableList<Life360Member>> = life360Repository.observeAllMembers()
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), persistentListOf())

    val isEnabled: StateFlow<Boolean> = featurePreferences.life360Enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isPaused: StateFlow<Boolean> = featurePreferences.life360PauseSyncing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val defaultCircleId: StateFlow<String?> = featurePreferences.life360DefaultCircleId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * All available handles (contacts) for mapping.
     */
    val availableHandles: StateFlow<ImmutableList<HandleEntity>> = handleDao.getAllHandles()
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), persistentListOf())

    /**
     * Map of handleId to display name for showing mapped contact names.
     */
    val handleDisplayNames: StateFlow<ImmutableMap<Long, String>> = handleDao.getAllHandles()
        .map { handles ->
            handles.associate { it.id to it.displayNameSimple }.toImmutableMap()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), persistentMapOf())

    init {
        checkAuthStatusAndSync()
    }

    /**
     * Check auth status and sync circles/members if authenticated.
     * Called when settings screen opens - uses CIRCLES endpoint rate limit (10 min).
     */
    private fun checkAuthStatusAndSync() {
        val isAuth = tokenStorage.isAuthenticated
        _uiState.value = _uiState.value.copy(isAuthenticated = isAuth)

        // Auto-sync when opening settings if authenticated
        if (isAuth) {
            Timber.d("Life360 settings opened, syncing circles and members")
            refreshCircles()
            syncMembers()
        }
    }

    fun storeToken(token: String) {
        viewModelScope.launch {
            life360Service.storeToken(token)
            _uiState.value = _uiState.value.copy(isAuthenticated = true)
            featurePreferences.setLife360Enabled(true)
            refreshCircles()
        }
    }

    fun logout() {
        viewModelScope.launch {
            life360Service.logout()
            featurePreferences.setLife360Enabled(false)
            featurePreferences.setLife360DefaultCircleId(null)
            _uiState.value = Life360UiState(isAuthenticated = false)
            _circles.value = persistentListOf()
        }
    }

    fun refreshCircles() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            life360Service.fetchCircles().fold(
                onSuccess = { circleList ->
                    _circles.value = circleList.toImmutableList()
                    _uiState.value = _uiState.value.copy(isLoading = false)

                    // Auto-select first circle if none selected
                    if (defaultCircleId.value == null && circleList.isNotEmpty()) {
                        featurePreferences.setLife360DefaultCircleId(circleList.first().id)
                    }
                },
                onFailure = { error ->
                    Timber.e(error, "Failed to fetch circles")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = formatError(error)
                    )
                }
            )
        }
    }

    fun syncMembers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, error = null)

            life360Service.syncAllCircles().fold(
                onSuccess = { count ->
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        lastSyncCount = count
                    )
                },
                onFailure = { error ->
                    Timber.e(error, "Failed to sync members")
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        error = formatError(error)
                    )
                }
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            featurePreferences.setLife360Enabled(enabled)
        }
    }

    fun setPaused(paused: Boolean) {
        viewModelScope.launch {
            featurePreferences.setLife360PauseSyncing(paused)
        }
    }

    fun setDefaultCircle(circleId: String) {
        viewModelScope.launch {
            featurePreferences.setLife360DefaultCircleId(circleId)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Map a Life360 member to a contact (handle).
     */
    fun mapMemberToContact(memberId: String, handleId: Long) {
        viewModelScope.launch {
            life360Repository.mapMemberToContact(memberId, handleId)
            Timber.d("Mapped member $memberId to contact $handleId")
        }
    }

    /**
     * Clear the contact mapping for a Life360 member.
     */
    fun unmapMember(memberId: String) {
        viewModelScope.launch {
            life360Repository.unmapMember(memberId)
            Timber.d("Unmapped member $memberId")
        }
    }

    private fun formatError(error: Throwable): String {
        return when (error) {
            is Life360Error.RateLimited -> "Rate limited. Please wait ${error.retryAfterSeconds}s."
            is Life360Error.AuthenticationFailed -> "Authentication failed. Please reconnect."
            is Life360Error.TokenExpired -> "Session expired. Please reconnect."
            is Life360Error.NoCirclesFound -> "No circles found for this account."
            is Life360Error.NetworkFailure -> "Network error. Check your connection."
            is Life360Error.ApiBlocked -> "Life360 is temporarily unavailable."
            else -> error.message ?: "Unknown error"
        }
    }
}

data class Life360UiState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val error: String? = null,
    val lastSyncCount: Int? = null
)
