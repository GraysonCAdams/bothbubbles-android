# Data Models

## Purpose

Shared data models used across the data layer. These models represent business concepts that don't directly map to database entities or API DTOs.

## Files

| File | Description |
|------|-------------|
| `AttachmentQuality.kt` | Enum for image/video quality presets (Original, High, Medium, Low) |
| `PendingAttachmentInput.kt` | Input model for attachments being sent |

## Architecture

```
Data Flow:
API DTO → Repository → Domain Model → UI

Database Entity → Repository → Domain Model → UI
```

## Required Patterns

### Model Definition

```kotlin
data class PendingAttachmentInput(
    val uri: Uri,
    val caption: String? = null,
    val mimeType: String? = null,
    val name: String? = null,
    val size: Long? = null
)
```

### When to Use Data Models

Use data models when:
- The model is shared between multiple layers
- The model represents a business concept (not persistence or API concerns)
- You need to decouple from entity/DTO structures

### Key Models

#### PendingAttachmentInput

Used throughout the send flow for attachments:

```kotlin
// In ChatViewModel
val pendingAttachments: StateFlow<List<PendingAttachmentInput>>

// In ChatSendDelegate
fun sendMessage(attachments: List<PendingAttachmentInput>)

// In PendingMessageRepository
fun queueMessage(attachments: List<PendingAttachmentInput>)
```

#### AttachmentQuality

Quality presets for media compression:

```kotlin
enum class AttachmentQuality {
    ORIGINAL,  // No compression
    HIGH,      // Minimal compression
    MEDIUM,    // Balanced
    LOW        // Maximum compression
}
```

## Best Practices

1. Keep models as pure data classes
2. Use nullable types for optional fields with defaults
3. Document complex fields
4. Avoid business logic in models (move to repositories/services)
5. Prefer immutability
