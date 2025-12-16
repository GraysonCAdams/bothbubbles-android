# Navigation

## Purpose

Compose Navigation setup with type-safe routes using kotlinx.serialization.

## Files

| File | Description |
|------|-------------|
| `ChatNavigation.kt` | Chat-related navigation helpers |
| `NavHost.kt` | Main NavHost with all routes |
| `Screen.kt` | Screen route definitions |
| `SettingsNavigation.kt` | Settings navigation helpers |
| `SetupShareNavigation.kt` | Setup and share navigation |

## Architecture

```
Navigation Structure:

NavHost
├── Conversations (start)
├── Chat/{chatGuid}
│   └── ChatDetails/{chatGuid}
│       ├── MediaGallery
│       ├── Links
│       └── Places
├── NewChat
├── NewGroup
│   └── GroupSetup
├── Settings
│   ├── Server
│   ├── Notifications
│   ├── SMS
│   ├── Export
│   └── About
├── Setup (initial)
└── Share (external share intent)
```

## Required Patterns

### Route Definition

```kotlin
// Using kotlinx.serialization for type-safe routes
@Serializable
sealed interface Screen {
    @Serializable
    data object Conversations : Screen

    @Serializable
    data class Chat(val chatGuid: String) : Screen

    @Serializable
    data class ChatDetails(val chatGuid: String) : Screen

    @Serializable
    data object Settings : Screen

    @Serializable
    data object Setup : Screen
}
```

### NavHost Setup

```kotlin
@Composable
fun BothBubblesNavHost(
    navController: NavHostController,
    startDestination: Screen
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable<Screen.Conversations> {
            ConversationsScreen(
                onNavigateToChat = { guid ->
                    navController.navigate(Screen.Chat(guid))
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings)
                }
            )
        }

        composable<Screen.Chat> { backStackEntry ->
            val chat: Screen.Chat = backStackEntry.toRoute()
            ChatScreen(
                chatGuid = chat.chatGuid,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetails = {
                    navController.navigate(Screen.ChatDetails(chat.chatGuid))
                }
            )
        }

        // ... other routes
    }
}
```

### Navigation Helpers

```kotlin
// Extension for safe navigation
fun NavController.navigateToChat(chatGuid: String) {
    navigate(Screen.Chat(chatGuid)) {
        launchSingleTop = true
    }
}

// Deep link handling
fun NavController.handleDeepLink(intent: Intent) {
    val chatGuid = intent.getStringExtra("chatGuid")
    if (chatGuid != null) {
        navigateToChat(chatGuid)
    }
}
```

## Best Practices

1. Use type-safe routes (kotlinx.serialization)
2. Handle deep links properly
3. Use `launchSingleTop` to avoid duplicates
4. Clear back stack appropriately
5. Handle configuration changes
