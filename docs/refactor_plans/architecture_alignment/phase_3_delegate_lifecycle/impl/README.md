# Phase 3: Delegate Lifecycle — Unified Implementation Plan

> **Status**: Core Refactor Phase
> **Blocking**: Requires Phase 0 complete
> **Code Changes**: Constructor signatures, factory injection, ViewModel wiring
> **Recommendation**: Combine with Phase 2 for efficiency

## Overview

Delegates should be **"born ready"** — safe to use immediately after construction. This phase eliminates the `initialize()` + `lateinit` pattern using AssistedInject factories.

## Core Principle

> **An object should never exist in an invalid state. If a delegate requires `chatGuid` to function, it must have `chatGuid` at construction time.**

## The Problem

```kotlin
// CURRENT - Temporal coupling, easy to forget
class ChatSendDelegate @Inject constructor(...) {
    private lateinit var chatGuid: String       // Can crash if accessed before init!
    private lateinit var scope: CoroutineScope  // Can crash if accessed before init!

    fun initialize(chatGuid: String, scope: CoroutineScope) {
        this.chatGuid = chatGuid  // Must remember to call this!
        this.scope = scope
    }
}
```

## The Solution: AssistedInject

```kotlin
// AFTER - Safe by construction
class ChatSendDelegate @AssistedInject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender,
    @Assisted private val chatGuid: String,       // Required at construction
    @Assisted private val scope: CoroutineScope   // Required at construction
) {
    // No lateinit! All state available immediately.

    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatSendDelegate
    }
}
```

## Migration Order (Recommended)

Start simple, end complex. This order minimizes risk:

| Order | Delegate | Complexity | Dependencies |
|-------|----------|------------|--------------|
| 1 | `ChatConnectionDelegate` | Low | Few deps, good starter |
| 2 | `ChatInfoDelegate` | Low | Simple state exposure |
| 3 | `ChatEffectsDelegate` | Low | Few dependencies |
| 4 | `ChatAttachmentDelegate` | Low | Simple |
| 5 | `ChatScheduledMessageDelegate` | Low | Simple |
| 6 | `ChatThreadDelegate` | Medium | Thread overlay |
| 7 | `ChatSearchDelegate` | Medium | Has `setMessageListDelegate()` |
| 8 | `ChatSyncDelegate` | Medium | Sync logic |
| 9 | `ChatComposerDelegate` | High | Many dependencies |
| 10 | `ChatMessageListDelegate` | High | Many consumers |
| 11 | `ChatOperationsDelegate` | Medium | Has `setMessageListDelegate()` |
| 12 | `ChatSendModeManager` | Medium | Mode switching |
| 13 | `ChatEtaSharingDelegate` | Low | Simple |
| 14 | `ChatSendDelegate` | **Highest** | Most complex, do last |

## Implementation Tasks

### Task 1: Migration Template

For each delegate, follow this pattern:

#### Step 1: Add AssistedInject Annotations

```kotlin
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class ChatExampleDelegate @AssistedInject constructor(
    // Regular DI dependencies (injected by Hilt)
    private val repository: SomeRepository,
    private val service: SomeService,
    // Runtime parameters (provided at creation time)
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {
    // ...
}
```

#### Step 2: Convert lateinit to val

```kotlin
// BEFORE
private lateinit var chatGuid: String
private lateinit var scope: CoroutineScope

// AFTER - move to constructor parameters with @Assisted
@Assisted private val chatGuid: String,
@Assisted private val scope: CoroutineScope
```

#### Step 3: Move initialize() body to init block

```kotlin
// BEFORE
fun initialize(chatGuid: String, scope: CoroutineScope) {
    this.chatGuid = chatGuid
    this.scope = scope
    setupObservers()
    startSync()
}

// AFTER
init {
    setupObservers()  // Runs immediately after construction
    startSync()
}
```

#### Step 4: Add Factory interface

```kotlin
@AssistedFactory
interface Factory {
    fun create(
        chatGuid: String,
        scope: CoroutineScope
    ): ChatExampleDelegate
}
```

#### Step 5: Delete initialize() method

Remove the entire `initialize()` function.

### Task 2: Update ChatViewModel

```kotlin
// BEFORE
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    val send: ChatSendDelegate,           // Direct injection
    val connection: ChatConnectionDelegate,
    // ...
) : ViewModel() {
    private val chatGuid: String = checkNotNull(savedStateHandle["chatGuid"])

    init {
        send.initialize(chatGuid, viewModelScope)        // Must remember!
        connection.initialize(chatGuid, viewModelScope)   // Must remember!
    }
}

// AFTER
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sendFactory: ChatSendDelegate.Factory,           // Factory injection
    private val connectionFactory: ChatConnectionDelegate.Factory,
    // ...
) : ViewModel() {
    private val chatGuid: String = checkNotNull(savedStateHandle["chatGuid"])

    // Create delegates with all required state - "born ready"
    val send = sendFactory.create(chatGuid, viewModelScope)
    val connection = connectionFactory.create(chatGuid, viewModelScope)

    // No initialize() calls needed!
}
```

