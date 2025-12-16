# DataStore Preferences

## Purpose

Type-safe preferences using Jetpack DataStore. Stores user settings, feature flags, and configuration that persists across app restarts.

## Files

| File | Description |
|------|-------------|
| `AttachmentPreferences.kt` | Image/video quality and compression settings |
| `FeaturePreferences.kt` | Feature toggle preferences |
| `NotificationPreferences.kt` | Global notification settings |
| `ServerPreferences.kt` | BlueBubbles server connection settings |
| `SettingsDataStore.kt` | Main settings store combining all preferences |
| `SmsPreferences.kt` | SMS/MMS-specific settings |
| `SyncPreferences.kt` | Sync behavior settings |
| `UiPreferences.kt` | UI/theme preferences |

## Architecture

```
SettingsDataStore (main entry point)
├── ServerPreferences     - Server URL, password, connection mode
├── NotificationPreferences - Sound, vibration, bubbles
├── SmsPreferences        - Default SMS app settings
├── SyncPreferences       - Background sync settings
├── AttachmentPreferences - Quality/compression
├── UiPreferences         - Theme, display options
└── FeaturePreferences    - Feature toggles
```

## Required Patterns

### Reading Preferences

```kotlin
// As Flow (for Compose/reactive UI)
val serverUrl: Flow<String> = settingsDataStore.serverUrl

// As suspend function (one-time read)
val url = settingsDataStore.serverUrl.first()
```

### Writing Preferences

```kotlin
// Suspend function for writes
suspend fun setServerUrl(url: String)

// Usage
viewModelScope.launch {
    settingsDataStore.setServerUrl("https://example.com")
}
```

### Defining New Preferences

```kotlin
class MyPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val MY_SETTING_KEY = booleanPreferencesKey("my_setting")
    }

    val mySetting: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[MY_SETTING_KEY] ?: false  // Default value
    }

    suspend fun setMySetting(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[MY_SETTING_KEY] = value
        }
    }
}
```

### Preference Key Types

```kotlin
// Available key types
stringPreferencesKey("key")
intPreferencesKey("key")
longPreferencesKey("key")
floatPreferencesKey("key")
booleanPreferencesKey("key")
stringSetPreferencesKey("key")
```

## Best Practices

1. Always provide default values in `map` transform
2. Group related preferences in dedicated classes
3. Use descriptive key names
4. Expose `Flow` for reactive reads
5. Use suspend functions for writes
6. Inject preferences through Hilt
7. Keep `SettingsDataStore` as the main entry point

## Usage in ViewModels

```kotlin
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    val serverUrl = settingsDataStore.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun updateServerUrl(url: String) {
        viewModelScope.launch {
            settingsDataStore.setServerUrl(url)
        }
    }
}
```
