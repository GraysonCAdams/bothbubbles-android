# API Data Transfer Objects (DTOs)

## Purpose

DTOs for API requests and responses. These classes map directly to JSON structures sent to/from the BlueBubbles server.

## Files

| File | Description |
|------|-------------|
| `ApiDtos.kt` | Response DTOs from the server |
| `RequestDtos.kt` | Request DTOs sent to the server |

## Architecture

```
API Layer:
JSON ←→ Moshi ←→ DTO ←→ Repository ←→ Domain Model
```

## Required Patterns

### Response DTOs

```kotlin
@JsonClass(generateAdapter = true)
data class MessageResponse(
    @Json(name = "status") val status: Int,
    @Json(name = "message") val message: String,
    @Json(name = "data") val data: MessageDto?
)

@JsonClass(generateAdapter = true)
data class MessageDto(
    @Json(name = "guid") val guid: String,
    @Json(name = "text") val text: String?,
    @Json(name = "dateCreated") val dateCreated: Long,
    @Json(name = "isFromMe") val isFromMe: Boolean
)
```

### Request DTOs

```kotlin
@JsonClass(generateAdapter = true)
data class SendMessageRequest(
    @Json(name = "chatGuid") val chatGuid: String,
    @Json(name = "message") val message: String,
    @Json(name = "method") val method: String = "private-api"
)
```

### Naming Conventions

- DTOs: `*Dto`, `*Request`, `*Response`
- Use `@Json(name = "...")` for JSON field mapping
- Use `@JsonClass(generateAdapter = true)` for Moshi code generation

### Mapping to Domain Models

Transform DTOs to domain models in repositories:

```kotlin
fun MessageDto.toEntity() = MessageEntity(
    guid = guid,
    text = text,
    date = dateCreated,
    isFromMe = isFromMe
)
```

## Best Practices

1. Keep DTOs as pure data classes
2. Use nullable types for optional fields
3. Always use `@Json` for explicit field names
4. Transform to domain models as early as possible
5. Don't leak DTOs to UI layer
6. Document non-obvious fields
