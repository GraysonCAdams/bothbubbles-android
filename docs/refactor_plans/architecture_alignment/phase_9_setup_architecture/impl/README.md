# Phase 9: Setup Architecture — Implementation Plan

> **Status**: Planned
> **Goal**: Unify Setup flow state and use Hilt for dependency injection.

## Overview

The Setup flow is a critical path that is currently fragile. We will refactor `SetupViewModel` to use a single source of truth (`SetupUiState`) and proper dependency injection.

## Step-by-Step Implementation

### Step 1: Define `SetupUiState`

```kotlin
data class SetupUiState(
    val currentStep: SetupStep = SetupStep.WELCOME,
    val isSyncing: Boolean = false,
    val error: SetupError? = null,
    val serverUrl: String = "",
    val fcmToken: String? = null
)

enum class SetupStep {
    WELCOME, PERMISSIONS, QR_SCAN, SERVER_CONFIG, SYNC, COMPLETE
}
```

### Step 2: Refactor `SetupViewModel`

Convert to `@HiltViewModel`.

```kotlin
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val socketConnection: SocketConnection, // Use interface!
    // ... factories for delegates
) : ViewModel() {
    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState = _uiState.asStateFlow()
}
```

### Step 3: Refactor Delegates

*   **`QRCodeScannerDelegate`**: Handle camera permission and QR parsing. Emit `ScanResult` events via `SharedFlow` so UI can react idempotently.
*   **`ServerConnectionDelegate`**: Handle socket connection logic. Use `SocketConnection` interface and expose a `StateFlow<ServerConnectionState>` consumed by the ViewModel.
*   **`PermissionRequestDelegate`**: Encapsulate runtime permission prompts and emit `PermissionState` to the ViewModel instead of calling back into it.
*   **`SyncInitializationDelegate`**: Handle initial sync logic and expose `StateFlow<SyncStatus>` for progress + errors.

#### SyncInitializationDelegate Example

```kotlin
sealed class SyncStatus {
    data object Idle : SyncStatus()
    data class InProgress(val progress: Float, val stage: String) : SyncStatus()
    data class Completed(val messageCount: Int, val chatCount: Int) : SyncStatus()
    data class Failed(val error: AppError) : SyncStatus()
}

class SyncInitializationDelegate @Inject constructor(
    private val syncService: SyncService,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    suspend fun startInitialSync() {
        _status.value = SyncStatus.InProgress(0f, "Connecting...")
        try {
            val result = syncService.performInitialSync { progress, stage ->
                _status.value = SyncStatus.InProgress(progress, stage)
            }
            _status.value = SyncStatus.Completed(
                messageCount = result.messagesImported,
                chatCount = result.chatsImported
            )
        } catch (e: Exception) {
            _status.value = SyncStatus.Failed(NetworkError.Unknown(e.message))
        }
    }

    fun reset() {
        _status.value = SyncStatus.Idle
    }
}
```

### Step 4: State Machine Logic

Implement a clear state machine in `SetupViewModel` to transition between steps:

```kotlin
fun nextStep() {
    val current = _uiState.value.currentStep
    val next = when(current) {
        SetupStep.WELCOME -> SetupStep.PERMISSIONS
        SetupStep.PERMISSIONS -> SetupStep.QR_SCAN
        // ...
    }
    _uiState.update { it.copy(currentStep = next) }
}
```

## Work Breakdown Checklist

| Item | Description | Owner | Status |
|------|-------------|-------|--------|
| SET-01 | `SetupViewModel` converted to `SetupUiState` + injected delegates | _Unassigned_ | ☐ |
| SET-02 | `QRCodeScannerDelegate` emits `SharedFlow<ScanResult>` and removes callbacks | _Unassigned_ | ☐ |
| SET-03 | `ServerConnectionDelegate` consumes `SocketConnection` interface and exposes `StateFlow` | _Unassigned_ | ☐ |
| SET-04 | `PermissionRequestDelegate` migrated to DI + `StateFlow<PermissionState>` | _Unassigned_ | ☐ |
| SET-05 | `SyncInitializationDelegate` exposes `StateFlow<SyncStatus>` and DI hooks | _Unassigned_ | ☐ |
| SET-06 | Setup state machine implemented with exhaustive transitions + error handling | _Unassigned_ | ☐ |

## Verification

*   **Manual Test**: Walk through the entire setup flow (fresh install).
*   **Edge Cases**: Test network failure during server config, denied permissions, invalid QR codes.
