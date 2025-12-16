# UI Utilities

## Purpose

UI-specific utility classes and extensions.

## Files

| File | Description |
|------|-------------|
| `StableCollections.kt` | Stable wrappers for collections (Compose performance) |

## Required Patterns

### Stable Collections

```kotlin
/**
 * Wrapper for lists to make them stable for Compose.
 * Prevents unnecessary recompositions when list content hasn't changed.
 */
@Immutable
data class StableList<T>(val items: List<T>) {
    operator fun get(index: Int): T = items[index]
    val size: Int get() = items.size
    fun isEmpty(): Boolean = items.isEmpty()
    fun isNotEmpty(): Boolean = items.isNotEmpty()
}

fun <T> List<T>.toStable() = StableList(this)

/**
 * Wrapper for maps to make them stable for Compose.
 */
@Immutable
data class StableMap<K, V>(val map: Map<K, V>) {
    operator fun get(key: K): V? = map[key]
    val size: Int get() = map.size
    fun isEmpty(): Boolean = map.isEmpty()
}

fun <K, V> Map<K, V>.toStable() = StableMap(this)
```

### Usage

```kotlin
@Composable
fun MessageList(
    messages: StableList<MessageUi>  // More stable than List<MessageUi>
) {
    LazyColumn {
        items(messages.size) { index ->
            MessageBubble(message = messages[index])
        }
    }
}

// In ViewModel
val messages: StateFlow<StableList<MessageUi>> = _messages.map { it.toStable() }
```

## Best Practices

1. Use stable wrappers for large collections
2. Mark data classes with @Immutable when appropriate
3. Use derivedStateOf for derived values
4. Avoid unnecessary allocations in compositions
