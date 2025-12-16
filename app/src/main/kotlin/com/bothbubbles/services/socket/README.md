# Socket.IO Service

## Purpose

Real-time communication with the BlueBubbles server via Socket.IO. Handles connection management, event emission, and event handling.

## Files

| File | Description |
|------|-------------|
| `SocketConnection.kt` | Interface for socket operations |
| `SocketEventEmitter.kt` | Emit events to the server |
| `SocketEventHandler.kt` | Route incoming events to handlers |
| `SocketEventParser.kt` | Parse raw socket events |
| `SocketIOConnection.kt` | Low-level Socket.IO connection wrapper |
| `SocketService.kt` | Main implementation of SocketConnection |

## Architecture

```
Socket Architecture:

┌─────────────────────────────────────────────────────────────┐
│                    SocketService                            │
│  - Connection management                                    │
│  - Auto-reconnect logic                                     │
│  - Connection state tracking                                │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
    SocketIOConnection  SocketEventParser  SocketEventEmitter
    (low-level IO)      (parse events)     (emit events)
                              │
                              ▼
                    SocketEventHandler
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
    MessageEventHandler ChatEventHandler SystemEventHandler
```

## Required Patterns

### Socket Connection Interface

```kotlin
interface SocketConnection {
    val connectionState: StateFlow<ConnectionState>
    val isConnected: Boolean

    fun connect()
    fun disconnect()
    fun reconnect()

    suspend fun emit(event: String, data: Any): Result<Any>
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}
```

### Event Handler Routing

```kotlin
class SocketEventHandler @Inject constructor(
    private val messageHandler: MessageEventHandler,
    private val chatHandler: ChatEventHandler,
    private val systemHandler: SystemEventHandler
) {
    fun handleEvent(event: SocketEvent) {
        when (event) {
            is SocketEvent.NewMessage -> messageHandler.handleNewMessage(event)
            is SocketEvent.UpdatedMessage -> messageHandler.handleUpdatedMessage(event)
            is SocketEvent.ChatRead -> chatHandler.handleChatRead(event)
            is SocketEvent.TypingIndicator -> chatHandler.handleTypingIndicator(event)
            is SocketEvent.ServerInfo -> systemHandler.handleServerInfo(event)
            // ... etc
        }
    }
}
```

### Connection State Management

```kotlin
class SocketService @Inject constructor(
    private val socketIO: SocketIOConnection,
    private val eventHandler: SocketEventHandler,
    private val serverPreferences: ServerPreferences
) : SocketConnection {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = _connectionState.asStateFlow()

    override fun connect() {
        _connectionState.value = ConnectionState.CONNECTING

        socketIO.on("connect") {
            _connectionState.value = ConnectionState.CONNECTED
        }

        socketIO.on("disconnect") {
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }

        socketIO.onAny { event, data ->
            val parsed = SocketEventParser.parse(event, data)
            parsed?.let { eventHandler.handleEvent(it) }
        }

        socketIO.connect(serverUrl, password)
    }
}
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `handlers/` | Decomposed event handlers by category |

## Best Practices

1. Use interface for testability
2. Track connection state via StateFlow
3. Implement auto-reconnect with backoff
4. Parse events early, handle typed events
5. Decompose handlers by event category
6. Log events for debugging (developer mode)
