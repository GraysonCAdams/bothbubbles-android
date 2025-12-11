# Incremental Message Updates Implementation Plan

## Problem Statement

Currently, when **any** message changes (reaction added, delivery status, read receipt), the entire message list is rebuilt:

```
Database Change → Room Flow emits List<MessageEntity> →
Transform ALL to List<MessageUiModel> → Replace entire list in ChatUiState →
Compose diffs entire list → Recomposition for all visible items
```

This causes UI jank during scrolling when updates arrive (common with reactions, delivery statuses).

## Goal

Implement Signal-style incremental updates:

```
Database Change → Room Flow emits List<MessageEntity> →
Compute DELTA (what actually changed) →
Update ONLY changed MessageUiModel objects →
Reuse unchanged object references →
Compose sees same objects → Skips recomposition for unchanged items
```

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      ChatViewModel                               │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              MessageCache (NEW)                          │   │
│  │  Map<String, CachedMessage>                              │   │
│  │    - guid → MessageUiModel + source hash                 │   │
│  │    - Preserves object identity for unchanged messages    │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                   │
│                              ▼                                   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              DeltaComputer (NEW)                         │   │
│  │  - Compares incoming entities with cached               │   │
│  │  - Returns: Added, Removed, Changed, Unchanged          │   │
│  │  - Uses content hash for efficient comparison           │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                   │
│                              ▼                                   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              List Builder                                │   │
│  │  - Builds sorted list from cache                        │   │
│  │  - Reuses unchanged MessageUiModel instances            │   │
│  │  - Only creates new objects for changed messages        │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

## Implementation Steps

### Phase 1: Create MessageCache Infrastructure

**File: `app/src/main/kotlin/com/bothbubbles/ui/chat/MessageCache.kt`** (NEW)

```kotlin
/**
 * Cached message with content hash for change detection.
 * The hash includes all fields that affect UI rendering.
 */
data class CachedMessage(
    val model: MessageUiModel,
    val sourceHash: Int,           // Hash of MessageEntity fields
    val reactionsHash: Int,        // Hash of reaction GUIDs
    val attachmentsHash: Int       // Hash of attachment states
)

/**
 * Cache for MessageUiModel instances.
 * Preserves object identity for unchanged messages to enable
 * Compose to skip recomposition.
 */
class MessageCache {
    private val cache = mutableMapOf<String, CachedMessage>()

    /**
     * Update cache with new data, returning list with preserved references.
     * @return Pair of (updated list, changed GUIDs for logging)
     */
    fun updateAndBuild(
        entities: List<MessageEntity>,
        reactions: Map<String, List<MessageEntity>>,
        attachments: Map<String, List<AttachmentEntity>>,
        transformer: (MessageEntity) -> MessageUiModel
    ): Pair<List<MessageUiModel>, Set<String>>

    fun clear()
}
```

### Phase 2: Implement Content Hashing

Create efficient hash functions that capture what affects rendering:

```kotlin
// Hash MessageEntity fields that affect UI
fun MessageEntity.contentHash(): Int {
    var hash = guid.hashCode()
    hash = 31 * hash + (text?.hashCode() ?: 0)
    hash = 31 * hash + dateCreated.hashCode()
    hash = 31 * hash + (dateDelivered?.hashCode() ?: 0)
    hash = 31 * hash + (dateRead?.hashCode() ?: 0)
    hash = 31 * hash + error
    hash = 31 * hash + isFromMe.hashCode()
    // ... other UI-affecting fields
    return hash
}

// Hash reactions by their GUIDs (order doesn't matter for display)
fun List<MessageEntity>.reactionsHash(): Int {
    return this.map { it.guid }.toSet().hashCode()
}

// Hash attachments by GUID + localPath (download state matters)
fun List<AttachmentEntity>.attachmentsHash(): Int {
    return this.map { "${it.guid}:${it.localPath}" }.toSet().hashCode()
}
```

### Phase 3: Integrate into ChatViewModel.loadMessages()

**Modify: `ChatViewModel.kt` - loadMessages()**

