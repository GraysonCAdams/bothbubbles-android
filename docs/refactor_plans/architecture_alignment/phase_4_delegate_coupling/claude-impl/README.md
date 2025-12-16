# Phase 4: Implementation Guide (Delegate Coupling Reduction)

## Goal

Remove `setDelegates()` pattern. Delegates should not hold references to each other. Coordination happens in ChatViewModel (the coordinator).

## Prerequisites

- Phase 3 complete (all delegates use AssistedInject)
- `setDelegates()` methods still exist but are targeted for removal

## Current Coupling (To Remove)

```kotlin
// ChatViewModel init block - THIS IS THE PROBLEM
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

## Strategy: Coordinator-Only Orchestration

The ChatViewModel becomes the single point of coordination. Instead of delegates calling each other, the ViewModel orchestrates the flow.

## Migration Example: ChatSendDelegate

### Current Flow (With Coupling)

```
User taps Send
    → ChatSendDelegate.sendCurrentMessage()
        → composerDelegate.getInput()           // Delegate calls delegate
        → connectionDelegate.getCurrentMode()   // Delegate calls delegate
        → messageListDelegate.insertOptimistic() // Delegate calls delegate
        → composerDelegate.clearInput()         // Delegate calls delegate
```

### Target Flow (Coordinator Orchestration)

```
User taps Send
    → ChatViewModel.sendMessage()               // ViewModel coordinates
        → composer.getInput()                   // ViewModel calls delegate
        → connection.getCurrentMode()           // ViewModel calls delegate
        → messageList.insertOptimistic(...)     // ViewModel calls delegate
        → send.queueMessage(...)                // ViewModel calls delegate
        → composer.clearInput()                 // ViewModel calls delegate
```

## Code Transformation

### Step 1: ChatSendDelegate - Remove setDelegates()

```kotlin
// BEFORE (Phase 3 state - has setDelegates)
class ChatSendDelegate @AssistedInject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender,
    private val socketConnection: SocketConnection,
    private val soundManager: SoundManager,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {
    // ❌ REMOVE THESE
    private var messageListDelegate: ChatMessageListDelegate? = null
    private var composerDelegate: ChatComposerDelegate? = null
    private var chatInfoDelegate: ChatInfoDelegate? = null
    private var connectionDelegate: ChatConnectionDelegate? = null
    private var onDraftCleared: (() -> Unit)? = null

    // ❌ REMOVE THIS METHOD
    fun setDelegates(...) { ... }

    // ❌ THIS METHOD USES DELEGATE REFERENCES
    fun sendCurrentMessage(effectId: String? = null) {
        val composer = composerDelegate ?: return
        val messageList = messageListDelegate ?: return
        // ... uses delegates directly
    }
}

// AFTER (Phase 4 - no delegate references)
class ChatSendDelegate @AssistedInject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender,
    private val socketConnection: SocketConnection,
    private val soundManager: SoundManager,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {
    // ✅ NO delegate references

    // ✅ NEW: Pure method that takes all inputs as parameters
    suspend fun queueMessage(
        text: String,
        attachments: List<PendingAttachmentInput>,
        replyToGuid: String?,
        effectId: String?,
        sendMode: ChatSendMode
    ): QueuedMessageInfo {
        // Queue to database, return info for optimistic UI
        val tempGuid = UUID.randomUUID().toString()
        pendingMessageRepository.queueMessage(...)

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
```

### Step 2: ChatViewModel - Add Coordination

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    // ... factories
) : ViewModel() {

    // ✅ NEW: ViewModel coordinates the send flow
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

            // Step 6: Play sound
            soundManager.playMessageSentSound()
        }
    }
}
```

## Alternative: Workflow Extraction

For complex multi-step flows, extract to a dedicated Workflow class:

```kotlin
/**
 * Coordinates the message sending flow across multiple delegates.
 * This keeps ChatViewModel focused on UI concerns.
 */
class SendMessageWorkflow @Inject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender,
    private val soundManager: SoundManager
) {
    sealed interface SendOutcome {
        data class Queued(val info: QueuedMessageInfo) : SendOutcome
        data class Failed(val error: Throwable) : SendOutcome
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
            pendingMessageRepository.queueMessage(...)

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
            SendOutcome.Failed(e)
        }
    }
}
```

Then in ViewModel:

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
                }
                is SendOutcome.Failed -> {
                    // Handle error
                }
            }
        }
    }
}
```

## Migration: ChatSearchDelegate

### Before

```kotlin
class ChatSearchDelegate @AssistedInject constructor(...) {
    private var messageListDelegate: ChatMessageListDelegate? = null

