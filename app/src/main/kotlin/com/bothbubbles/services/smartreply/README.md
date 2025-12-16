# Smart Reply Service

## Purpose

Generate contextual smart reply suggestions using ML Kit's Smart Reply API.

## Files

| File | Description |
|------|-------------|
| `SmartReplyService.kt` | Generate smart reply suggestions |

## Architecture

```
Smart Reply Flow:

Recent Messages → SmartReplyService
               → Build conversation for ML Kit
               → Call SmartReply.suggestReplies()
               → Filter/rank suggestions
               → Return top N suggestions
```

## Required Patterns

### Smart Reply Service

```kotlin
class SmartReplyService @Inject constructor() {
    private val smartReply = SmartReply.getClient()

    suspend fun getSuggestions(
        messages: List<Message>,
        limit: Int = 3
    ): List<String> = suspendCancellableCoroutine { cont ->
        val conversation = buildConversation(messages)

        smartReply.suggestReplies(conversation)
            .addOnSuccessListener { result ->
                if (result.status == SmartReplySuggestionResult.STATUS_SUCCESS) {
                    val suggestions = result.suggestions
                        .take(limit)
                        .map { it.text }
                    cont.resume(suggestions)
                } else {
                    cont.resume(emptyList())
                }
            }
            .addOnFailureListener { e ->
                cont.resume(emptyList())
            }
    }

    private fun buildConversation(messages: List<Message>): List<TextMessage> {
        return messages.takeLast(10).map { message ->
            if (message.isFromMe) {
                TextMessage.createForLocalUser(
                    message.text ?: "",
                    message.date
                )
            } else {
                TextMessage.createForRemoteUser(
                    message.text ?: "",
                    message.date,
                    message.senderHandle ?: "unknown"
                )
            }
        }
    }
}
```

### Integration with Composer

```kotlin
// In ChatComposerDelegate
private fun loadSmartReplies() {
    viewModelScope.launch {
        val recentMessages = messageRepository.getRecentMessages(chatGuid, limit = 10)
        val suggestions = smartReplyService.getSuggestions(recentMessages)
        _smartReplies.value = suggestions
    }
}
```

## Best Practices

1. Only show suggestions for recent conversations
2. Limit conversation context to last ~10 messages
3. Hide suggestions when user starts typing
4. Respect user preference to disable
5. Handle ML Kit failures gracefully
