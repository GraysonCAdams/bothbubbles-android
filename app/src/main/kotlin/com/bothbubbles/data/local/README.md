# Local Storage

## Purpose

Local storage layer containing Room database and DataStore preferences. Provides persistent storage for messages, chats, handles, attachments, and user settings.

## Architecture

```
local/
├── db/           # Room database
│   ├── dao/      # Data Access Objects
│   └── entity/   # Database entities
└── prefs/        # DataStore preferences
```

## Required Patterns

### Database Access

Always use DAOs through repositories, never directly from ViewModels:

```kotlin
// GOOD - Via repository
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao
) {
    fun getMessages(chatGuid: String) = messageDao.getMessagesForChat(chatGuid)
}

// BAD - Direct DAO access from ViewModel
class ChatViewModel @Inject constructor(
    private val messageDao: MessageDao  // Don't do this
)
```

### Reactive Data

Use `Flow` for all database queries that need to observe changes:

```kotlin
@Query("SELECT * FROM messages WHERE chat_guid = :chatGuid")
fun getMessagesForChat(chatGuid: String): Flow<List<MessageEntity>>
```

### Preferences

Use typed DataStore preferences with default values:

```kotlin
val serverUrl: Flow<String> = dataStore.data.map { prefs ->
    prefs[SERVER_URL_KEY] ?: ""
}
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `db/` | Room database, migrations, DAOs, entities |
| `prefs/` | DataStore preferences for user settings |
