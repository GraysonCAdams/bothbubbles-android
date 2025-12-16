# Message Sender Strategies

## Purpose

Strategy pattern implementations for sending messages via different channels (iMessage, SMS, MMS).

## Files

| File | Description |
|------|-------------|
| `IMessageSenderStrategy.kt` | Send via BlueBubbles server (iMessage) |
| `MessageSenderStrategy.kt` | Base strategy interface |
| `SmsSenderStrategy.kt` | Send via local Android SMS/MMS |

## Architecture

```
Strategy Selection:

MessageSendingService
         │
         ▼
    ┌────────────────────────────────────┐
    │     Select Strategy Based On:       │
    │  - Chat type (iMessage vs SMS)      │
    │  - Server connection status         │
    │  - User preference (send mode)      │
    │  - Fallback history                 │
    └────────────────────────────────────┘
              │
    ┌─────────┴─────────┐
    ▼                   ▼
IMessageSender      SmsSender
Strategy            Strategy
    │                   │
    ▼                   ▼
BlueBubbles        Android SMS
Server             Framework
```

## Required Patterns

### Strategy Interface

```kotlin
interface MessageSenderStrategy {
    suspend fun send(request: SendRequest): Result<MessageEntity>
    fun canHandle(chat: Chat, mode: SendMode): Boolean
    val priority: Int  // Higher = preferred
}

data class SendRequest(
    val chatGuid: String,
    val text: String,
    val attachments: List<PendingAttachmentInput>,
    val replyToGuid: String?,
    val effect: MessageEffect?
)
```

### iMessage Strategy

```kotlin
class IMessageSenderStrategy @Inject constructor(
    private val api: BothBubblesApi,
    private val attachmentRepository: AttachmentRepository
) : MessageSenderStrategy {

    override suspend fun send(request: SendRequest): Result<MessageEntity> {
        // Upload attachments first
        val uploadedAttachments = request.attachments.map { attachment ->
            attachmentRepository.upload(attachment)
        }

        // Send message via API
        val response = api.sendMessage(
            SendMessageRequest(
                chatGuid = request.chatGuid,
                message = request.text,
                attachmentGuids = uploadedAttachments.map { it.guid }
            )
        )

        return if (response.isSuccessful) {
            Result.success(response.body()!!.data.toEntity())
        } else {
            Result.failure(NetworkError.ServerError(response.code()))
        }
    }

    override fun canHandle(chat: Chat, mode: SendMode): Boolean {
        return mode == SendMode.IMESSAGE || chat.isIMessage
    }
}
```

### SMS Strategy

```kotlin
class SmsSenderStrategy @Inject constructor(
    private val smsSendService: SmsSendService,
    private val mmsSendService: MmsSendService
) : MessageSenderStrategy {

    override suspend fun send(request: SendRequest): Result<MessageEntity> {
        return if (request.attachments.isEmpty()) {
            smsSendService.send(request.chatGuid, request.text)
        } else {
            mmsSendService.send(request.chatGuid, request.text, request.attachments)
        }
    }

    override fun canHandle(chat: Chat, mode: SendMode): Boolean {
        return mode == SendMode.SMS || chat.isSms
    }
}
```

## Best Practices

1. Strategy selection should be deterministic
2. Handle fallback when primary strategy fails
3. Log strategy selection for debugging
4. Consider user preferences in selection
5. Upload attachments before message send
