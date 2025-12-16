# Phase 3: Implementation Guide (Delegate Lifecycle)

## Goal

Eliminate `lateinit var` + `initialize()` pattern using AssistedInject. Delegates should be "born ready" - safe to use immediately after construction.

## Prerequisites

1. Phase 0 ADRs reviewed
2. At least one test exists (ChatSendDelegateTest recommended)

## Migration Order (Recommended)

Start with simpler delegates, end with complex ones:

1. **ChatConnectionDelegate** - Fewest dependencies, good starter
2. **ChatInfoDelegate** - Simple state exposure
3. **ChatEffectsDelegate** - Simple, few dependencies
4. **ChatAttachmentDelegate** - Simple
5. **ChatScheduledMessageDelegate** - Simple
6. **ChatThreadDelegate** - Medium complexity
7. **ChatSearchDelegate** - Has `setMessageListDelegate()` coupling
8. **ChatSyncDelegate** - Medium complexity
9. **ChatComposerDelegate** - Complex, many dependencies
10. **ChatMessageListDelegate** - Complex, many consumers
11. **ChatOperationsDelegate** - Has `setMessageListDelegate()` coupling
12. **ChatSendModeManager** - Used by ChatConnectionDelegate
13. **ChatEtaSharingDelegate** - Simple
14. **ChatSendDelegate** - Most complex, do last

## Complete Migration Example: ChatConnectionDelegate

### Step 1: Current State (Before)

```kotlin
class ChatConnectionDelegate @Inject constructor(
    private val chatFallbackTracker: ChatFallbackTracker,
    private val counterpartSyncService: CounterpartSyncService,
    private val chatRepository: ChatRepository
) {
    // ❌ These are the problem - uninitialized state
    private lateinit var chatGuid: String
    private lateinit var scope: CoroutineScope
    private lateinit var sendModeManager: ChatSendModeManager
    private var mergedChatGuids: List<String> = emptyList()

    // ❌ Must remember to call this
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
        // ... setup code
    }
}
```

### Step 2: After Migration

```kotlin
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class ChatConnectionDelegate @AssistedInject constructor(
    // Regular DI dependencies (injected by Hilt)
    private val chatFallbackTracker: ChatFallbackTracker,
    private val counterpartSyncService: CounterpartSyncService,
    private val chatRepository: ChatRepository,
    // Runtime parameters (provided at creation time)
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope,
    @Assisted private val sendModeManager: ChatSendModeManager,
    @Assisted private val mergedChatGuids: List<String>
) {
    // ✅ No lateinit - all state available at construction
    private val isMergedChat: Boolean = mergedChatGuids.size > 1

    // ✅ Setup runs in init block, not separate method
    init {
        setupFallbackObservation()
        setupCounterpartSync()
    }

    // Factory interface for Hilt to implement
    @AssistedFactory
    interface Factory {
        fun create(
            chatGuid: String,
            scope: CoroutineScope,
            sendModeManager: ChatSendModeManager,
            mergedChatGuids: List<String>
        ): ChatConnectionDelegate
    }

    private fun setupFallbackObservation() {
        scope.launch {
            // ... existing observation code
        }
    }

    private fun setupCounterpartSync() {
        scope.launch {
            // ... existing sync code
        }
    }
}
```

### Step 3: Update ChatViewModel

```kotlin
// BEFORE
@HiltViewModel
class ChatViewModel @Inject constructor(
    // ... other deps
    val connection: ChatConnectionDelegate,  // ❌ Injected directly
) : ViewModel() {
    init {
        // ❌ Manual initialization
        connection.initialize(chatGuid, viewModelScope, sendMode, mergedChatGuids)
    }
}

// AFTER
@HiltViewModel
class ChatViewModel @Inject constructor(
    // ... other deps
    private val connectionFactory: ChatConnectionDelegate.Factory,  // ✅ Factory injected
) : ViewModel() {
    // ✅ Created with all parameters - "born ready"
    val connection = connectionFactory.create(
        chatGuid = chatGuid,
        scope = viewModelScope,
        sendModeManager = sendMode,
        mergedChatGuids = mergedChatGuids
    )
}
```

## Complete Migration Example: ChatSendDelegate (Complex)

### Before

