# Chat Creator

## Purpose

Screens for creating new conversations (DM and group chats). Includes contact selection, search, and group setup.

## Files

| File | Description |
|------|-------------|
| `ChatCreatorComponents.kt` | Shared components for chat creation |
| `ChatCreatorFastScroller.kt` | Alphabet fast scroller |
| `ChatCreatorModels.kt` | Data models for chat creation |
| `ChatCreatorScreen.kt` | Main new chat screen (DM) |
| `ChatCreatorViewModel.kt` | ViewModel for DM creation |
| `GroupAlphabetFastScroller.kt` | Fast scroller for group contacts |
| `GroupContactListComponents.kt` | Group contact list components |
| `GroupCreatorScreen.kt` | Group chat creation screen |
| `GroupCreatorViewModel.kt` | ViewModel for group creation |
| `GroupParticipantComponents.kt` | Selected participant chips |
| `GroupSetupScreen.kt` | Group name and photo setup |
| `GroupSetupViewModel.kt` | ViewModel for group setup |

## Architecture

```
Chat Creation Flow:

ChatCreatorScreen (DM)
├── Search bar
├── Recent contacts
├── Contact list (alphabetical)
└── Start chat → Navigate to ChatScreen

Group Creation Flow:
GroupCreatorScreen
├── Search bar
├── Selected participants
├── Contact list (multi-select)
└── Continue → GroupSetupScreen
                ├── Group name input
                ├── Group photo picker
                └── Create → Navigate to ChatScreen
```

## Required Patterns

### Contact Selection

```kotlin
@Composable
fun ChatCreatorScreen(
    viewModel: ChatCreatorViewModel = hiltViewModel(),
    onNavigateToChat: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()

    Column {
        SearchBar(onSearch = viewModel::search)

        LazyColumn {
            items(searchResults.ifEmpty { contacts }) { contact ->
                ContactRow(
                    contact = contact,
                    onClick = {
                        viewModel.startChat(contact)
                            .onSuccess { chatGuid -> onNavigateToChat(chatGuid) }
                    }
                )
            }
        }
    }
}
```

### Group Creation

```kotlin
@Composable
fun GroupCreatorScreen(
    viewModel: GroupCreatorViewModel = hiltViewModel(),
    onNavigateToSetup: (List<Contact>) -> Unit
) {
    val selectedParticipants by viewModel.selectedParticipants.collectAsStateWithLifecycle()

    Column {
        // Selected participants chips
        FlowRow {
            selectedParticipants.forEach { contact ->
                ParticipantChip(contact, onRemove = { viewModel.removeParticipant(contact) })
            }
        }

        // Contact list with multi-select
        LazyColumn {
            items(contacts) { contact ->
                ContactRow(
                    contact = contact,
                    isSelected = contact in selectedParticipants,
                    onClick = { viewModel.toggleParticipant(contact) }
                )
            }
        }

        // Continue button
        Button(
            onClick = { onNavigateToSetup(selectedParticipants) },
            enabled = selectedParticipants.size >= 2
        ) {
            Text("Continue")
        }
    }
}
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `delegates/` | ViewModel delegates for chat creation |

## Best Practices

1. Support both phone number and email input
2. Validate addresses before starting chat
3. Handle iMessage availability checking
4. Support fast alphabet scrolling
5. Remember recent contacts
