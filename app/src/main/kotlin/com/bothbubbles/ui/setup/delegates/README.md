# Setup ViewModel Delegates

## Purpose

Delegate classes for SetupViewModel following the same pattern as ChatViewModel.

## Files

| File | Description |
|------|-------------|
| `AutoResponderDelegate.kt` | Auto responder configuration |
| `MlModelDelegate.kt` | ML model download |
| `PermissionsDelegate.kt` | Permission request handling |
| `ServerConnectionDelegate.kt` | Server connection testing |
| `SmsSetupDelegate.kt` | SMS default app setup |
| `SyncDelegate.kt` | Initial sync progress |

## Architecture

```
SetupViewModel
├── ServerConnectionDelegate
│   ├── testConnection()
│   └── saveServerConfig()
├── PermissionsDelegate
│   ├── checkPermissions()
│   └── requestPermissions()
├── SmsSetupDelegate
│   ├── checkDefaultSmsApp()
│   └── requestDefaultSmsApp()
├── SyncDelegate
│   ├── startInitialSync()
│   └── syncProgress StateFlow
├── MlModelDelegate
│   └── downloadModels()
└── AutoResponderDelegate
    └── saveAutoResponderConfig()
```

## Required Patterns

### Server Connection Delegate

```kotlin
class ServerConnectionDelegate @Inject constructor(
    private val api: BothBubblesApi,
    private val serverPreferences: ServerPreferences
) {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState = _connectionState.asStateFlow()

    suspend fun testConnection(url: String, password: String): Boolean {
        _connectionState.value = ConnectionState.Testing

        return try {
            val response = api.getServerInfo()
            if (response.isSuccessful) {
                serverPreferences.saveCredentials(url, password)
                _connectionState.value = ConnectionState.Success
                true
            } else {
                _connectionState.value = ConnectionState.Error("Invalid response")
                false
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            false
        }
    }
}
```

## Best Practices

1. Keep delegates focused on single responsibility
2. Use StateFlow for state exposure
3. Handle errors gracefully
4. Provide clear error messages
5. Save progress incrementally
