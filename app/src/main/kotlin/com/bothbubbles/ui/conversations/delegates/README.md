# Conversations ViewModel Delegates

## Purpose

Delegate classes for ConversationsViewModel following the same pattern as ChatViewModel.

## Files

| File | Description |
|------|-------------|
| `ConversationActionsDelegate.kt` | Pin, mute, snooze, archive, delete actions |
| `ConversationLoadingDelegate.kt` | Data loading and pagination |
| `ConversationObserverDelegate.kt` | Database/socket change observers |
| `UnifiedGroupMappingDelegate.kt` | iMessage/SMS conversation merging |

## Architecture

```
ConversationsViewModel
├── ConversationLoadingDelegate
│   ├── loadConversations()
│   ├── loadMore()
│   └── refresh()
├── ConversationActionsDelegate
│   ├── pin/unpin()
│   ├── mute/unmute()
│   ├── snooze()
│   ├── archive()
│   └── delete()
├── ConversationObserverDelegate
│   ├── Observe chat changes
│   ├── Observe message updates
│   └── Combine 12+ flows using array syntax
└── UnifiedGroupMappingDelegate
    └── Merge iMessage/SMS counterparts
```

## Required Patterns

### Observer Delegate with Many Flows

```kotlin
class ConversationObserverDelegate @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    // ... many DAOs
) {
    fun initialize(scope: CoroutineScope) {
        scope.launch {
            // Use array-form combine for 6+ flows
            combine(
                flow1, flow2, flow3, flow4, flow5, flow6, flow7, flow8
            ) { values: Array<Any?> ->
                @Suppress("UNCHECKED_CAST")
                val chats = values[0] as? List<Chat> ?: emptyList()
                val messages = values[1] as? Map<String, Message> ?: emptyMap()
                // ... use safe casts with defaults
                buildConversationList(chats, messages, ...)
            }.collect { conversations ->
                _conversations.value = conversations
            }
        }
    }
}
```

### Unified Group Mapping

```kotlin
class UnifiedGroupMappingDelegate @Inject constructor(
    private val unifiedGroupDao: UnifiedChatGroupDao
) {
    suspend fun mergeConversations(
        conversations: List<Conversation>
    ): List<Conversation> {
        val groups = unifiedGroupDao.getAllGroups()

        // Merge iMessage and SMS conversations that belong to same group
        return conversations.groupBy { conv ->
            groups.find { it.containsChat(conv.guid) }?.groupId ?: conv.guid
        }.map { (_, groupConvs) ->
            mergeGroup(groupConvs)
        }
    }
}
```

## Best Practices

1. Use array-form `combine` for 6+ flows
2. Use safe casts (`as?`) with defaults in combine
3. Handle null values during StateFlow initialization
4. Keep delegate responsibilities focused
5. Unit test delegates independently