```kotlin
// Add as class member
private val messageCache = MessageCache()

private fun loadMessages() {
    viewModelScope.launch {
        // ... existing flow setup ...

        .map { (messages, handleIdToName, addressToName, addressToAvatarPath) ->
            // Separate reactions (existing code)
            val iMessageReactions = messages.filter { it.isReaction }
            val regularMessages = messages.filter { !it.isReaction && !isSmsReaction(it) }

            // Group reactions and attachments (existing code)
            val reactionsByMessage = iMessageReactions.groupBy { it.associatedMessageGuid }
            val allAttachments = attachmentDao.getAttachmentsForMessages(...)

            // NEW: Use cache for incremental updates
            val (messageModels, changedGuids) = messageCache.updateAndBuild(
                entities = regularMessages,
                reactions = reactionsByMessage,
                attachments = allAttachments,
                transformer = { entity ->
                    entity.toUiModel(
                        reactions = reactionsByMessage[entity.guid].orEmpty(),
                        attachments = allAttachments[entity.guid].orEmpty(),
                        // ... other params
                    )
                }
            )

            // Log for debugging (can remove later)
            if (changedGuids.isNotEmpty()) {
                Log.d(TAG, "Incremental update: ${changedGuids.size} changed, ${messageModels.size - changedGuids.size} reused")
            }

            messageModels
        }
        .collect { messageModels ->
            _uiState.update { state ->
                state.copy(isLoading = false, messages = messageModels)
            }
        }
    }
}
```

### Phase 4: Handle Direct Socket Updates

Socket events already do surgical updates in `directlyAddMessageToUi()` and `directlyUpdateMessageInUi()`. We need to ensure cache consistency:

```kotlin
private suspend fun directlyAddMessageToUi(messageDto: MessageDto, eventChatGuid: String) {
    val messageModel = withContext(Dispatchers.Default) {
        // ... existing transformation code ...
    }

    // Update cache first
    messageCache.put(messageModel.guid, CachedMessage(
        model = messageModel,
        sourceHash = computeHash(messageDto),
        reactionsHash = 0,
        attachmentsHash = 0
    ))

    // Then update UI state (existing code)
    _uiState.update { state ->
        // ...
    }
}

private suspend fun directlyUpdateMessageInUi(messageDto: MessageDto, eventChatGuid: String) {
    _uiState.update { state ->
        // ... existing logic ...

        // Update cache with new model
        messageCache.update(messageDto.guid) { existing ->
            existing.copy(model = updatedMessage)
        }

        // ... rest of existing code ...
    }
}
```

### Phase 5: Add flowOn for Background Processing

Ensure heavy computation happens off main thread:

```kotlin
.map { /* heavy transformation */ }
.flowOn(Dispatchers.Default)  // Transform on computation thread
.collect { /* update state on main */ }
```

## Files to Modify

| File | Changes |
|------|---------|
| `MessageCache.kt` | NEW - Cache infrastructure |
| `ChatViewModel.kt` | Integrate cache in loadMessages(), update direct socket handlers |

## Testing Strategy

1. **Unit Tests for MessageCache**
   - Test that unchanged messages return same object reference
   - Test that changed messages return new objects
   - Test hash collision handling

2. **Integration Tests**
   - Send reaction → verify only that message recomposes
   - Delivery status update → verify only sender's message updates
   - New message → verify existing messages not recreated

3. **Performance Profiling**
   - Before/after comparison of frame times during scroll + updates
   - Object allocation tracking (should see fewer MessageUiModel allocations)

## Expected Improvements

| Scenario | Before | After |
|----------|--------|-------|
| Reaction added during scroll | Full list diff, all items recompose | Single item updates |
| Delivery status | Full list diff | Single item updates |
| Read receipt | Full list diff | Single item updates |
| New message while scrolling | Full list diff | Prepend only, existing items unchanged |

## Risks and Mitigations

1. **Cache Consistency**
   - Risk: Cache gets out of sync with Room
   - Mitigation: Always rebuild from Room data, cache only preserves object identity

2. **Memory Usage**
   - Risk: Large cache for long conversations
   - Mitigation: Cache is bounded by message limit; cleared on chat exit

3. **Hash Collisions**
   - Risk: Different content produces same hash
   - Mitigation: Use multiple hashes (source, reactions, attachments) combined

## Rollback Plan

If issues arise:
1. Remove `messageCache.updateAndBuild()` call
2. Revert to direct `regularMessages.map { it.toUiModel(...) }`
3. No database or API changes required
