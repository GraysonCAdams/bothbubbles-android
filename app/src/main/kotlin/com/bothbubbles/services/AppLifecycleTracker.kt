package com.bothbubbles.services

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether the app is in the foreground or background.
 * Uses ProcessLifecycleOwner to observe app lifecycle state.
 */
@Singleton
class AppLifecycleTracker @Inject constructor() : DefaultLifecycleObserver {

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    // Observable foreground state for components that need to react to lifecycle changes
    private val _foregroundState = MutableStateFlow(false)
    val foregroundState: StateFlow<Boolean> = _foregroundState.asStateFlow()

    fun initialize() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        isAppInForeground = true
        _foregroundState.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        isAppInForeground = false
        _foregroundState.value = false
    }
}
