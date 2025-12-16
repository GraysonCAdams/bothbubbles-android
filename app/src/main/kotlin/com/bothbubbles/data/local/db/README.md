# Room Database

## Purpose

Room database implementation for BothBubbles. Stores messages, chats, handles (contacts), attachments, and various metadata.

## Files

| File | Description |
|------|-------------|
| `BothBubblesDatabase.kt` | Room database definition with all entities and DAOs. Current version: 37. |
| `DatabaseMigrations.kt` | Migration definitions for schema changes between versions. |
| `QueryFilters.kt` | Reusable query filter utilities. |

## Architecture

```
db/
├── BothBubblesDatabase.kt   # @Database definition
├── DatabaseMigrations.kt    # Migration objects
├── QueryFilters.kt          # Query utilities
├── dao/                     # Data Access Objects
└── entity/                  # Entity classes
```

## Required Patterns

### Adding New Entities

1. Create entity class in `entity/` with `@Entity` annotation
2. Add entity to `@Database(entities = [...])` in `BothBubblesDatabase.kt`
3. Create DAO interface in `dao/`
4. Add abstract DAO getter in `BothBubblesDatabase`
5. Increment database version
6. Add migration in `DatabaseMigrations.kt`
7. Provide DAO in `DatabaseModule.kt`

### Entity Naming

- Entity classes: `*Entity` (e.g., `MessageEntity`, `ChatEntity`)
- Table names: snake_case (e.g., `messages`, `pending_messages`)
- Column names: snake_case (e.g., `chat_guid`, `date_created`)

### Migrations

Always provide migrations for schema changes:

```kotlin
val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE messages ADD COLUMN new_column TEXT")
    }
}
```

### DAO Organization

Split large DAOs by operation type:
- `ChatDao` - Basic CRUD
- `ChatQueryDao` - Complex queries
- `ChatUpdateDao` - Update operations
- `ChatDeleteDao` - Delete operations

## Database Entities

| Entity | Purpose |
|--------|---------|
| `ChatEntity` | Conversations (iMessage and SMS) |
| `MessageEntity` | Individual messages |
| `HandleEntity` | Contact handles (phone/email) |
| `AttachmentEntity` | Message attachments |
| `PendingMessageEntity` | Unsent messages queue |
| `ScheduledMessageEntity` | Scheduled messages |
| `UnifiedChatGroupEntity` | iMessage/SMS conversation merging |

## Schema Export

Schema JSON files are exported to `app/schemas/` for migration testing. These should be committed to version control.
