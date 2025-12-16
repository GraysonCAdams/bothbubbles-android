# Setup Wizard

## Purpose

Initial app setup wizard for configuring server connection, permissions, SMS, and sync.

## Files

| File | Description |
|------|-------------|
| `SetupAutoResponderPage.kt` | Auto responder setup page |
| `SetupCategorizationPage.kt` | Message categorization setup |
| `SetupPermissionsPage.kt` | Permission request page |
| `SetupQrScanner.kt` | QR code scanner for server URL |
| `SetupScreen.kt` | Main setup wizard container |
| `SetupServerPage.kt` | Server connection page |
| `SetupSmsPage.kt` | SMS default app setup |
| `SetupSyncPage.kt` | Initial sync page |
| `SetupViewModel.kt` | Main setup ViewModel |
| `SetupWelcomePage.kt` | Welcome/intro page |

## Architecture

```
Setup Wizard Flow:

SetupWelcomePage → SetupServerPage → SetupPermissionsPage
                                   → SetupSmsPage (optional)
                                   → SetupCategorizationPage (optional)
                                   → SetupAutoResponderPage (optional)
                                   → SetupSyncPage → Complete!

SetupViewModel
├── PermissionsDelegate      - Handle permission requests
├── ServerConnectionDelegate - Server connection logic
├── SmsSetupDelegate         - SMS default app setup
├── SyncDelegate             - Initial sync progress
├── MlModelDelegate          - ML model download
└── AutoResponderDelegate    - Auto responder setup
```

## Required Patterns

### Setup Screen Container

```kotlin
@Composable
fun SetupScreen(
    viewModel: SetupViewModel = hiltViewModel(),
    onSetupComplete: () -> Unit
) {
    val currentPage by viewModel.currentPage.collectAsStateWithLifecycle()

    AnimatedContent(targetState = currentPage) { page ->
        when (page) {
            SetupPage.WELCOME -> SetupWelcomePage(onNext = viewModel::nextPage)
            SetupPage.SERVER -> SetupServerPage(
                onNext = viewModel::nextPage,
                onScanQr = { /* Navigate to scanner */ }
            )
            SetupPage.PERMISSIONS -> SetupPermissionsPage(
                onPermissionsGranted = viewModel::nextPage
            )
            SetupPage.SYNC -> SetupSyncPage(
                onComplete = {
                    viewModel.markSetupComplete()
                    onSetupComplete()
                }
            )
        }
    }
}
```

### Page Pattern

```kotlin
@Composable
fun SetupServerPage(
    viewModel: SetupViewModel = hiltViewModel(),
    onNext: () -> Unit
) {
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Connect to Server", style = MaterialTheme.typography.headlineMedium)

        TextField(
            value = serverUrl,
            onValueChange = viewModel::setServerUrl,
            label = { Text("Server URL") }
        )

        Button(
            onClick = { viewModel.testConnection() },
            enabled = serverUrl.isNotEmpty()
        ) {
            Text("Test Connection")
        }

        when (connectionState) {
            ConnectionState.SUCCESS -> {
                Text("Connected!", color = MaterialTheme.colorScheme.primary)
                Button(onClick = onNext) { Text("Continue") }
            }
            ConnectionState.ERROR -> Text("Connection failed", color = MaterialTheme.colorScheme.error)
            else -> {}
        }
    }
}
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `delegates/` | ViewModel delegates for setup |

## Best Practices

1. Allow skipping optional pages
2. Save progress (survive process death)
3. Handle back navigation properly
4. Show clear error messages
5. Test connection before proceeding
