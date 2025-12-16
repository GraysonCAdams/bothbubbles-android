# Developer Tools

## Purpose

Developer and debugging features for troubleshooting connection issues, viewing event logs, and testing functionality.

## Files

| File | Description |
|------|-------------|
| `ConnectionModeManager.kt` | Manages Socket.IO ↔ FCM auto-switching and connection modes |
| `DeveloperEventLog.kt` | Circular buffer event log for debugging |

## Architecture

```
Developer Tools:

ConnectionModeManager
├── Socket-first mode (default)
├── FCM-only mode
├── Socket-only mode
└── Auto-switching logic

DeveloperEventLog
├── Circular buffer (last N events)
├── Event filtering
├── Export functionality
└── UI integration (DeveloperEventLogScreen)
```

## Required Patterns

### Event Logging

```kotlin
class DeveloperEventLog @Inject constructor() {
    private val events = ArrayDeque<LogEvent>(MAX_EVENTS)
    private var enabled = false

    fun log(category: String, message: String, data: Map<String, Any>? = null) {
        if (!enabled) return
        events.addLast(LogEvent(
            timestamp = System.currentTimeMillis(),
            category = category,
            message = message,
            data = data
        ))
        if (events.size > MAX_EVENTS) events.removeFirst()
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }
}
```

### Connection Mode Management

```kotlin
class ConnectionModeManager @Inject constructor(
    private val socketService: SocketService,
    private val settingsDataStore: SettingsDataStore
) {
    enum class ConnectionMode {
        SOCKET_FIRST,  // Use socket, fallback to FCM
        FCM_ONLY,      // Only use FCM push
        SOCKET_ONLY    // Only use socket (no FCM)
    }

    fun initialize() {
        // Watch for connection mode changes
        scope.launch {
            settingsDataStore.connectionMode.collect { mode ->
                applyConnectionMode(mode)
            }
        }
    }
}
```

## Best Practices

1. Gate all developer features behind developer mode flag
2. Use circular buffer to prevent memory leaks
3. Include timestamps for all logged events
4. Allow filtering/searching events
5. Provide export functionality for support