### Task 3: Handle Complex Initialize Signatures

Some delegates have complex `initialize()` with many parameters:

```kotlin
// BEFORE - ChatComposerDelegate
fun initialize(
    chatGuid: String,
    scope: CoroutineScope,
    uiState: StateFlow<ChatUiState>,
    syncState: StateFlow<SyncState>,
    sendState: StateFlow<SendState>,
    messagesState: StateFlow<StableList<MessageUiModel>>,
    onUiStateUpdate: (ChatUiState.() -> ChatUiState) -> Unit
)

// AFTER - All become @Assisted parameters
class ChatComposerDelegate @AssistedInject constructor(
    // DI dependencies
    private val attachmentRepository: AttachmentRepository,
    private val draftRepository: DraftRepository,
    // Runtime parameters
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope,
    @Assisted private val uiState: StateFlow<ChatUiState>,
    @Assisted private val syncState: StateFlow<SyncState>,
    @Assisted private val sendState: StateFlow<SendState>,
    @Assisted private val messagesState: StateFlow<StableList<MessageUiModel>>,
    @Assisted private val onUiStateUpdate: (ChatUiState.() -> ChatUiState) -> Unit
) {
    @AssistedFactory
    interface Factory {
        fun create(
            chatGuid: String,
            scope: CoroutineScope,
            uiState: StateFlow<ChatUiState>,
            syncState: StateFlow<SyncState>,
            sendState: StateFlow<SendState>,
            messagesState: StateFlow<StableList<MessageUiModel>>,
            onUiStateUpdate: (ChatUiState.() -> ChatUiState) -> Unit
        ): ChatComposerDelegate
    }
}
```

### Task 4: Keep setDelegates() Temporarily

**IMPORTANT**: Phase 3 is lifecycle-only. Keep cross-delegate wiring intact:

```kotlin
// KEEP THIS FOR NOW - Phase 4 will remove it
fun setDelegates(
    messageList: ChatMessageListDelegate,
    composer: ChatComposerDelegate,
    chatInfo: ChatInfoDelegate,
    connection: ChatConnectionDelegate,
    onDraftCleared: () -> Unit
) {
    this.messageListDelegate = messageList
    this.composerDelegate = composer
    this.chatInfoDelegate = chatInfo
    this.connectionDelegate = connection
    this.onDraftCleared = onDraftCleared
}
```

Phase 3 changes **lifecycle only**. Phase 4 removes **coupling**.

## Code Examples

### Complete Migration: ChatConnectionDelegate

```kotlin
// BEFORE
class ChatConnectionDelegate @Inject constructor(
    private val chatFallbackTracker: ChatFallbackTracker,
    private val counterpartSyncService: CounterpartSyncService,
    private val chatRepository: ChatRepository
) {
    private lateinit var chatGuid: String
    private lateinit var scope: CoroutineScope
    private lateinit var sendModeManager: ChatSendModeManager
    private var mergedChatGuids: List<String> = emptyList()

    fun initialize(
        chatGuid: String,
        scope: CoroutineScope,
        sendModeManager: ChatSendModeManager,
        mergedChatGuids: List<String> = listOf(chatGuid)
    ) {
        this.chatGuid = chatGuid
        this.scope = scope
        this.sendModeManager = sendModeManager
        this.mergedChatGuids = mergedChatGuids
        setupFallbackObservation()
        setupCounterpartSync()
    }
}
```

```kotlin
// AFTER
class ChatConnectionDelegate @AssistedInject constructor(
    // DI dependencies
    private val chatFallbackTracker: ChatFallbackTracker,
    private val counterpartSyncService: CounterpartSyncService,
    private val chatRepository: ChatRepository,
    // Runtime parameters
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope,
    @Assisted private val sendModeManager: ChatSendModeManager,
    @Assisted private val mergedChatGuids: List<String>
) {
    private val isMergedChat: Boolean = mergedChatGuids.size > 1

    init {
        setupFallbackObservation()
        setupCounterpartSync()
    }

    private fun setupFallbackObservation() {
        scope.launch {
            // ... existing code
        }
    }

    private fun setupCounterpartSync() {
        scope.launch {
            // ... existing code
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            chatGuid: String,
            scope: CoroutineScope,
            sendModeManager: ChatSendModeManager,
            mergedChatGuids: List<String>
        ): ChatConnectionDelegate
    }
}
```

