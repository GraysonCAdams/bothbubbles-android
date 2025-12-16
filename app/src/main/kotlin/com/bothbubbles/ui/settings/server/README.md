# Server Settings

## Purpose

BlueBubbles server connection configuration.

## Files

| File | Description |
|------|-------------|
| `ServerSettingsScreen.kt` | Server URL, password, connection settings |
| `ServerSettingsViewModel.kt` | ViewModel for server settings |

## Usage with SettingsPanel

```kotlin
// ServerSettingsContent can be embedded in SettingsPanel
@Composable
fun ServerSettingsContent(
    viewModel: ServerSettingsViewModel = hiltViewModel()
) {
    // Content without Scaffold for panel embedding
}
```
