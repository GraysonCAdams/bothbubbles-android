# Message Paging

## Purpose

Efficient message pagination for chat screens. Loads messages in chunks as user scrolls.

## Files

| File | Description |
|------|-------------|
| `MessageDataSource.kt` | Abstract data source interface |
| `MessagePagingController.kt` | Coordinates pagination logic |
| `MessagePagingHelpers.kt` | Helper functions for paging |
| `MessagePagingState.kt` | Paging state models |
| `PagingConfig.kt` | Pagination configuration |
| `RoomMessageDataSource.kt` | Room-backed data source implementation |

## Architecture

```
Paging Flow:

User scrolls → MessagePagingController
            → Check if near boundary
            → Load more from MessageDataSource
            → Merge with existing messages
            → Update UI state
```

## Required Patterns

### Paging Configuration

```kotlin
data class PagingConfig(
    val pageSize: Int = 50,
    val prefetchDistance: Int = 10,
    val initialLoadSize: Int = 100,
    val maxSize: Int = 500  // Cap to prevent memory issues
)
```

### Paging Controller

```kotlin
class MessagePagingController(
    private val dataSource: MessageDataSource,
    private val config: PagingConfig
) {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _pagingState = MutableStateFlow(MessagePagingState())
    val pagingState: StateFlow<MessagePagingState> = _pagingState.asStateFlow()

    suspend fun loadInitial() {
        _pagingState.update { it.copy(isLoading = true) }
        val initial = dataSource.load(limit = config.initialLoadSize)
        _messages.value = initial
        _pagingState.update { it.copy(isLoading = false) }
    }

    suspend fun loadMore(direction: LoadDirection) {
        if (_pagingState.value.isLoading) return

        _pagingState.update { it.copy(isLoading = true) }
        val boundary = when (direction) {
            LoadDirection.PREPEND -> _messages.value.firstOrNull()?.date
            LoadDirection.APPEND -> _messages.value.lastOrNull()?.date
        }

        val more = dataSource.loadAround(boundary, config.pageSize, direction)
        _messages.update { current ->
            when (direction) {
                LoadDirection.PREPEND -> more + current
                LoadDirection.APPEND -> current + more
            }.takeLast(config.maxSize)
        }
        _pagingState.update { it.copy(isLoading = false) }
    }
}
```

### Data Source

```kotlin
class RoomMessageDataSource @Inject constructor(
    private val messageDao: MessageDao
) : MessageDataSource {

    override suspend fun load(limit: Int): List<Message> {
        return messageDao.getRecentMessages(chatGuid, limit)
    }

    override suspend fun loadAround(
        anchor: Long?,
        limit: Int,
        direction: LoadDirection
    ): List<Message> {
        return when (direction) {
            LoadDirection.PREPEND -> messageDao.getMessagesBefore(chatGuid, anchor, limit)
            LoadDirection.APPEND -> messageDao.getMessagesAfter(chatGuid, anchor, limit)
        }
    }
}
```

## Best Practices

1. Use bidirectional paging (scroll up and down)
2. Prefetch before reaching boundaries
3. Cap total messages to prevent memory issues
4. Handle loading states properly
5. Maintain scroll position during loads
6. Support jump-to-message (search results)