    fun setMessageListDelegate(messageList: ChatMessageListDelegate) {
        this.messageListDelegate = messageList
    }

    fun scrollToSearchResult(index: Int) {
        // Uses messageListDelegate to scroll
        messageListDelegate?.scrollToMessage(...)
    }
}
```

### After

```kotlin
class ChatSearchDelegate @AssistedInject constructor(...) {
    // ✅ NO delegate reference

    // ✅ Return data, let ViewModel coordinate the scroll
    fun getSearchResultPosition(index: Int): Int? {
        return searchResults.getOrNull(index)?.position
    }
}

// In ChatViewModel:
fun scrollToSearchResult(index: Int) {
    val position = search.getSearchResultPosition(index) ?: return
    messageList.scrollToPosition(position)
}
```

## Migration: ChatOperationsDelegate

### Before

```kotlin
class ChatOperationsDelegate @AssistedInject constructor(...) {
    private var messageListDelegate: ChatMessageListDelegate? = null

    fun setMessageListDelegate(messageList: ChatMessageListDelegate) {
        this.messageListDelegate = messageList
    }

    fun toggleReaction(messageGuid: String, reaction: String) {
        scope.launch {
            val result = messageSender.sendReaction(...)
            messageListDelegate?.refreshMessage(messageGuid)  // ❌ Direct call
        }
    }
}
```

### After

```kotlin
class ChatOperationsDelegate @AssistedInject constructor(...) {
    // ✅ NO delegate reference

    // ✅ Return result, let ViewModel coordinate refresh
    suspend fun toggleReaction(
        messageGuid: String,
        reaction: String
    ): Result<Unit> {
        return messageSender.sendReaction(...)
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

## Minimal Interface Narrowing (Optional)

If a delegate genuinely needs to trigger ONE action on another delegate, use a minimal interface:

```kotlin
// Instead of passing full ChatMessageListDelegate
interface MessageListScroller {
    fun scrollToMessage(guid: String)
}

// ChatMessageListDelegate implements it
class ChatMessageListDelegate : MessageListScroller {
    override fun scrollToMessage(guid: String) { ... }
}

// Search only sees the narrow interface
class ChatSearchDelegate @AssistedInject constructor(
    @Assisted private val scroller: MessageListScroller  // Narrow dependency
) {
    fun scrollToResult(index: Int) {
        val guid = searchResults[index].guid
        scroller.scrollToMessage(guid)
    }
}
```

**Note**: Only use this if coordinator orchestration is genuinely awkward. Prefer coordinator-only by default.

## Files to Modify

| File | Change |
|------|--------|
| `ChatSendDelegate.kt` | Remove setDelegates(), remove delegate references |
| `ChatSearchDelegate.kt` | Remove setMessageListDelegate() |
| `ChatOperationsDelegate.kt` | Remove setMessageListDelegate() |
| `ChatViewModel.kt` | Add coordination methods, remove setDelegates() calls |

## Verification

After Phase 4:

```bash
# Should find NO setDelegates methods
grep -r "fun setDelegates" app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/
grep -r "setMessageListDelegate" app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/

# Should find NO delegate references in delegates
grep -r "private var.*Delegate" app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/
```

## Exit Criteria

- [ ] No `setDelegates()` methods in any delegate
- [ ] No delegate stores a reference to another delegate
- [ ] ChatViewModel orchestrates all cross-delegate interactions
- [ ] Control flow is traceable through ViewModel
- [ ] Build passes
- [ ] App functions correctly
