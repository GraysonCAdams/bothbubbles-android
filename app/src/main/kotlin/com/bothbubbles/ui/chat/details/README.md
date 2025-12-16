# Conversation Details

## Purpose

Conversation details screen showing participants, shared media, links, notification settings, and chat actions.

## Files

| File | Description |
|------|-------------|
| `ChatNotificationComponents.kt` | Notification setting UI components |
| `ChatNotificationDialogs.kt` | Notification setting dialogs |
| `ChatNotificationSettingsScreen.kt` | Per-chat notification settings |
| `ChatNotificationSettingsViewModel.kt` | ViewModel for notification settings |
| `ConversationDetailsActions.kt` | Action buttons (call, search, etc.) |
| `ConversationDetailsDangerZone.kt` | Destructive actions (delete, block) |
| `ConversationDetailsHeader.kt` | Chat avatar and name header |
| `ConversationDetailsMedia.kt` | Shared media grid |
| `ConversationDetailsOptions.kt` | Chat options (mute, pin, etc.) |
| `ConversationDetailsParticipants.kt` | Group participant list |
| `ConversationDetailsScreen.kt` | Main details screen |
| `ConversationDetailsUtils.kt` | Utility functions |
| `ConversationDetailsViewModel.kt` | ViewModel for details |
| `LinksScreen.kt` | Shared links list |
| `LinksViewModel.kt` | ViewModel for links |
| `MediaGalleryScreen.kt` | Full media gallery |
| `MediaGalleryViewModel.kt` | ViewModel for gallery |
| `MediaLinksComponents.kt` | Media/links tab components |
| `MediaLinksScreen.kt` | Combined media/links view |
| `MediaLinksViewModel.kt` | ViewModel for media/links |
| `MediaThumbnail.kt` | Media thumbnail component |
| `PlacesScreen.kt` | Shared locations list |
| `PlacesViewModel.kt` | ViewModel for places |

## Architecture

```
Conversation Details:

ConversationDetailsScreen
├── ConversationDetailsHeader
│   └── Avatar, name, participant count
├── ConversationDetailsActions
│   └── Call, Video, Search, Mute buttons
├── ConversationDetailsOptions
│   └── Pin, Archive, Notification settings
├── ConversationDetailsMedia
│   └── Recent media grid → MediaGalleryScreen
├── MediaLinksScreen
│   ├── Media tab
│   └── Links tab → LinksScreen
├── ConversationDetailsParticipants (groups)
│   └── Participant list with contact actions
└── ConversationDetailsDangerZone
    └── Delete chat, Block sender
```

## Required Patterns

### Details Screen

```kotlin
@Composable
fun ConversationDetailsScreen(
    chatGuid: String,
    viewModel: ConversationDetailsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToMediaGallery: () -> Unit
) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val participants by viewModel.participants.collectAsStateWithLifecycle()
    val recentMedia by viewModel.recentMedia.collectAsStateWithLifecycle()

    LazyColumn {
        item { ConversationDetailsHeader(chat) }
        item { ConversationDetailsActions(chat, viewModel::onCall) }
        item { ConversationDetailsOptions(chat, viewModel::togglePin) }
        item {
            ConversationDetailsMedia(
                recentMedia,
                onViewAll = onNavigateToMediaGallery
            )
        }
        // ...
    }
}
```

## Best Practices

1. Load media thumbnails lazily
2. Support pull-to-refresh
3. Handle large participant lists efficiently
4. Confirm destructive actions
5. Navigate to sub-screens for full views
