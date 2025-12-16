# Repositories

## Purpose

Repository classes that abstract data sources and provide clean APIs to upper layers. Repositories handle data fetching, caching, and transformation.

## Files

| File | Description |
|------|-------------|
| `AttachmentRepository.kt` | Attachment download/upload operations |
| `AutoShareContactRepository.kt` | ETA auto-share contact management |
| `ChatParticipantOperations.kt` | Chat participant CRUD |
| `ChatRepository.kt` | Chat/conversation management |
| `ChatSyncOperations.kt` | Chat sync logic |
| `FaceTimeRepository.kt` | FaceTime link generation |
| `GifRepository.kt` | GIF search via Tenor API |
| `HandleRepository.kt` | Contact handle management |
| `LinkPreviewRepository.kt` | URL link preview fetching and caching |
| `MessageRepository.kt` | Message CRUD and queries |
| `PendingMessageRepository.kt` | Unsent message queue management |
| `QuickReplyTemplateRepository.kt` | Quick reply template CRUD |
| `ScheduledMessageRepository.kt` | Scheduled message management |
| `SmsContentProviderHelpers.kt` | SMS content provider utilities |
| `SmsImporter.kt` | Import SMS from Android content provider |
| `SmsMessageOperations.kt` | SMS message CRUD |
| `SmsRepository.kt` | SMS/MMS data access |
| `UnifiedChatGroupRepository.kt` | iMessage/SMS conversation merging |
| `UnifiedGroupOperations.kt` | Unified group operations |

## Architecture

```
Repository Pattern:

┌─────────────────────────────────────────────────────────────┐
│                     Repository                              │
│  - Abstracts data sources                                   │
│  - Handles caching strategy                                 │
│  - Transforms data between layers                           │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
       Local (Room)      Remote (API)    Memory Cache
```

## Required Patterns

### Repository Structure

```kotlin
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val api: BothBubblesApi,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    // Reactive queries (Flow)
    fun getMessagesForChat(chatGuid: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesForChat(chatGuid)

    // One-time operations (suspend)
    suspend fun sendMessage(request: SendMessageRequest): Result<MessageEntity>

    // Sync operations
    suspend fun syncMessages(chatGuid: String): Result<Int>
}
```

### Error Handling

Use `Result<T>` for operations that can fail:

```kotlin
suspend fun sendMessage(request: SendMessageRequest): Result<MessageEntity> {
    return try {
        val response = api.sendMessage(request)
        if (response.isSuccessful) {
            val entity = response.body()!!.data.toEntity()
            messageDao.upsert(entity)
            Result.success(entity)
        } else {
            Result.failure(NetworkError.ServerError(response.code()))
        }
    } catch (e: IOException) {
        Result.failure(NetworkError.NoConnection)
    }
}
```

### Caching Strategy

```kotlin
// Cache-first strategy
fun getChat(guid: String): Flow<ChatEntity?> {
    return messageDao.observeChat(guid)
        .onStart {
            // Optionally refresh from network
            refreshChat(guid)
        }
}

// Network-first with cache fallback
suspend fun syncChat(guid: String): Result<ChatEntity> {
    return try {
        val remote = api.getChat(guid)
        chatDao.upsert(remote.toEntity())
        Result.success(remote.toEntity())
    } catch (e: Exception) {
        // Return cached data on network failure
        chatDao.getChatByGuid(guid)?.let {
            Result.success(it)
        } ?: Result.failure(e)
    }
}
```

## Key Repositories

| Repository | Purpose |
|------------|---------|
| `MessageRepository` | Core message operations |
| `ChatRepository` | Conversation management |
| `PendingMessageRepository` | Offline message queue |
| `AttachmentRepository` | Media handling |
| `SmsRepository` | SMS/MMS integration |

## Best Practices

1. Inject DAOs and APIs via constructor
2. Use `@IoDispatcher` for IO operations
3. Return `Flow` for reactive data
4. Return `Result<T>` for fallible operations
5. Handle all exceptions within repository
6. Transform DTOs to entities/models
7. Keep repositories focused on single domain
