package com.bothbubbles

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.bothbubbles.services.AppLifecycleTracker
import com.bothbubbles.services.fcm.FcmTokenManager
import com.bothbubbles.services.socket.SocketConnectionManager
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BothBubblesApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var socketConnectionManager: SocketConnectionManager

    @Inject
    lateinit var fcmTokenManager: FcmTokenManager

    @Inject
    lateinit var appLifecycleTracker: AppLifecycleTracker

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                // Use ImageDecoder for GIFs on Android 9+ (API 28+), fallback to GifDecoder
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        PhoneNumberFormatter.init(this)
        createNotificationChannels()

        // Initialize app lifecycle tracker (must be before other managers that may depend on it)
        appLifecycleTracker.initialize()

        // Initialize socket connection manager for auto-connect
        socketConnectionManager.initialize()

        // Initialize FCM token manager (handles token retrieval and server registration)
        fcmTokenManager.initialize()
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // Messages channel
        val messagesChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_messages_desc)
            enableVibration(true)
            enableLights(true)
        }

        // Background service channel
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_service_desc)
            setShowBadge(false)
        }

        notificationManager.createNotificationChannels(listOf(messagesChannel, serviceChannel))
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_SERVICE = "service"
    }
}
