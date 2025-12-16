# Phase 4: Delegate Coupling Reduction — Unified Implementation Plan

> **Status**: ✅ COMPLETE
> **Completed**: 2024-12-16
> **Blocking**: Requires Phase 3 complete (was complete)
> **Code Changes**: Remove setDelegates(), add coordinator orchestration
> **Risk Level**: Medium-High (touches control flow)

## Overview

After Phase 3, delegates are "born ready" but still hold references to each other via `setDelegates()`. This phase removes those cross-references, making the ViewModel the **single point of coordination**.

## Core Principle

> **Delegates should not know about each other. The ViewModel coordinates all cross-delegate interactions.**

## The Problem

```kotlin
// CURRENT - Hidden delegate web
class ChatSendDelegate {
    private var messageListDelegate: ChatMessageListDelegate? = null
    private var composerDelegate: ChatComposerDelegate? = null

    fun sendMessage(text: String) {
        // Delegate calls delegate - hidden coupling!
        val input = composerDelegate?.getInput() ?: return
        messageListDelegate?.insertOptimistic(...)
        composerDelegate?.clearInput()
    }
}
```

## The Solution

```kotlin
// AFTER - ViewModel coordinates explicitly
class ChatViewModel {
    fun sendMessage() {
        // All coordination is visible here
        val input = composer.getInput()
        val queuedInfo = send.queueMessage(input)
        messageList.insertOptimistic(queuedInfo)
        composer.clearInput()
    }
}
```

## Current Coupling to Remove

```kotlin
// ChatViewModel init block - THIS NEEDS TO GO
send.setDelegates(
    messageList = messageList,
    composer = composer,
    chatInfo = chatInfo,
    connection = connection,
    onDraftCleared = { composer.clearDraftFromDatabase() }
)
operations.setMessageListDelegate(messageList)
search.setMessageListDelegate(messageList)
```

## Design Options

### Option 1: Coordinator-Only Orchestration (Default)

ViewModel calls delegates explicitly. Simple, traceable.

```kotlin
fun sendMessage() {
    viewModelScope.launch {
        val input = composer.getInput()
        val mode = connection.getCurrentSendMode()
        val queued = send.queueMessage(input.text, input.attachments, mode)
        messageList.insertOptimistic(queued)
        composer.clearInput()
    }
}
```

### Option 1.5: Workflow Extraction (For Complex Flows)

For multi-step flows, extract to a dedicated workflow class:

```kotlin
class SendMessageWorkflow @Inject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender
) {
    sealed interface SendOutcome {
        data class Queued(val info: QueuedMessageInfo) : SendOutcome
        data class Failed(val error: Throwable) : SendOutcome
    }

    suspend fun execute(params: SendParams): SendOutcome {
        // Multi-step logic encapsulated here
    }
}
```

### Option 2: Localized Flows (Targeted Use)

Delegate exposes output as Flow; coordinator collects it.

```kotlin
// Producer
class ChatSendDelegate {
    private val _outcomes = MutableSharedFlow<SendOutcome>()
    val outcomes: SharedFlow<SendOutcome> = _outcomes.asSharedFlow()
}

// Consumer (ViewModel)
init {
    send.outcomes.onEach { outcome ->
        when (outcome) {
            is SendOutcome.Queued -> messageList.insertOptimistic(outcome.info)
        }
    }.launchIn(viewModelScope)
}
```

## Implementation Tasks

### Task 1: Map Current Coupling

Create `delegate_coupling_map.md`:

```markdown
# Delegate Coupling Map

## ChatSendDelegate
- Uses: composerDelegate.getInput()
- Uses: connectionDelegate.getCurrentSendMode()
- Uses: messageListDelegate.insertOptimistic()
- Uses: composerDelegate.clearInput()
- Uses: onDraftCleared callback

## ChatSearchDelegate
- Uses: messageListDelegate.scrollToMessage()

## ChatOperationsDelegate
- Uses: messageListDelegate.refreshMessage()
```

### Task 2: Refactor ChatSendDelegate

#### Before (With Coupling)

```kotlin
class ChatSendDelegate @AssistedInject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender,
    private val socketConnection: SocketConnection,
    private val soundManager: SoundManager,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {
    // Cross-delegate references
    private var messageListDelegate: ChatMessageListDelegate? = null
    private var composerDelegate: ChatComposerDelegate? = null
    private var chatInfoDelegate: ChatInfoDelegate? = null
    private var connectionDelegate: ChatConnectionDelegate? = null
    private var onDraftCleared: (() -> Unit)? = null

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

    fun sendCurrentMessage(effectId: String? = null) {
        val composer = composerDelegate ?: return
        val messageList = messageListDelegate ?: return
        val connection = connectionDelegate ?: return

        scope.launch {
            val input = composer.getInput()
            val mode = connection.getCurrentSendMode()
            // ... complex send logic using delegates
            messageList.insertOptimistic(...)
            composer.clearInput()
        }
    }
}
```

