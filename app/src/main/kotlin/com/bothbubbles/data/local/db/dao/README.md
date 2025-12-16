# Data Access Objects (DAOs)

## Purpose

Room DAOs provide type-safe database access. Each DAO handles operations for one or more related entities.

## Files

| File | Description |
|------|-------------|
| `AttachmentDao.kt` | Attachment CRUD and queries |
| `AutoRespondedSenderDao.kt` | Track auto-responded senders |
| `AutoShareContactDao.kt` | ETA auto-share contact management |
| `ChatCategorizationDao.kt` | Message categorization data |
| `ChatDao.kt` | Core chat CRUD operations |
| `ChatDeleteDao.kt` | Chat deletion operations |
| `ChatGroupDao.kt` | Group chat specific operations |
| `ChatNotificationDao.kt` | Per-chat notification settings |
| `ChatParticipantDao.kt` | Chat participant management |
| `ChatQueryDao.kt` | Complex chat queries |
| `ChatTransactionDao.kt` | Transactional chat operations |
| `ChatUpdateDao.kt` | Chat update operations |
| `HandleDao.kt` | Contact handle management |
| `IMessageCacheDao.kt` | iMessage availability cache |
| `LinkPreviewDao.kt` | URL link preview data |
| `MessageDao.kt` | Core message CRUD and queries |
| `PendingAttachmentDao.kt` | Pending attachment queue |
| `PendingMessageDao.kt` | Unsent message queue |
| `QuickReplyTemplateDao.kt` | Quick reply templates |
| `ScheduledMessageDao.kt` | Scheduled message management |
| `SeenMessageDao.kt` | Message read tracking |
| `SyncRangeDao.kt` | Sync progress tracking |
| `UnifiedChatGroupDao.kt` | iMessage/SMS conversation merging |
| `VerifiedCounterpartCheckDao.kt` | Counterpart verification cache |

## Architecture

DAOs are split by responsibility for better organization:

```
ChatDao (base)
├── ChatQueryDao     - Complex queries
├── ChatUpdateDao    - Update operations
├── ChatDeleteDao    - Delete operations
├── ChatGroupDao     - Group-specific ops
└── ChatNotificationDao - Notification settings
```

## Required Patterns

### Query Return Types

```kotlin
// Single query - returns nullable
@Query("SELECT * FROM chats WHERE guid = :guid")
suspend fun getChatByGuid(guid: String): ChatEntity?

// Reactive query - returns Flow
@Query("SELECT * FROM chats WHERE guid = :guid")
fun observeChatByGuid(guid: String): Flow<ChatEntity?>

// List query - returns Flow
@Query("SELECT * FROM messages WHERE chat_guid = :chatGuid ORDER BY date DESC")
fun getMessagesForChat(chatGuid: String): Flow<List<MessageEntity>>
```

### Insert Strategies

```kotlin
// Replace on conflict (upsert)
@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun upsert(message: MessageEntity)

// Ignore duplicates
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertIfNotExists(message: MessageEntity): Long
```

### Transactions

Use `@Transaction` for operations that need atomicity:

```kotlin
@Transaction
suspend fun insertChatWithHandles(chat: ChatEntity, handles: List<HandleEntity>) {
    insertChat(chat)
    insertHandles(handles)
    insertCrossRefs(/* ... */)
}
```

### Naming Conventions

- `get*` - Single query, returns nullable or non-null
- `observe*` - Returns `Flow` for reactive observation
- `getAll*` - Returns list
- `insert*` / `upsert*` - Insert operations
- `update*` - Update operations
- `delete*` - Delete operations
- `count*` - Count queries

## Best Practices

1. Keep DAOs focused on single entity or related entities
2. Use `Flow` for queries that UI needs to observe
3. Use `suspend` for one-time queries
4. Split large DAOs by operation type
5. Always provide DAOs in `DatabaseModule.kt`
