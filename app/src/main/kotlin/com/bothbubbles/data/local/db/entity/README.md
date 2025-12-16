# Database Entities

## Purpose

Room entity classes representing database tables. Each entity maps to a table and defines its schema.

## Files

| File | Description |
|------|-------------|
| `AttachmentEntity.kt` | Message attachments (images, videos, files) |
| `AutoRespondedSenderEntity.kt` | Senders who received auto-responses |
| `AutoShareContactEntity.kt` | Contacts for ETA auto-sharing |
| `ChatEntity.kt` | Conversations (iMessage and SMS) |
| `ChatHandleCrossRef.kt` | Many-to-many chat↔handle relationship |
| `HandleEntity.kt` | Contact handles (phone numbers, emails) |
| `IMessageAvailabilityCacheEntity.kt` | Cache of iMessage availability checks |
| `LinkPreviewEntity.kt` | URL link preview metadata |
| `MessageEntity.kt` | Individual messages |
| `MessageSourceResolver.kt` | Utility for determining message source |
| `PendingAttachmentEntity.kt` | Attachments queued for sending |
| `PendingMessageEntity.kt` | Messages queued for sending |
| `QuickReplyTemplateEntity.kt` | User-defined quick reply templates |
| `ReactionClassifier.kt` | Utility for classifying tapback reactions |
| `ScheduledMessageEntity.kt` | Messages scheduled for future delivery |
| `SeenMessageEntity.kt` | Tracks which messages have been seen |
| `SyncRangeEntity.kt` | Tracks sync progress per chat |
| `TransferState.kt` | Attachment transfer state enum |
| `UnifiedChatGroupEntity.kt` | Groups iMessage/SMS conversations together |
| `VerifiedCounterpartCheckEntity.kt` | Cache of counterpart verification checks |

## Architecture

```
Core Entities:
├── ChatEntity          - Conversations
├── MessageEntity       - Messages
├── HandleEntity        - Contacts
└── AttachmentEntity    - Attachments

Supporting Entities:
├── ChatHandleCrossRef  - Chat ↔ Handle relationship
├── LinkPreviewEntity   - URL previews
└── UnifiedChatGroupEntity - iMessage/SMS merging

Queue Entities:
├── PendingMessageEntity    - Unsent messages
├── PendingAttachmentEntity - Unsent attachments
└── ScheduledMessageEntity  - Scheduled messages
```

## Required Patterns

### Entity Definition

```kotlin
@Entity(
    tableName = "messages",
    indices = [
        Index("chat_guid"),
        Index("date")
    ],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["guid"],
            childColumns = ["chat_guid"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "guid")
    val guid: String,

    @ColumnInfo(name = "chat_guid")
    val chatGuid: String,

    @ColumnInfo(name = "text")
    val text: String?,

    @ColumnInfo(name = "date")
    val date: Long
)
```

### Naming Conventions

- Class names: `*Entity` suffix
- Table names: snake_case plural (e.g., `messages`, `pending_messages`)
- Column names: snake_case (e.g., `chat_guid`, `date_created`)
- Always use `@ColumnInfo` for explicit column names

### Primary Keys

- Use meaningful IDs where possible (e.g., `guid` from server)
- Use `@PrimaryKey(autoGenerate = true)` for local-only entities

### Relationships

Use cross-reference tables for many-to-many:

```kotlin
@Entity(
    tableName = "chat_handle_cross_ref",
    primaryKeys = ["chat_guid", "handle_id"]
)
data class ChatHandleCrossRef(
    @ColumnInfo(name = "chat_guid") val chatGuid: String,
    @ColumnInfo(name = "handle_id") val handleId: Long
)
```

### Indices

Add indices for frequently queried columns:

```kotlin
@Entity(
    tableName = "messages",
    indices = [
        Index("chat_guid"),      // Foreign key queries
        Index("date"),           // Sorting
        Index("is_from_me")      // Filtering
    ]
)
```

## Best Practices

1. Keep entities as pure data classes
2. Use nullable types for optional fields
3. Add indices for query performance
4. Use foreign keys for referential integrity
5. Document complex fields with KDoc
