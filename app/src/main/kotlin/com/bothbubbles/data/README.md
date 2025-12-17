# Data Layer

## Purpose

The data layer provides data access abstractions following the Repository pattern. It encapsulates all data sources (local database, remote API, preferences) and exposes clean APIs to upper layers.

## Files

| File | Description |
|------|-------------|
| `ServerCapabilities.kt` | Model representing BlueBubbles server capabilities based on macOS version. Determines feature availability (edit/unsend, FindMy, etc.). |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Repositories (repository/)               │
│  Clean API for data operations - abstracts data sources     │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│  Local (local/) │ │ Remote (remote/)│ │   Abstractions  │
│  Room + Prefs   │ │ Retrofit API    │ │   Interfaces    │
└─────────────────┘ └─────────────────┘ └─────────────────┘
```

## Mandatory Rules

### Repository Pattern

Repositories should:
- Abstract data source details from consumers
- Handle data transformation between layers
- Expose `Flow` for reactive data
- Use `Result<T>` for fallible operations

```kotlin
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val api: BothBubblesApi
) {
    fun getMessages(chatGuid: String): Flow<List<MessageEntity>>
    suspend fun sendMessage(message: Message): Result<Message>
}
```

### Data Flow Direction

- Data layer should NOT depend on Services layer
- Services layer can depend on Data layer
- UI layer can depend on both

## Sub-packages

| Package | Purpose |
|---------|---------|
| `local/` | Local storage - Room database, DataStore preferences |
| `remote/` | Remote data - Retrofit API, DTOs |
| `repository/` | Repository implementations |
| `model/` | Shared data models |
| `abstractions/` | Interfaces for testability |

## Key Classes

- `ServerCapabilities` - Feature flags based on server macOS version
- Repositories in `repository/` - Data access layer
- DAOs in `local/db/dao/` - Database access objects
- Entities in `local/db/entity/` - Database entities