#### After (Decoupled)

```kotlin
class ChatSendDelegate @AssistedInject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender,
    private val socketConnection: SocketConnection,
    private val soundManager: SoundManager,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {
    // NO delegate references!

    /**
     * Queues a message for sending. Returns info for optimistic UI update.
     * Does NOT interact with other delegates - that's the ViewModel's job.
     */
    suspend fun queueMessage(
        text: String,
        attachments: List<PendingAttachmentInput>,
        replyToGuid: String?,
        effectId: String?,
        sendMode: ChatSendMode
    ): QueuedMessageInfo {
        val tempGuid = UUID.randomUUID().toString()

        pendingMessageRepository.queueMessage(
            chatGuid = chatGuid,
            text = text,
            attachments = attachments,
            tempGuid = tempGuid,
            replyToGuid = replyToGuid,
            effectId = effectId,
            sendMode = sendMode
        )

        soundManager.playMessageSentSound()

        return QueuedMessageInfo(
            guid = tempGuid,
            text = text,
            dateCreated = System.currentTimeMillis(),
            hasAttachments = attachments.isNotEmpty(),
            replyToGuid = replyToGuid,
            effectId = effectId
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatSendDelegate
    }
}

// New data class for return value
data class QueuedMessageInfo(
    val guid: String,
    val text: String,
    val dateCreated: Long,
    val hasAttachments: Boolean,
    val replyToGuid: String?,
    val effectId: String?
)
```

### Task 3: Add Coordination to ChatViewModel

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sendFactory: ChatSendDelegate.Factory,
    private val connectionFactory: ChatConnectionDelegate.Factory,
    private val messageListFactory: ChatMessageListDelegate.Factory,
    private val composerFactory: ChatComposerDelegate.Factory,
    private val soundManager: SoundManager
) : ViewModel() {

    private val chatGuid: String = checkNotNull(savedStateHandle["chatGuid"])

    // Create delegates
    val connection = connectionFactory.create(chatGuid, viewModelScope, ...)
    val messageList = messageListFactory.create(chatGuid, viewModelScope)
    val composer = composerFactory.create(chatGuid, viewModelScope, ...)
    val send = sendFactory.create(chatGuid, viewModelScope)

    // NO setDelegates() calls!

    /**
     * ViewModel coordinates the entire send flow.
     * Control flow is explicit and traceable.
     */
    fun sendMessage(effectId: String? = null) {
        viewModelScope.launch {
            // Step 1: Get input from composer
            val input = composer.getComposerInput()
            if (input.text.isBlank() && input.attachments.isEmpty()) return@launch

            // Step 2: Get send mode from connection
            val sendMode = connection.getCurrentSendMode()

            // Step 3: Queue message (returns info for optimistic UI)
            val queuedInfo = send.queueMessage(
                text = input.text,
                attachments = input.attachments,
                replyToGuid = input.replyToGuid,
                effectId = effectId,
                sendMode = sendMode
            )

            // Step 4: Insert optimistic message into list
            messageList.insertOptimisticMessage(queuedInfo)

            // Step 5: Clear composer
            composer.clearInput()
            composer.clearDraftFromDatabase()

            // Step 6: Scroll to bottom
            messageList.scrollToBottom()
        }
    }
}
```

### Task 4: Refactor ChatSearchDelegate

#### Before

```kotlin
class ChatSearchDelegate {
    private var messageListDelegate: ChatMessageListDelegate? = null

    fun setMessageListDelegate(messageList: ChatMessageListDelegate) {
        this.messageListDelegate = messageList
    }

    fun scrollToSearchResult(index: Int) {
        val guid = searchResults.getOrNull(index)?.guid ?: return
        messageListDelegate?.scrollToMessage(guid)
    }
}
```

#### After

```kotlin
class ChatSearchDelegate @AssistedInject constructor(
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {
    // NO delegate references!

    /**
     * Returns the message GUID for a search result.
     * ViewModel handles the scroll action.
     */
    fun getSearchResultGuid(index: Int): String? {
        return searchResults.getOrNull(index)?.guid
    }

    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatSearchDelegate
    }
}

// In ChatViewModel:
fun scrollToSearchResult(index: Int) {
    val guid = search.getSearchResultGuid(index) ?: return
    messageList.scrollToMessage(guid)
}
```

### Task 5: Refactor ChatOperationsDelegate

#### Before

```kotlin
class ChatOperationsDelegate {
    private var messageListDelegate: ChatMessageListDelegate? = null

    fun toggleReaction(messageGuid: String, reaction: String) {
        scope.launch {
            val result = messageSender.sendReaction(...)
            messageListDelegate?.refreshMessage(messageGuid)  // Hidden coupling
        }
    }
}
```

#### After

```kotlin
class ChatOperationsDelegate @AssistedInject constructor(
    private val messageSender: MessageSender,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {
    // NO delegate references!

    /**
     * Returns result - ViewModel handles the refresh.
     */
    suspend fun toggleReaction(
        messageGuid: String,
        reaction: String
    ): Result<Unit> {
        return messageSender.sendReaction(messageGuid, reaction)
    }

    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatOperationsDelegate
    }
}

