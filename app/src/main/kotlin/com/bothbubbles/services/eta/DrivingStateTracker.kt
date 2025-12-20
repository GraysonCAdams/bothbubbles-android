package com.bothbubbles.services.eta

import android.content.Context
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.asFlow
import com.bothbubbles.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether the user is actively driving based on:
 * 1. Android Auto connection state (primary - official API)
 * 2. Navigation active state (fallback - navigation notification present)
 *
 * Uses the official [CarConnection] API which is the recommended way to detect
 * Android Auto/Automotive connection state.
 */
@Singleton
class DrivingStateTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val etaSharingManager: EtaSharingManager,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DrivingStateTracker"
    }

    private val carConnection = CarConnection(context)

    // Convert LiveData to Flow for use with combine
    private val carConnectionTypeFlow = carConnection.type.asFlow()

    /**
     * Whether the user is considered to be actively driving.
     *
     * True if:
     * - Connected to Android Auto (projection mode), OR
     * - Connected to Android Automotive OS (native), OR
     * - Navigation is active (fallback for non-Android Auto driving)
     */
    val isActivelyDriving: StateFlow<Boolean> = combine(
        carConnectionTypeFlow,
        etaSharingManager.isNavigationActive
    ) { carState: Int, navActive: Boolean ->
        // Android Auto or Automotive = definitely driving
        val isCarConnected = carState == CarConnection.CONNECTION_TYPE_PROJECTION ||
                carState == CarConnection.CONNECTION_TYPE_NATIVE

        if (isCarConnected) {
            Timber.d("$TAG: Car connected (type=$carState), actively driving=true")
        }

        // Navigation active is fallback indicator (user might be driving without Android Auto)
        isCarConnected || navActive
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    /**
     * Whether the device is connected to Android Auto or Android Automotive.
     * More specific than [isActivelyDriving] - only true for car connections.
     */
    val isCarConnected: StateFlow<Boolean> = carConnectionTypeFlow
        .map { carState ->
            carState == CarConnection.CONNECTION_TYPE_PROJECTION ||
                    carState == CarConnection.CONNECTION_TYPE_NATIVE
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )
}
