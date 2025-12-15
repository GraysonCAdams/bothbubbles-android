# Chat Conversation Rendering Architecture

**Last Updated:** December 15, 2025
**Status:** Technical Reference Document
**Scope:** Message list rendering, state management, recomposition triggers, and animation systems

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Architecture Overview](#architecture-overview)
3. [Data Flow](#data-flow)
4. [Message Paging System](#message-paging-system)
5. [UI Composition Structure](#ui-composition-structure)
6. [Recomposition vs New Composition](#recomposition-vs-new-composition)
7. [Animation Systems](#animation-systems)
8. [State Management & Re-render Triggers](#state-management--re-render-triggers)
9. [Performance Findings & Known Issues](#performance-findings--known-issues)
10. [Key Files Reference](#key-files-reference)

---

## Executive Summary

The BothBubbles chat rendering system is built on Jetpack Compose with a Signal-style BitSet pagination controller. Messages flow from Room database through a sparse paging layer, are transformed into `MessageUiModel` instances with incremental caching, and rendered via a reversed `LazyColumn`.

**Key architectural decisions:**
- Sparse pagination (can load position 5000 without loading 0-4999)
- Incremental message cache (preserves object identity for unchanged items)
- Deferred animation tracking (separates initial load from new message animations)
- Segmented bubble rendering (media/links render outside text bubbles)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           UI Layer (Compose)                                 │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ ChatScreen.kt                                                         │  │
│  │  └─► LazyColumn (reverseLayout=true)                                 │  │
│  │       └─► itemsIndexed(messages, key = { guid })                     │  │
│  │            └─► MessageBubble                                          │  │
│  │                 ├─► SegmentedMessageBubble (media/links)              │  │
│  │                 └─► SimpleBubbleContent (text-only)                   │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                    │                                         │
│                    collectAsStateWithLifecycle()                            │
│                                    │                                         │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ ChatViewModel.kt                                                      │  │
│  │  ├─► uiState: StateFlow<ChatUiState>                                 │  │
│  │  ├─► sparseMessages: StateFlow<SparseMessageList>                    │  │
│  │  └─► composerState: StateFlow<ComposerState>                         │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Paging Layer                                          │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ MessagePagingController.kt                                            │  │
│  │  ├─► BitSet loadStatus (tracks loaded positions)                     │  │
│  │  ├─► Map<Int, MessageUiModel> sparseData                             │  │
│  │  ├─► Map<String, Int> guidToPosition                                 │  │
│  │  └─► Set<String> seenMessageGuids (animation control)                │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                    │                                         │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ RoomMessageDataSource.kt                                              │  │
│  │  └─► Transforms MessageEntity → MessageUiModel                       │  │
│  │      Uses MessageCache for incremental updates                       │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Data Layer (Room)                                  │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ MessageRepository → MessageDao → SQLite                              │  │
│  │  ├─► getMessagesByPosition(chatGuids, limit, offset)                 │  │
│  │  ├─► observeMessageCountForChats(chatGuids)                          │  │
│  │  └─► getReactionsForMessages(messageGuids)                           │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Data Flow

### Initial Load Sequence

```
1. ChatScreen mounted
   │
   ├─► ChatViewModel.init()
   │    └─► pagingController.initialize()
   │         ├─► dataSource.size() → Get total message count
   │         ├─► loadRange(0, initialLoadSize) → Load newest 50 messages
   │         └─► _initialLoadComplete.value = true
   │
   ├─► ChatScreen collects sparseMessages
   │    └─► SparseMessageList.toList() → Convert to List<MessageUiModel>
   │
   └─► LazyColumn renders with reverseLayout=true
        └─► itemsIndexed with key = { message.guid }
```

### New Message Arrival (Socket Push)

```
1. SocketService receives NewMessage event
   │
   ├─► SocketEventHandler → MessageEventHandler.handleNewMessage()
   │    └─► MessageDao.insertMessage()
   │
   ├─► RoomMessageDataSource.observeSize() emits new count
   │
   ├─► MessagePagingController.onSizeChanged(newSize)
   │    ├─► shiftPositions(addedCount) → Shift all existing positions +1
   │    └─► loadRange(0, addedCount + prefetchDistance)
   │
   ├─► _messages.value = SparseMessageList(...)
   │
   └─► ChatScreen recomposes
        └─► New message renders with entrance animation
```

### Scroll-Triggered Loading

```
1. User scrolls toward older messages (higher indices)
   │
   ├─► LazyColumn scroll position changes
   │    └─► snapshotFlow { firstVisibleIndex, lastVisibleIndex }
   │
   ├─► ChatViewModel.onScrollPositionChanged(first, last)
   │    └─► pagingController.onDataNeededAroundIndex(first, last)
   │
   ├─► MessagePagingController (debounced 50ms)
   │    ├─► findGaps(loadStart, loadEnd, loadStatus) → Find unloaded ranges
   │    └─► loadRange(gap.first, gap.last) for each gap
   │
   └─► New messages appear in view without full list refresh
```

---

## Message Paging System

### Signal-Style BitSet Pagination

The paging system is modeled after Signal's `FixedSizePagingController`, using a BitSet to track loaded positions:

```kotlin
// MessagePagingController.kt
class MessagePagingController {
    // BitSet: position → isLoaded
    private val loadStatus = BitSet()

    // Sparse storage: only loaded positions have entries
    private val sparseData = mutableMapOf<Int, MessageUiModel>()

    // GUID → position mapping for O(1) lookups
    private val guidToPosition = mutableMapOf<String, Int>()

    // Track seen messages for animation control
    private val seenMessageGuids = mutableSetOf<String>()
}
```

**Key behaviors:**
- **Sparse Loading:** Can jump to position 5000 directly (for search results/deep links)
- **Gap Detection:** `findGaps()` identifies unloaded ranges within prefetch distance
- **Position Shifting:** When new messages arrive, all positions shift by N
- **Generation Counter:** Invalidates in-flight loads after position shifts

### SparseMessageList

```kotlin
data class SparseMessageList(
    val totalSize: Int,
    private val loadedData: Map<Int, MessageUiModel>,
    val loadedRanges: List<IntRange>
) {
    fun toList(): List<MessageUiModel> // For backwards compatibility
    fun get(index: Int): MessageUiModel? // Nullable for unloaded positions
    fun isLoaded(index: Int): Boolean
}
```

### MessageCache (Incremental Updates)

The `MessageCache` preserves object identity for unchanged messages, enabling Compose to skip recomposition:

```kotlin
class MessageCache {
    // Cache: GUID → (hash, MessageUiModel)
    private val cache = mutableMapOf<String, Pair<Int, MessageUiModel>>()

    fun updateAndBuild(
        entities: List<MessageEntity>,
        reactions: Map<String, List<...>>,
        attachments: Map<String, List<...>>,
        transform: (entity, reactions, attachments) -> MessageUiModel
    ): Pair<List<MessageUiModel>, Set<String>> {
        // Returns (models, changedGuids)
        // Unchanged items return cached instance (same object identity)
    }
}
```

---

## UI Composition Structure

### LazyColumn Configuration

```kotlin
// ChatScreen.kt
LazyColumn(
    modifier = Modifier.fillMaxSize(),
    state = listState,
    reverseLayout = true,  // Index 0 = newest message at bottom
    contentPadding = PaddingValues(...)
) {
    // Spam safety banner (at visual bottom due to reverseLayout)
    if (uiState.isSpam) { item(key = "spam_safety_banner") { ... } }

    // Typing indicator
    if (uiState.isTyping) { item(key = "typing_indicator") { ... } }

    // Messages
    itemsIndexed(
        items = uiState.messages,
        key = { _, message -> message.guid },  // Stable keys!
        contentType = { _, message -> if (message.isFromMe) 1 else 0 }
    ) { index, message ->
        // Pre-computed lookups (O(1) via Maps built once per recomposition)
        val nextVisibleMessage = nextVisibleMessageMap[index]
        val showSenderName = showSenderNameMap[index] ?: false
        val showAvatar = showAvatarMap[index] ?: false

        // Animation control
        val shouldAnimateEntrance = initialLoadComplete && !alreadyAnimated

        MessageBubble(...)
    }

    // Loading indicators (at visual top due to reverseLayout)
    if (uiState.isLoadingMore) { item(key = "loading_more") { ... } }
}
```

### Cache Window for Item Retention

```kotlin
// Large cache window keeps ~50 messages composed beyond viewport
val cacheWindow = remember { LazyLayoutCacheWindow(ahead = 1000.dp, behind = 2000.dp) }
val listState = rememberLazyListState(cacheWindow = cacheWindow, ...)
```

### MessageBubble Routing

```kotlin
@Composable
fun MessageBubble(message: MessageUiModel, ...) {
    // Detect if segmentation is needed
    val needsSegmentation = remember(message, firstUrl) {
        MessageSegmentParser.needsSegmentation(message, firstUrl != null)
    }

    if (needsSegmentation) {
        // Messages with media attachments or link previews
        SegmentedMessageBubble(...)
    } else {
        // Optimized fast path for text-only messages
        SimpleBubbleContent(...)
    }
}
```

### Message Segmentation

Messages are parsed into segments for rendering:

```kotlin
sealed class MessageSegment {
    data class TextSegment(val text: String) : MessageSegment()
    data class MediaSegment(val attachment: AttachmentUiModel) : MessageSegment()
    data class LinkPreviewSegment(val url: String, val detectedUrl: DetectedUrl) : MessageSegment()
    data class FileSegment(val attachment: AttachmentUiModel) : MessageSegment()
}

// Segment order: media first → text bubble → link preview → file attachments
```

---

## Recomposition vs New Composition

### How Compose Determines What to Recompose

1. **Stable Keys:** `key = { message.guid }` ensures Compose tracks items by GUID, not position
2. **Object Identity:** If `MessageUiModel` instance is the same object (via cache), children skip
3. **Structural Equality:** `@Stable` annotation + data class `equals()` for smart comparison
4. **ContentType:** `contentType = { if (isFromMe) 1 else 0 }` hints item pool reuse

### Recomposition Triggers

| Trigger | Scope | Mitigation |
|---------|-------|------------|
| `uiState.messages` list changes | Full `itemsIndexed` | MessageCache preserves object identity |
| Message state changes (reactions, delivery) | Single item | `updateMessageLocally()` for optimistic UI |
| Scroll position changes | None (no state read) | - |
| Typing indicator toggle | Just typing item | Separate `item(key = "typing_indicator")` |
| Search highlighting | Affected messages | `isCurrentSearchMatch` passed as param |

### Pre-Computed Maps (O(n) Once, O(1) Per-Item)

```kotlin
// Instead of O(n²) per-item lookups:
val nextVisibleMessageMap = remember(uiState.messages) {
    // Build once when messages change
    val map = mutableMapOf<Int, MessageUiModel?>()
    var lastVisibleMessage: MessageUiModel? = null
    for (i in uiState.messages.indices.reversed()) {
        map[i] = lastVisibleMessage
        if (!uiState.messages[i].isReaction) {
            lastVisibleMessage = uiState.messages[i]
        }
    }
    map
}

val showSenderNameMap = remember(uiState.messages, uiState.isGroup) { ... }
val showAvatarMap = remember(uiState.messages, uiState.isGroup) { ... }
```

---

## Animation Systems

### New Message Entrance Animation

```kotlin
// AnimationUtils.kt
@Composable
fun Modifier.newMessageEntrance(
    shouldAnimate: Boolean,
    isFromMe: Boolean = false
): Modifier {
    if (!shouldAnimate) return this  // Skip entirely when not needed

    var hasAppeared by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(16)  // One frame delay for smooth render
        hasAppeared = true
    }

    // Animate: alpha (0→1), translationY (40→0), scale (0.92/0.96→1)
    return this.graphicsLayer {
        alpha = animatedAlpha
        translationY = animatedTranslationY
        scaleX = animatedScale
        scaleY = animatedScale
    }
}
```

### Animation Control Flow

```
1. Initial Load:
   │
   ├─► pagingController.initialLoadComplete = false
   │
   ├─► Messages load into sparseData
   │
   ├─► seenMessageGuids populated with all loaded GUIDs
   │
   └─► initialLoadComplete = true
        └─► ChatScreen: Mark existing messages as "already animated"
             animatedMessageGuids.addAll(currentMessages)

2. New Message Arrives:
   │
   ├─► Message inserted at position 0
   │
   ├─► NOT in animatedMessageGuids (it's new!)
   │
   ├─► shouldAnimateEntrance = initialLoadComplete && !alreadyAnimated
   │    └─► TRUE → Animate
   │
   └─► animatedMessageGuids.add(newGuid) immediately
        └─► Prevents re-animation on scroll back
```

### Item Placement Animation

```kotlin
// PERF: Use snap() instead of spring for rapid scrolling
.animateItem(
    fadeInSpec = null,
    fadeOutSpec = null,
    placementSpec = snap()  // No spring = no frame drops during scroll
)
```

### Bubble Effect Animations

```kotlin
// BubbleEffectWrapper handles iMessage-style effects
BubbleEffectWrapper(
    effect = bubbleEffect,  // SLAM, LOUD, GENTLE, etc.
    isNewMessage = shouldAnimateBubble,
    isFromMe = message.isFromMe,
    onEffectComplete = { viewModel.onBubbleEffectCompleted(message.guid) },
    isInvisibleInkRevealed = isInvisibleInkRevealed,
    ...
) {
    MessageBubble(...)
}
```

---

## State Management & Re-render Triggers

### ChatUiState Structure

```kotlin
data class ChatUiState(
    // Messages (via separate flow for optimization)
    val messages: List<MessageUiModel> = emptyList(),

    // Chat metadata
    val chatTitle: String = "",
    val isGroup: Boolean = false,
    val isTyping: Boolean = false,

    // Send mode
    val currentSendMode: ChatSendMode = ChatSendMode.SMS,
    val isInSmsFallbackMode: Boolean = false,

    // Loading states
    val isLoadingMore: Boolean = false,
    val isSyncingMessages: Boolean = false,

    // Search
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchMatchIndices: List<Int> = emptyList(),

    // Reply
    val replyingToGuid: String? = null,
    val replyToMessage: MessageUiModel? = null,

    // + ~30 more fields
)
```

### Composer State Optimization

The composer was extracted from `ChatUiState` to prevent cascade recomposition:

```kotlin
// ComposerRelevantState extracts only composer-needed fields
private val composerRelevantState: Flow<ComposerRelevantState> = _uiState
    .map { ui ->
        ComposerRelevantState(
            replyToMessage = ui.replyToMessage,
            currentSendMode = ui.currentSendMode,
            isSending = ui.isSending,
            // Only 7 fields instead of 80+
        )
    }
    .distinctUntilChanged()  // Gate: only emit when these fields change

val composerState: StateFlow<ComposerState> = combine(
    composerRelevantState,  // Gated
    _draftText,
    _pendingAttachments,
    _attachmentQuality,
    _activePanel
) { ... }
    .distinctUntilChanged()  // Final gate
```

### Flow Collection in Compose

```kotlin
// ChatScreen.kt - Lifecycle-aware collection
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
val initialLoadComplete by viewModel.initialLoadComplete.collectAsStateWithLifecycle()
val composerState by viewModel.composerState.collectAsStateWithLifecycle()
```

---

## Performance Findings & Known Issues

### Critical Issue #1: `adjustedComposerState` Remember Block

**Location:** `ChatScreen.kt:822-850`

```kotlin
// PROBLEM: composerState changes on EVERY keystroke
val adjustedComposerState = remember(
    composerState,  // <-- Changes on every keystroke!
    isRecording,
    isPreviewingVoiceMemo,
    ...
) {
    composerState.copy(...)  // Creates new object with 23 fields
}
```

**Impact:** Every keystroke triggers:
- remember block re-execution
- `ComposerState.copy()` (23 fields)
- Full `ChatComposer` recomposition tree

**Status:** Documented in `RESEARCH_TYPING_SLUGGISHNESS_DEEP_DIVE.md`

### High Priority Issue: Combine Block Before DistinctUntilChanged

**Location:** `ChatViewModel.kt:252-317`

```kotlin
val composerState: StateFlow<ComposerState> = combine(
    composerRelevantState,
    _draftText,  // <-- Emits on every keystroke
    _pendingAttachments,
    _attachmentQuality,
    _activePanel
) { ... }
    .distinctUntilChanged()  // <-- Too late! Work already done
```

**Impact:** Combine lambda executes on every keystroke before filtering.

### Medium Priority: Lambda Recreation in TextInputContent

**Location:** `ChatComposer.kt:343-364`

```kotlin
ComposerTextField(
    leadingContent = {
        ComposerActionButtons(...)  // NEW LAMBDA EVERY RECOMPOSE
    },
    trailingContent = {
        ComposerMediaButtons(...)  // NEW LAMBDA EVERY RECOMPOSE
    }
)
```

**Status:** Should wrap in `remember(relevantInputs)`

### Optimizations Already Implemented

1. **MessageCache** - Preserves object identity for unchanged messages
2. **Pre-computed Maps** - O(1) lookups for sender name, avatar, next message
3. **ComposerRelevantState** - Isolates composer from full uiState
4. **LazyLayoutCacheWindow** - Keeps ~50 messages composed beyond viewport
5. **snap() for animateItem** - Removes spring animation overhead during scroll
6. **seenMessageGuids** - Tracks animation state in paging controller
7. **animatedMessageGuids** - UI-level deduplication of entrance animations

---

## Key Files Reference

| File | Purpose |
|------|---------|
| `ChatScreen.kt` | Main chat UI, LazyColumn, animation orchestration |
| `ChatViewModel.kt` | State management, composerState, uiState |
| `MessagePagingController.kt` | Signal-style BitSet pagination |
| `RoomMessageDataSource.kt` | Room → MessageUiModel transformation |
| `SparseMessageList.kt` | Sparse list container for pagination |
| `MessageCache.kt` | Incremental update caching |
| `MessageBubble.kt` | Bubble routing (segmented vs simple) |
| `MessageSegment.kt` | Segment parsing for media/links |
| `AnimationUtils.kt` | `newMessageEntrance`, `staggeredEntrance` modifiers |
| `ComposerState.kt` | Composer state data class (23 fields) |

---

## Testing & Profiling

### Enable Compose Compiler Reports

```gradle
// build.gradle.kts
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}
```

### Layout Inspector

1. Android Studio → View → Tool Windows → Layout Inspector
2. Enable "Show Recomposition Counts"
3. Type in composer and observe counts on `ComposerActionButtons`, `ComposerMediaButtons`

### Systrace Profiling

```bash
adb shell atrace -t 5 -b 16384 gfx view dalvik -o /data/local/tmp/trace.html
adb pull /data/local/tmp/trace.html
```

Look for:
- Main thread blocking during `Choreographer#doFrame`
- Excessive `View#draw` calls
- GC pressure from object allocations

---

## Related Documentation

- `RESEARCH_TYPING_SLUGGISHNESS.md` - Lambda recreation analysis
- `RESEARCH_TYPING_SLUGGISHNESS_DEEP_DIVE.md` - Full typing performance analysis
- `CLAUDE.md` - Project architecture overview and coding conventions
