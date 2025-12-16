# Foreground Services

## Purpose

Android foreground services for maintaining persistent connections and background operations.

## Files

| File | Description |
|------|-------------|
| `SocketForegroundService.kt` | Maintains persistent Socket.IO connection |

## Architecture

```
Foreground Service Lifecycle:

App Start → SocketForegroundService.start()
          → Show persistent notification
          → Maintain socket connection
          → Handle connection state changes

App Stop → SocketForegroundService.stop()
         → Clean up socket connection
         → Remove notification
```

## Required Patterns

### Foreground Service

```kotlin
class SocketForegroundService : Service() {
    @Inject lateinit var socketService: SocketService

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForeground()
            ACTION_STOP -> stopForeground()
        }
        return START_STICKY
    }

    private fun startForeground() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        socketService.connect()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"

        fun start(context: Context) {
            val intent = Intent(context, SocketForegroundService::class.java)
            intent.action = ACTION_START
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
```

### Service Notification

```kotlin
private fun buildNotification(): Notification {
    return NotificationCompat.Builder(this, CHANNEL_SERVICE)
        .setContentTitle("BothBubbles")
        .setContentText("Connected to server")
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
}
```

## Best Practices

1. Use `START_STICKY` to restart after system kills
2. Show low-priority notification (not intrusive)
3. Update notification with connection status
4. Handle doze mode and battery optimization
5. Clean up resources in `onDestroy()`
