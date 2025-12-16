# Wave 3E (Parallel): Scroll Logic Consolidation

**Prerequisites**:
- Read `00_shared_conventions.md` first
- Wave 2 (sequential ChatScreen cutover) must be complete

**Owned Files**: `ChatScrollHelper.kt`, `ChatScreenEffects.kt`, `ChatMessageList.kt`

---

## Objective

Audit and consolidate duplicate scroll-to-message logic to have a single owner for each scroll behavior.

---

## Tasks

### 1. Audit Scroll Logic Locations

Identify all scroll-related logic in:

**ChatMessageList.kt** (around lines 182-200):
- Scroll to search result
- Scroll to highlighted message
- Auto-scroll on new message

**ChatScreenEffects.kt** (around lines 71-142):
- Scroll to search result
- Scroll to highlighted message
- Scroll restoration

**ChatScrollHelper.kt**:
- `LoadMoreOnScroll` composable
- `ScrollPositionTracker` composable
- `AutoScrollOnNewMessage` composable (may be unused)

### 2. Determine Single Owner

| Scroll Behavior | Preferred Owner | Remove From |
|-----------------|-----------------|-------------|
| Scroll to search result | `ChatScreenEffects.kt` | `ChatMessageList.kt` |
| Scroll to highlighted message | `ChatScreenEffects.kt` | `ChatMessageList.kt` |
| Auto-scroll on new message | `ChatMessageList.kt` | `ChatScrollHelper.kt` (if duplicated) |
| Load more on scroll | `ChatScrollHelper.kt` | - |
| Scroll position tracking | `ChatScrollHelper.kt` | - |

### 3. Remove Duplicate from ChatMessageList.kt

If scroll-to-search and scroll-to-highlighted exist in both places:

```kotlin
// REMOVE from ChatMessageList.kt if duplicated in ChatScreenEffects:
LaunchedEffect(searchState.highlightedIndex) {
    searchState.highlightedIndex?.let { index ->
        listState.animateScrollToItem(index)
    }
}

LaunchedEffect(highlightedMessageGuid) {
    highlightedMessageGuid?.let { guid ->
        val index = messages.indexOfFirst { it.guid == guid }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }
}
```

### 4. Remove Dead Code from ChatScrollHelper.kt

Check if `AutoScrollOnNewMessage` is used anywhere:

```kotlin
// If this composable is never called, REMOVE it:
@Composable
fun AutoScrollOnNewMessage(
    listState: LazyListState,
    messages: List<MessageUiModel>,
    // ...
) {
    // ...
}
```

Search for usages: `AutoScrollOnNewMessage(`

### 5. Verify Scroll Restoration Logic

Ensure scroll restoration (on chat open, on return from background) has a single owner:

```kotlin
// Should be in ChatScreenEffects.kt or rememberChatScreenState
LaunchedEffect(Unit) {
    if (!state.scrollRestored && initialScrollPosition > 0) {
        listState.scrollToItem(initialScrollPosition, initialScrollOffset)
        state.scrollRestored = true
    }
}
```

### 6. Document the Ownership

Add comments to clarify ownership:

```kotlin
// In ChatScrollHelper.kt
/**
 * Scroll helpers for ChatMessageList.
 *
 * Scroll behavior ownership:
 * - Load more on scroll: LoadMoreOnScroll (this file)
 * - Scroll position tracking: ScrollPositionTracker (this file)
 * - Search result scrolling: ChatScreenEffects.kt
 * - Highlighted message scrolling: ChatScreenEffects.kt
 * - New message auto-scroll: ChatMessageList.kt
 */
```

---

## Verification

- [ ] Each scroll behavior has exactly one owner
- [ ] No duplicate LaunchedEffect for the same scroll trigger
- [ ] Dead code (`AutoScrollOnNewMessage` if unused) is removed
- [ ] Scroll to search result works correctly
- [ ] Scroll to highlighted message works correctly
- [ ] Auto-scroll on new message works correctly
- [ ] Build succeeds: `./gradlew assembleDebug`

---

## Notes

- Conflicting scroll commands can cause janky behavior (two animateScrollToItem calls fighting)
- Test thoroughly after consolidation - scroll bugs are easy to introduce
- If unsure which location is "primary", check which one has more complete logic
