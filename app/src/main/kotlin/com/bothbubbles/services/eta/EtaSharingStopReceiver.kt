package com.bothbubbles.services.eta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Broadcast receiver to handle "Stop Sharing" action from the ETA sharing notification.
 */
@AndroidEntryPoint
class EtaSharingStopReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EtaSharingStopReceiver"
    }

    @Inject
    lateinit var etaSharingManager: EtaSharingManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == NavigationListenerService.ACTION_STOP_SHARING) {
            Log.d(TAG, "Stop sharing action received")
            etaSharingManager.stopSharing(sendFinalMessage = true)
        }
    }
}
