package com.bothbubbles.services.socket.handlers

import android.util.Log
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.socket.FaceTimeCallStatus
import com.bothbubbles.services.socket.SocketEvent
import com.bothbubbles.services.socket.UiRefreshEvent
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles system-level socket events:
 * - Server updates
 * - FaceTime calls
 * - iCloud account status
 * - Scheduled messages
 * - Errors
 */
@Singleton
class SystemEventHandler @Inject constructor(
    private val notificationService: NotificationService
) {
    companion object {
        private const val TAG = "SystemEventHandler"
    }

    fun handleServerUpdate(event: SocketEvent.ServerUpdate) {
        Log.i(TAG, "Server update available: ${event.version}")
        // Show notification to inform user about the update
        notificationService.showServerUpdateNotification(event.version)
    }

    fun handleIncomingFaceTime(
        event: SocketEvent.IncomingFaceTime,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Log.d(TAG, "Incoming FaceTime from: ${event.caller}")

        // Show notification for incoming FaceTime call
        val callerDisplay = PhoneNumberFormatter.format(event.caller)
        notificationService.showFaceTimeCallNotification(
            callUuid = "facetime-${event.timestamp}",
            callerName = callerDisplay,
            callerAddress = event.caller
        )

        // Emit UI refresh event
        uiRefreshEvents.tryEmit(UiRefreshEvent.IncomingFaceTime(event.caller))
    }

    fun handleFaceTimeCall(event: SocketEvent.FaceTimeCall) {
        Log.d(TAG, "FaceTime call: ${event.callUuid}, status: ${event.status}")

        when (event.status) {
            FaceTimeCallStatus.INCOMING -> {
                // Show incoming FaceTime call notification
                val callerDisplay = event.callerName ?: event.callerAddress?.let { PhoneNumberFormatter.format(it) } ?: ""
                notificationService.showFaceTimeCallNotification(
                    callUuid = event.callUuid,
                    callerName = callerDisplay,
                    callerAddress = event.callerAddress
                )
            }
            FaceTimeCallStatus.DISCONNECTED -> {
                // Dismiss the notification when call ends
                notificationService.dismissFaceTimeCallNotification(event.callUuid)
            }
            FaceTimeCallStatus.CONNECTED, FaceTimeCallStatus.RINGING -> {
                // Update notification state if needed
                Log.d(TAG, "FaceTime call state: ${event.status}")
            }
            FaceTimeCallStatus.UNKNOWN -> {
                Log.w(TAG, "Unknown FaceTime call status")
            }
        }
    }

    fun handleICloudAccountStatus(
        event: SocketEvent.ICloudAccountStatus,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Log.i(TAG, "iCloud account status changed: alias=${event.alias}, active=${event.active}")

        if (!event.active) {
            // iCloud account logged out - show a notification to inform the user
            notificationService.showICloudAccountNotification(active = false, alias = event.alias)
        }

        // Emit UI refresh event so settings/status screens can update
        uiRefreshEvents.tryEmit(UiRefreshEvent.ICloudAccountStatusChanged(event.alias, event.active))
    }

    fun handleError(event: SocketEvent.Error) {
        Log.e(TAG, "Socket error: ${event.message}")
    }

    // ===== Scheduled Message Handlers =====

    fun handleScheduledMessageCreated(
        event: SocketEvent.ScheduledMessageCreated,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Log.d(TAG, "Scheduled message created: ${event.messageId} for chat ${event.chatGuid}")
        // Server-side scheduled messages are managed by the server
        // We log them for visibility but don't need to sync them locally
        // since we use client-side scheduling via WorkManager
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("scheduled_message_created"))
    }

    fun handleScheduledMessageSent(
        event: SocketEvent.ScheduledMessageSent,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Log.d(TAG, "Scheduled message sent: ${event.messageId} -> ${event.sentMessageGuid}")
        // The actual message will arrive via new-message event
        // This event is just for tracking that a scheduled message was sent
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("scheduled_message_sent"))
    }

    fun handleScheduledMessageError(
        event: SocketEvent.ScheduledMessageError,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Log.e(TAG, "Scheduled message error: ${event.messageId} - ${event.errorMessage}")
        // Could show a notification or UI indicator that a scheduled message failed
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("scheduled_message_error"))
    }

    fun handleScheduledMessageDeleted(
        event: SocketEvent.ScheduledMessageDeleted,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Log.d(TAG, "Scheduled message deleted: ${event.messageId}")
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("scheduled_message_deleted"))
    }
}
