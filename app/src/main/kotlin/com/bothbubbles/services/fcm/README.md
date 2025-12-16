# Firebase Cloud Messaging

## Purpose

Firebase Cloud Messaging integration for push notifications when Socket.IO connection is unavailable.

## Files

| File | Description |
|------|-------------|
| `BothBubblesFirebaseService.kt` | FirebaseMessagingService implementation |
| `FcmMessageHandler.kt` | Process incoming FCM messages |
| `FcmTokenManager.kt` | Manage FCM token registration with server |
| `FcmTokenRegistrationWorker.kt` | Background worker for token registration |
| `FirebaseConfigManager.kt` | Firebase configuration management |

## Architecture

```
FCM Flow:

BlueBubbles Server
       │
       ▼ (FCM Push)
BothBubblesFirebaseService
       │
       ▼
FcmMessageHandler
       ├── Parse message payload
       ├── Store in database
       ├── Show notification
       └── Trigger socket reconnect (if applicable)
```

## Required Patterns

### Firebase Service

```kotlin
class BothBubblesFirebaseService : FirebaseMessagingService() {
    @Inject lateinit var fcmMessageHandler: FcmMessageHandler
    @Inject lateinit var fcmTokenManager: FcmTokenManager

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        fcmMessageHandler.handleMessage(remoteMessage)
    }

    override fun onNewToken(token: String) {
        fcmTokenManager.registerToken(token)
    }
}
```

### Token Registration

```kotlin
class FcmTokenManager @Inject constructor(
    private val api: BothBubblesApi,
    private val settingsDataStore: SettingsDataStore
) {
    suspend fun registerToken(token: String) {
        // Save locally
        settingsDataStore.setFcmToken(token)

        // Register with BlueBubbles server
        api.registerFcmToken(RegisterFcmTokenRequest(token))
    }
}
```

### Message Handling

```kotlin
class FcmMessageHandler @Inject constructor(
    private val incomingMessageHandler: IncomingMessageHandler,
    private val notificationService: NotificationService
) {
    fun handleMessage(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        when (data["type"]) {
            "new-message" -> handleNewMessage(data)
            "updated-message" -> handleUpdatedMessage(data)
            "typing-indicator" -> handleTypingIndicator(data)
        }
    }
}
```

## Best Practices

1. Re-register token on app update
2. Handle token refresh gracefully
3. Use WorkManager for reliable token registration
4. Parse FCM payload consistently with socket events
5. Trigger socket reconnect after FCM message (for real-time sync)
