# Refactor Plan: ChatViewModel Message List Delegate

**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatViewModel.kt`
**Goal:** Extract message paging, data source coordination, and list updates into a specialized delegate.

## Design Philosophy: The Delegate Pattern
- **State Ownership:** The Delegate owns `_messagesState` and the `MessagePagingController`.
- **Scoped Logic:** All logic for handling new messages, updates, and paging lives here.
- **ViewModel as Facade:** `ChatViewModel` exposes `messagesState` from the delegate.

## Instructions

### 1. Create the Delegate Class
Create: `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatMessageListDelegate.kt`

**Structure:**
```kotlin
class ChatMessageListDelegate @Inject constructor(
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val smartReplyService: SmartReplyService,
    // Inject CoroutineScope
) {
    // 1. Internal State
    private val _messagesState = MutableStateFlow<StableList<MessageUiModel>>(StableList(emptyList()))
    val messagesState = _messagesState.asStateFlow()
    
    private val pagingController: MessagePagingController = ...

    // 2. Public Actions
    fun loadMoreMessages() { ... }
    fun handleSocketMessage(event: SocketEvent.Message) { ... }
    fun refresh() { ... }
}
```

### 2. Move Logic from ChatViewModel
Move the following from `ChatViewModel.kt`:
- **Fields:** `_messagesState`, `pagingController`, `dataSource`.
- **Logic:** 
    - `loadMessages()`
    - `onNewMessage()`
    - `onMessageUpdated()`
    - `onMessageDeleted()`
    - The `SocketEvent.Message` listener block.

### 3. Integrate into ChatViewModel
1.  Inject `ChatMessageListDelegate`.
2.  Remove moved fields/methods.
3.  Expose `val messagesState = messageListDelegate.messagesState`.
4.  Delegate socket events: `messageListDelegate.handleSocketMessage(event)`.

## Verification
- **Functionality:** Chat history loads correctly. New messages appear at the bottom.
- **Performance:** Paging (scrolling up) still works.
