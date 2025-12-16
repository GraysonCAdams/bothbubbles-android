# ETA Sharing Service

## Purpose

Allows users to share their estimated time of arrival (ETA) from navigation apps (Google Maps, Waze, etc.) with contacts.

## Files

| File | Description |
|------|-------------|
| `EtaModels.kt` | Data models for ETA information |
| `EtaSharingManager.kt` | Core ETA sharing logic and state management |
| `EtaSharingReceiver.kt` | Broadcast receiver for navigation intents |
| `NavigationEtaParser.kt` | Parse ETA from navigation notifications |
| `NavigationListenerService.kt` | NotificationListenerService for navigation apps |

## Architecture

```
ETA Sharing Flow:

Navigation App (Maps/Waze)
         │
         ▼
NavigationListenerService (NotificationListener)
         │
         ▼
NavigationEtaParser (extract ETA from notification)
         │
         ▼
EtaSharingManager (manage sharing state)
         │
         ▼
MessageSender (send ETA updates to contacts)
```

## Required Patterns

### Notification Listener

```kotlin
class NavigationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isNavigationNotification(sbn)) {
            val eta = NavigationEtaParser.parse(sbn)
            eta?.let { etaSharingManager.updateEta(it) }
        }
    }
}
```

### ETA Parsing

```kotlin
object NavigationEtaParser {
    fun parse(notification: StatusBarNotification): EtaInfo? {
        val text = notification.notification.extras
            .getString(Notification.EXTRA_TEXT) ?: return null

        // Parse patterns like "15 min · 5.2 mi"
        val etaMinutes = extractMinutes(text)
        val distance = extractDistance(text)

        return EtaInfo(
            minutes = etaMinutes,
            distance = distance,
            destination = extractDestination(text)
        )
    }
}
```

## Best Practices

1. Require NotificationListener permission
2. Support multiple navigation apps (Maps, Waze, etc.)
3. Rate limit ETA updates (don't spam contacts)
4. Allow contact selection for auto-sharing
5. Gracefully handle notification format changes
