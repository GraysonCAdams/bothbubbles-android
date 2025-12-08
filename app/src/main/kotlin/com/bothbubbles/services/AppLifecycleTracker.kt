package com.bothbubbles.services

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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

    fun initialize() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        isAppInForeground = true
    }

    override fun onStop(owner: LifecycleOwner) {
        isAppInForeground = false
    }
}
