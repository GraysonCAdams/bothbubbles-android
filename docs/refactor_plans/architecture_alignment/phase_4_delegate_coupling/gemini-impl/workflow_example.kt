// Phase 4 Implementation: Workflow Extraction & Coupling Removal

// Goal: Remove `send.setDelegates(messageList)`
// The "Send Message" flow involves:
// 1. Optimistic UI insert (MessageList)
// 2. Clear Draft (Composer)
// 3. Actual Network Send (Service)
// 4. Scroll to bottom (MessageList)

// Option 1.5: Workflow Class (Encapsulated Logic)

class SendMessageWorkflow @Inject constructor(
    private val messageSender: MessageSender,
    private val pendingMessageRepository: PendingMessageRepository
) {
    // Returns a Flow of events that the ViewModel coordinates
    fun execute(
        chatGuid: String, 
        text: String, 
        scope: CoroutineScope
    ): Flow<SendEvent> = flow {
        // 1. Create Pending Message
        val tempGuid = UUID.randomUUID().toString()
        emit(SendEvent.OptimisticInsert(tempGuid, text))
        
        // 2. Send
        val result = messageSender.sendMessage(chatGuid, text, providedTempGuid = tempGuid)
        
        if (result.isSuccess) {
            emit(SendEvent.Success(tempGuid))
        } else {
            emit(SendEvent.Failure(tempGuid, result.exceptionOrNull()?.message))
        }
    }
    
    sealed interface SendEvent {
        data class OptimisticInsert(val tempGuid: String, val text: String) : SendEvent
        data class Success(val tempGuid: String) : SendEvent
        data class Failure(val tempGuid: String, val error: String?) : SendEvent
    }
}

// ViewModel Usage
class ChatViewModel {
    fun sendMessage(text: String) {
        // 1. Trigger Workflow
        sendMessageWorkflow.execute(chatGuid, text, viewModelScope)
            .onEach { event ->
                when (event) {
                    is SendEvent.OptimisticInsert -> {
                        // Coordinator tells MessageList to update
                        messageList.addPendingMessage(event)
                        // Coordinator tells Composer to clear
                        composer.clearDraft()
                        // Coordinator tells MessageList to scroll
                        messageList.scrollToBottom()
                    }
                    is SendEvent.Failure -> {
                        _appError.value = event.error
                    }
                }
            }
            .launchIn(viewModelScope)
    }
}

// Result:
// ChatSendDelegate is GONE (or reduced to simple wrapper).
// ChatViewModel coordinates explicit actions.
// No hidden `send.messageList.scrollToBottom()` calls inside a delegate.