// In ChatViewModel:
fun toggleReaction(messageGuid: String, reaction: String) {
    viewModelScope.launch {
        val result = operations.toggleReaction(messageGuid, reaction)
        if (result.isSuccess) {
            messageList.refreshMessage(messageGuid)
        }
    }
}
```

### Task 6: Optional Workflow Extraction

For complex multi-step flows, extract a Workflow class:

```kotlin
/**
 * Encapsulates the multi-step send flow.
 * Keeps ChatViewModel focused on UI concerns.
 */
class SendMessageWorkflow @Inject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender
) {
    sealed interface SendOutcome {
        data class Queued(val info: QueuedMessageInfo) : SendOutcome
        data class Failed(val error: AppError) : SendOutcome
    }

    suspend fun execute(
        chatGuid: String,
        text: String,
        attachments: List<PendingAttachmentInput>,
        replyToGuid: String?,
        effectId: String?,
        sendMode: ChatSendMode
    ): SendOutcome {
        return try {
            val tempGuid = UUID.randomUUID().toString()

            pendingMessageRepository.queueMessage(
                chatGuid = chatGuid,
                text = text,
                attachments = attachments,
                tempGuid = tempGuid,
                replyToGuid = replyToGuid,
                effectId = effectId,
                sendMode = sendMode
            )

            SendOutcome.Queued(
                QueuedMessageInfo(
                    guid = tempGuid,
                    text = text,
                    dateCreated = System.currentTimeMillis(),
                    hasAttachments = attachments.isNotEmpty(),
                    replyToGuid = replyToGuid,
                    effectId = effectId
                )
            )
        } catch (e: Exception) {
            SendOutcome.Failed(MessageError.SendFailed(e.message ?: "Unknown error"))
        }
    }
}
```

Usage in ViewModel:

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sendMessageWorkflow: SendMessageWorkflow,
    // ... other deps
) : ViewModel() {

    fun sendMessage(effectId: String? = null) {
        viewModelScope.launch {
            val input = composer.getComposerInput()
            val sendMode = connection.getCurrentSendMode()

            when (val outcome = sendMessageWorkflow.execute(
                chatGuid = chatGuid,
                text = input.text,
                attachments = input.attachments,
                replyToGuid = input.replyToGuid,
                effectId = effectId,
                sendMode = sendMode
            )) {
                is SendOutcome.Queued -> {
                    messageList.insertOptimisticMessage(outcome.info)
                    composer.clearInput()
                    messageList.scrollToBottom()
                }
                is SendOutcome.Failed -> {
                    _appError.value = outcome.error
                }
            }
        }
    }
}
```

## Files to Modify

| File | Change |
|------|--------|
| `ChatSendDelegate.kt` | Remove setDelegates(), remove delegate references |
| `ChatSearchDelegate.kt` | Remove setMessageListDelegate() |
| `ChatOperationsDelegate.kt` | Remove setMessageListDelegate() |
| `ChatViewModel.kt` | Add coordination methods, remove setDelegates() calls |

## Exit Criteria

- [x] No `setDelegates()` methods in any Chat delegate
- [x] No delegate stores a reference to another delegate
- [x] ChatViewModel orchestrates all cross-delegate interactions
- [x] Control flow is explicit and traceable through ViewModel
- [x] Build passes
- [ ] App functions correctly (manual testing recommended)
- [ ] Tests pass (add coordinator tests - optional)

## Verification Commands

```bash
# Should find NO setDelegates methods
grep -r "fun setDelegates" app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/
grep -r "setMessageListDelegate" app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/

# Should find NO delegate references in delegates
grep -r "private var.*Delegate" app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/

# Should find NO delegate field assignments
grep -r "this\..*Delegate = " app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/
```

## Minimal Interface Narrowing (Alternative)

If a delegate truly needs ONE action from another, use a minimal interface:

```kotlin
// Define narrow interface
interface MessageListScroller {
    fun scrollToMessage(guid: String)
}

// ChatMessageListDelegate implements it
class ChatMessageListDelegate : MessageListScroller {
    override fun scrollToMessage(guid: String) { ... }
}

// Search only sees the narrow interface (injected via factory)
class ChatSearchDelegate @AssistedInject constructor(
    @Assisted private val scroller: MessageListScroller
) {
    fun scrollToResult(index: Int) {
        val guid = searchResults[index].guid
        scroller.scrollToMessage(guid)
    }
}
```

**Note**: Prefer coordinator orchestration. Only use interface narrowing when coordination becomes awkward.

---

**Next Step**: After completing Phase 4, the Chat architecture is clean. Phase 5 (Service Layer Hygiene) is optional but recommended for long-term maintenance.