```kotlin
class ChatSendDelegate @Inject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSendingService: MessageSendingService,
    private val socketService: SocketService,
    private val soundManager: SoundManager
) {
    private lateinit var chatGuid: String
    private lateinit var scope: CoroutineScope

    // Cross-delegate references (keep temporarily - Phase 4 will remove)
    private var messageListDelegate: ChatMessageListDelegate? = null
    private var composerDelegate: ChatComposerDelegate? = null
    private var chatInfoDelegate: ChatInfoDelegate? = null
    private var connectionDelegate: ChatConnectionDelegate? = null
    private var onDraftCleared: (() -> Unit)? = null

    fun initialize(chatGuid: String, scope: CoroutineScope) {
        this.chatGuid = chatGuid
        this.scope = scope
        observeUploadProgress()
        observeQueuedMessages()
    }

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
}
```

### After (Phase 3 only - keeping setDelegates temporarily)

```kotlin
class ChatSendDelegate @AssistedInject constructor(
    // DI dependencies - note interface usage (Phase 2)
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender,           // ✅ Interface
    private val socketConnection: SocketConnection,     // ✅ Interface
    private val soundManager: SoundManager,
    // Runtime parameters
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {
    // ⚠️ KEEP setDelegates() TEMPORARILY - will be removed in Phase 4
    // Cross-delegate references stay nullable for now
    private var messageListDelegate: ChatMessageListDelegate? = null
    private var composerDelegate: ChatComposerDelegate? = null
    private var chatInfoDelegate: ChatInfoDelegate? = null
    private var connectionDelegate: ChatConnectionDelegate? = null
    private var onDraftCleared: (() -> Unit)? = null

    init {
        // ✅ Observation starts immediately - no separate initialize()
        observeUploadProgress()
        observeQueuedMessages()
    }

    // ⚠️ KEEP THIS METHOD - Phase 4 will remove it
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

    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatSendDelegate
    }
}
```

## Handling Complex Initialize() Methods

Some delegates have complex `initialize()` signatures with StateFlows:

### ChatComposerDelegate (Complex Case)

```kotlin
// BEFORE
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
    // DI dependencies...
    private val attachmentRepository: AttachmentRepository,
    // ... other injected deps
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

## Gradle Dependencies

Ensure these are in `build.gradle.kts`:

```kotlin
// Should already be present for Hilt
ksp(libs.hilt.compiler)
implementation(libs.hilt.android)

// AssistedInject is part of Dagger/Hilt - no additional dependency needed
```

## Required Imports

```kotlin
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
```

## Migration Checklist Per Delegate

- [ ] Add `@AssistedInject` to constructor
- [ ] Add `@Assisted` to runtime parameters (chatGuid, scope, etc.)
- [ ] Convert `lateinit var` to `val` constructor parameters
- [ ] Move `initialize()` body to `init {}` block
- [ ] Delete `initialize()` method
- [ ] Create `@AssistedFactory` interface inside class
- [ ] Update ChatViewModel to inject Factory and call `create()`
- [ ] Keep `setDelegates()` temporarily (Phase 4 removes it)
- [ ] Swap concrete services to interfaces (Phase 2)
- [ ] Test that delegate works correctly

## Common Mistakes to Avoid

### 1. Forgetting @Assisted on runtime params

```kotlin
// WRONG - Hilt will try to inject these
@AssistedInject constructor(
    private val chatGuid: String,  // ❌ Missing @Assisted
    private val scope: CoroutineScope  // ❌ Missing @Assisted
)

// CORRECT
@AssistedInject constructor(
    @Assisted private val chatGuid: String,  // ✅
    @Assisted private val scope: CoroutineScope  // ✅
)
```

### 2. Putting Factory outside the class

```kotlin
// WRONG - Factory must be inside the delegate class
class ChatSendDelegate @AssistedInject constructor(...) { }

@AssistedFactory
interface ChatSendDelegateFactory { }  // ❌ Outside

// CORRECT
class ChatSendDelegate @AssistedInject constructor(...) {
    @AssistedFactory
    interface Factory {  // ✅ Inside
        fun create(...): ChatSendDelegate
    }
}
```

### 3. Missing @AssistedFactory annotation

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

## Verification

After migrating a delegate:

```bash
# Build should succeed
./gradlew assembleDebug

# Grep for removed patterns
grep -r "lateinit var chatGuid" app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/
grep -r "fun initialize(" app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatConnectionDelegate.kt

# Should find fewer matches after each migration
```

## Exit Criteria for Phase 3

- [ ] All Chat delegates use `@AssistedInject`
- [ ] No `lateinit var` for chatGuid/scope in delegates
- [ ] No `initialize()` methods in delegates
- [ ] ChatViewModel uses Factory injection
- [ ] `setDelegates()` still exists (will be removed in Phase 4)
- [ ] Build passes
- [ ] App runs correctly
