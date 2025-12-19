package com.bothbubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.core.model.Life360Member
import com.bothbubbles.data.repository.Life360Repository
import com.bothbubbles.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for the full-screen Life360 map.
 * Observes Life360 member by address for real-time location updates.
 */
@HiltViewModel
class Life360MapViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    life360Repository: Life360Repository
) : ViewModel() {

    private val route: Screen.Life360Map = savedStateHandle.toRoute()
    val participantAddress: String = route.participantAddress

    val life360Member: StateFlow<Life360Member?> = life360Repository
        .observeMemberByAddress(participantAddress)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )
}
