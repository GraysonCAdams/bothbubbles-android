# Android Auto Integration

## Purpose

Provides messaging functionality for Android Auto. Allows users to view and send messages while driving using voice commands and the car display.

## Files

| File | Description |
|------|-------------|
| `AutoAudioPlayer.kt` | Audio playback for message notifications in car |
| `AutoTextToSpeech.kt` | TTS for reading messages aloud |
| `AutoUtils.kt` | Utility functions for Android Auto |
| `BothBubblesCarAppService.kt` | Main Car App Service entry point |
| `ComposeContactsContent.kt` | Contact selection screen for composing |
| `ComposeMessageScreen.kt` | Message composition screen |
| `ConversationDetailScreen.kt` | Individual conversation view |
| `ConversationListContent.kt` | Conversation list content |
| `ConversationListScreen.kt` | Main conversation list screen |
| `MessagingRootScreen.kt` | Root navigation for messaging |
| `VoiceReplyScreen.kt` | Voice reply interface |
| `VoiceSearchScreen.kt` | Voice search for contacts |

## Architecture

```
Android Auto Flow:

BothBubblesCarAppService
└── MessagingRootScreen
    ├── ConversationListScreen
    │   └── ConversationDetailScreen
    │       └── VoiceReplyScreen
    └── ComposeMessageScreen
        ├── VoiceSearchScreen
        └── ComposeContactsContent
```

## Required Patterns

### Car App Service

```kotlin
class BothBubblesCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return MessagingSession()
    }
}
```

### Screen Templates

Use Car App Library templates:

```kotlin
class ConversationListScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        return ListTemplate.Builder()
            .setTitle("Messages")
            .setSingleList(buildConversationList())
            .build()
    }
}
```

### Voice Input

```kotlin
// Trigger voice input
val voiceIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
carContext.startActivity(voiceIntent)

// Handle result in Activity
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    val spokenText = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
}
```

## Best Practices

1. Keep UI simple - limited screen real estate in cars
2. Minimize driver distraction - use voice for input
3. Use large touch targets
4. Follow Android Auto design guidelines
5. Test on actual car head units when possible
