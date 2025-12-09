package com.bothbubbles.services.fcm

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Firebase Cloud Messaging service for BlueBubbles.
 *
 * This service receives FCM push notifications from the BlueBubbles server
 * and delegates processing to FcmMessageHandler.
 *
 * Note: Services cannot use constructor injection with Hilt, so we use
 * EntryPoint to access dependencies.
 */
class BothBubblesFirebaseService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "BothBubblesFCM"
        // Maximum time allowed for FCM message processing before timeout
        private const val FCM_PROCESSING_TIMEOUT_MS = 10_000L
    }

    // Lazy initialization of dependencies via EntryPoint
    private val entryPoint: FirebaseServiceEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            FirebaseServiceEntryPoint::class.java
        )
    }

    private val fcmMessageHandler: FcmMessageHandler by lazy {
        entryPoint.fcmMessageHandler()
    }

    private val fcmTokenManager: FcmTokenManager by lazy {
        entryPoint.fcmTokenManager()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FirebaseMessagingService created")
    }

    /**
     * Called when FCM token is created or refreshed.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed: ${token.take(10)}...")
        fcmTokenManager.onTokenRefreshed(token)
    }

    /**
     * Called when a message is received from FCM.
     *
     * IMPORTANT: Uses runBlocking to ensure message processing completes before
     * this method returns. Without this, if the app process is killed immediately
     * after onMessageReceived returns, the notification may not be shown.
     *
     * The timeout ensures we don't block indefinitely if something goes wrong.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val messageId = message.messageId
        val type = message.data["type"]
        Log.d(TAG, "FCM message received: id=$messageId, type=$type")

        // Use runBlocking to ensure processing completes before method returns.
        // This prevents message/notification loss if the app is killed immediately.
        runBlocking {
            val result = withTimeoutOrNull(FCM_PROCESSING_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    try {
                        fcmMessageHandler.handleMessage(message)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling FCM message", e)
                    }
                }
            }
            if (result == null) {
                Log.w(TAG, "FCM message processing timed out after ${FCM_PROCESSING_TIMEOUT_MS}ms")
            }
        }
    }

    override fun onDeletedMessages() {
        super.onDeletedMessages()
        Log.d(TAG, "FCM messages deleted (too many pending)")
        // Could trigger a sync to fetch missed messages
    }

    override fun onMessageSent(msgId: String) {
        super.onMessageSent(msgId)
        Log.d(TAG, "FCM message sent: $msgId")
    }

    override fun onSendError(msgId: String, exception: Exception) {
        super.onSendError(msgId, exception)
        Log.e(TAG, "FCM send error: $msgId", exception)
    }
}

/**
 * Hilt EntryPoint for accessing dependencies in FirebaseMessagingService.
 *
 * Services cannot use constructor injection, so we use EntryPoint pattern.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FirebaseServiceEntryPoint {
    fun fcmMessageHandler(): FcmMessageHandler
    fun fcmTokenManager(): FcmTokenManager
}
