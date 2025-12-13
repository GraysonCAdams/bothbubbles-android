package com.bothbubbles.services.fcm

import android.util.Log
import com.bothbubbles.di.ApplicationScope
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    private val applicationScope: CoroutineScope by lazy {
        entryPoint.applicationScope()
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
     * Uses application-scoped coroutine to process messages asynchronously.
     * FirebaseMessagingService keeps the process alive for ~20 seconds for high-priority
     * messages, giving enough time for processing. Using runBlocking would risk ANR.
     *
     * The timeout ensures we don't process indefinitely if something goes wrong.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val messageId = message.messageId
        val type = message.data["type"]
        Log.d(TAG, "FCM message received: id=$messageId, type=$type")

        // Use application scope for async processing.
        // FCM high-priority messages keep process alive for ~20 seconds.
        applicationScope.launch(Dispatchers.IO) {
            try {
                val result = withTimeoutOrNull(FCM_PROCESSING_TIMEOUT_MS) {
                    fcmMessageHandler.handleMessage(message)
                }
                if (result == null) {
                    Log.w(TAG, "FCM message processing timed out after ${FCM_PROCESSING_TIMEOUT_MS}ms")
                } else {
                    Log.d(TAG, "FCM message processed successfully: id=$messageId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling FCM message: id=$messageId", e)
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
    @ApplicationScope
    fun applicationScope(): CoroutineScope
}
