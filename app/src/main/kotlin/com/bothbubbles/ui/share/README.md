# Share Picker

## Purpose

Handle external share intents from other apps. Shows conversation picker for sharing content.

## Files

| File | Description |
|------|-------------|
| `SharePickerScreen.kt` | Conversation picker for sharing |
| `SharePickerViewModel.kt` | ViewModel for share picker |

## Architecture

```
Share Flow:

External App → Share Intent → MainActivity
                           → Navigate to SharePickerScreen
                           → User selects conversation
                           → Send shared content
                           → Navigate to conversation
```

## Required Patterns

### Share Picker Screen

```kotlin
@Composable
fun SharePickerScreen(
    sharedContent: SharedContent,
    viewModel: SharePickerViewModel = hiltViewModel(),
    onChatSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val recentChats by viewModel.recentChats.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()

    Column {
        // Shared content preview
        SharedContentPreview(sharedContent)

        // Search bar
        SearchBar(onSearch = viewModel::search)

        // Conversation list
        LazyColumn {
            items(searchResults.ifEmpty { recentChats }) { chat ->
                ConversationRow(
                    chat = chat,
                    onClick = {
                        viewModel.shareToChat(chat.guid, sharedContent)
                        onChatSelected(chat.guid)
                    }
                )
            }
        }
    }
}
```

### Shared Content Handling

```kotlin
sealed class SharedContent {
    data class Text(val text: String) : SharedContent()
    data class Image(val uri: Uri) : SharedContent()
    data class Video(val uri: Uri) : SharedContent()
    data class Multiple(val uris: List<Uri>) : SharedContent()
}

fun Intent.toSharedContent(): SharedContent? {
    return when (action) {
        Intent.ACTION_SEND -> {
            val text = getStringExtra(Intent.EXTRA_TEXT)
            val uri = getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            when {
                text != null -> SharedContent.Text(text)
                uri != null -> SharedContent.Image(uri)
                else -> null
            }
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            val uris = getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            uris?.let { SharedContent.Multiple(it) }
        }
        else -> null
    }
}
```

## Best Practices

1. Show preview of shared content
2. Support recent conversations at top
3. Allow searching all conversations
4. Handle multiple share types
5. Navigate to conversation after sharing
