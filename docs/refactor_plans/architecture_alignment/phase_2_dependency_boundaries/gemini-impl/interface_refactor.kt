// Phase 2 Implementation: Interface Extraction & Binding

// 1. Define the Interface (app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSender.kt)
// This already exists, but we ensure it covers all UI needs.
interface MessageSender {
    val uploadProgress: StateFlow<UploadProgress?>
    
    suspend fun sendMessage(
        chatGuid: String,
        text: String,
        // ... other params
    ): Result<MessageEntity>
    
    // Add methods here if ChatSendDelegate uses them but they aren't in the interface yet
    // e.g., retryMessage, scheduleMessage
}

// 2. Bind it in Hilt (app/src/main/kotlin/com/bothbubbles/di/MessagingModule.kt)
@Module
@InstallIn(SingletonComponent::class)
abstract class MessagingModule {

    @Binds
    @Singleton
    abstract fun bindMessageSender(
        impl: MessageSendingService
    ): MessageSender
}

// 3. Update Delegate to use Interface (app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatSendDelegate.kt)
class ChatSendDelegate @Inject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender, // <--- CHANGED from MessageSendingService
    private val socketService: SocketService,
    private val soundManager: SoundManager
) {
    // ...
}

// 4. Update other Delegates
// ChatOperationsDelegate.kt
class ChatOperationsDelegate @Inject constructor(
    private val messageSender: MessageSender, // <--- CHANGED
    // ...
)

// 5. Facade Interfaces for Singleton Services
// Define a UI-facing contract that hides the concrete singleton.
interface ActiveConversationTracker {
    val activeChatGuid: StateFlow<String?>
    fun setActiveChat(guid: String?)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ActiveConversationModule {
    @Binds
    @Singleton
    abstract fun bindActiveConversationTracker(
        impl: ActiveConversationManager
    ): ActiveConversationTracker
}

// ChatViewModel now depends on ActiveConversationTracker instead of ActiveConversationManager
class ChatViewModel @Inject constructor(
    private val activeConversationTracker: ActiveConversationTracker,
    // ...
)
