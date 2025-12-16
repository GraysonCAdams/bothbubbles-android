# Settings Screens

## Purpose

Settings screens for configuring app behavior, server connection, notifications, SMS, and other features.

## Files

| File | Description |
|------|-------------|
| `EffectsSettingsScreen.kt` | Message effects settings |
| `EffectsSettingsViewModel.kt` | ViewModel for effects settings |
| `SettingsPanel.kt` | Panel overlay for settings (split-screen) |
| `SettingsPanelNavigator.kt` | Navigation helper for panel |
| `SettingsPanelPage.kt` | Panel page enum |
| `SettingsScreen.kt` | Main settings screen |
| `SettingsViewModel.kt` | Main settings ViewModel |

## Architecture

```
Settings Organization:

settings/
├── SettingsScreen.kt        - Main settings menu
├── SettingsPanel.kt         - Overlay panel variant
├── about/                   - About, licenses
├── archived/                - Archived chats
├── attachments/             - Image quality settings
├── autoresponder/           - Auto-responder settings
├── blocked/                 - Blocked contacts
├── categorization/          - Message categorization
├── components/              - Shared settings components
├── developer/               - Developer options
├── eta/                     - ETA sharing settings
├── export/                  - Message export
├── messages/                - Message display settings
├── notifications/           - Notification settings
├── server/                  - Server connection
├── sms/                     - SMS settings, backup
├── spam/                    - Spam settings
├── swipe/                   - Swipe action customization
├── sync/                    - Sync settings
└── templates/               - Quick reply templates
```

## Required Patterns

### Settings Screen

```kotlin
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigate: (SettingsDestination) -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item {
                SettingsCategory("Account") {
                    SettingsRow("Server", onClick = { onNavigate(SettingsDestination.Server) })
                    SettingsRow("Notifications", onClick = { onNavigate(SettingsDestination.Notifications) })
                }
            }
            item {
                SettingsCategory("Messages") {
                    SettingsRow("SMS Settings", onClick = { onNavigate(SettingsDestination.Sms) })
                    SettingsRow("Export", onClick = { onNavigate(SettingsDestination.Export) })
                }
            }
        }
    }
}
```

### Settings Panel (Overlay)

The `SettingsPanel` uses internal navigation with `AnimatedContent`:

```kotlin
@Composable
fun SettingsPanel(
    currentPage: SettingsPanelPage,
    onNavigate: (SettingsPanelPage) -> Unit,
    onDismiss: () -> Unit
) {
    AnimatedContent(targetState = currentPage) { page ->
        when (page) {
            SettingsPanelPage.Main -> SettingsContent(onNavigate)
            SettingsPanelPage.Server -> ServerSettingsContent()
            SettingsPanelPage.Notifications -> NotificationSettingsContent()
            // ...
        }
    }
}
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `about/` | About screen, open source licenses |
| `archived/` | Archived chats list |
| `attachments/` | Image/video quality settings |
| `autoresponder/` | Auto-responder configuration |
| `blocked/` | Blocked contacts management |
| `categorization/` | Message categorization settings |
| `components/` | Shared settings components |
| `developer/` | Developer options and logs |
| `eta/` | ETA sharing settings |
| `export/` | Message export UI |
| `messages/` | Message display preferences |
| `notifications/` | Notification preferences |
| `server/` | Server connection settings |
| `sms/` | SMS settings and backup |
| `spam/` | Spam detection settings |
| `swipe/` | Swipe action customization |
| `sync/` | Sync preferences |
| `templates/` | Quick reply templates |

## Best Practices

1. Group related settings
2. Use consistent row components
3. Show current values in rows
4. Handle permissions in relevant screens
5. Persist changes immediately