### ChatViewModel After Migration

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    // Inject factories, not delegates
    private val sendFactory: ChatSendDelegate.Factory,
    private val connectionFactory: ChatConnectionDelegate.Factory,
    private val sendModeFactory: ChatSendModeManager.Factory,
    private val messageListFactory: ChatMessageListDelegate.Factory,
    private val composerFactory: ChatComposerDelegate.Factory,
    // ... other factories
) : ViewModel() {

    private val chatGuid: String = checkNotNull(savedStateHandle["chatGuid"])
    private val mergedChatGuids: List<String> = savedStateHandle["mergedGuids"] ?: listOf(chatGuid)

    // Create delegates - all "born ready"
    val sendMode = sendModeFactory.create(chatGuid)
    val connection = connectionFactory.create(chatGuid, viewModelScope, sendMode, mergedChatGuids)
    val messageList = messageListFactory.create(chatGuid, viewModelScope)
    val composer = composerFactory.create(chatGuid, viewModelScope, uiState, ...)
    val send = sendFactory.create(chatGuid, viewModelScope)

    init {
        // Cross-wiring still happens (Phase 4 removes this)
        send.setDelegates(messageList, composer, chatInfo, connection, ::clearDraft)
    }
}
```

## Common Mistakes to Avoid

### 1. Forgetting @Assisted

```kotlin
// WRONG - Hilt will try to inject these as DI dependencies
@AssistedInject constructor(
    private val chatGuid: String,  // Missing @Assisted!
    private val scope: CoroutineScope  // Missing @Assisted!
)

// CORRECT
@AssistedInject constructor(
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
)
```

### 2. Putting Factory Outside Class

```kotlin
// WRONG
class ChatSendDelegate @AssistedInject constructor(...) { }

@AssistedFactory
interface ChatSendDelegateFactory { }  // Outside class!

// CORRECT
class ChatSendDelegate @AssistedInject constructor(...) {
    @AssistedFactory
    interface Factory {  // Inside class!
        fun create(...): ChatSendDelegate
    }
}
```

### 3. Missing @AssistedFactory Annotation

```kotlin
// WRONG
interface Factory {
    fun create(...): ChatSendDelegate
}

// CORRECT
@AssistedFactory
interface Factory {
    fun create(...): ChatSendDelegate
}
```

## Migration Checklist (Per Delegate)

```markdown
## Delegate: ChatXxxDelegate

### Phase 3 Migration

- [ ] Add `@AssistedInject` to constructor
- [ ] Add `@Assisted` to runtime parameters (chatGuid, scope, etc.)
- [ ] Convert `lateinit var` to `val` constructor parameters
- [ ] Move `initialize()` body to `init {}` block
- [ ] Delete `initialize()` method
- [ ] Create `@AssistedFactory` interface inside class
- [ ] Update ChatViewModel to inject Factory and call `create()`
- [ ] **Keep `setDelegates()` temporarily** (Phase 4 removes it)

### Phase 2 Integration (do together)

- [ ] Swap concrete services to interfaces
- [ ] `MessageSendingService` → `MessageSender`
- [ ] `SocketService` → `SocketConnection`

### Verification

- [ ] Build passes: `./gradlew assembleDebug`
- [ ] App runs correctly
- [ ] No `UninitializedPropertyAccessException` errors
```

## Exit Criteria

- [ ] All Chat delegates use `@AssistedInject`
- [ ] No `lateinit var` for chatGuid/scope in any delegate
- [ ] No `initialize()` methods in delegates
- [ ] ChatViewModel uses Factory injection for all delegates
- [ ] `setDelegates()` methods still exist (Phase 4 removes them)
- [ ] Build passes
- [ ] App functions correctly
- [ ] At least one test validates factory construction

## Verification Commands

```bash
# Build should succeed
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug

# Check for remaining lateinit (should decrease with each migration)
grep -r "lateinit var chatGuid" app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/
grep -r "lateinit var scope" app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/

# Check for remaining initialize methods (should decrease)
grep -r "fun initialize(" app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/

# Count @AssistedInject usage (should increase)
grep -r "@AssistedInject" app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ | wc -l
```

## Test Validation

After migration, add a test proving no initialize() needed:

```kotlin
@Test
fun `delegate works immediately after factory creation`() = runTest {
    val delegate = delegateFactory.create(
        chatGuid = "test-guid",
        scope = this
    )

    // Should work immediately - no initialize() needed
    val result = delegate.someMethod()

    assertNotNull(result)
}
```

---

**Next Step**: After completing Phase 3, proceed to Phase 4 (Delegate Coupling Reduction) to remove `setDelegates()` patterns.
