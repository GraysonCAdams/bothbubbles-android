// Phase 3 Implementation: AssistedInject for ChatSendDelegate

// 1. The Delegate (Safe by Construction)
class ChatSendDelegate @AssistedInject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender,
    private val socketService: SocketService,
    private val soundManager: SoundManager,
    @Assisted private val chatGuid: String,        // <--- Injected at runtime
    @Assisted private val scope: CoroutineScope    // <--- Injected at runtime
) {
    // NO lateinit var chatGuid
    // NO lateinit var scope
    // NO initialize() method

    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatSendDelegate
    }

    // Existing methods use 'chatGuid' and 'scope' directly, which are now final properties.
    fun sendMessage(text: String) {
        scope.launch {
            messageSender.sendMessage(chatGuid, text, ...)
        }
    }
    
    // TEMPORARY: Keep setDelegates for Phase 4
    fun setDelegates(messageList: ChatMessageListDelegate, composer: ChatComposerDelegate) {
        // ...
    }
}

// 2. The ViewModel (Coordinator)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatSendDelegateFactory: ChatSendDelegate.Factory,
    private val chatConnectionDelegateFactory: ChatConnectionDelegate.Factory,
    private val chatSendModeManagerFactory: ChatSendModeManager.Factory,
    // ... other factories
) : ViewModel() {

    private val chatGuid: String = checkNotNull(savedStateHandle["chatGuid"])

    // Create delegate immediately
    val send: ChatSendDelegate = chatSendDelegateFactory.create(chatGuid, viewModelScope)
    val connection: ChatConnectionDelegate = chatConnectionDelegateFactory.create(chatGuid, viewModelScope)
    val sendMode: ChatSendModeManager = chatSendModeManagerFactory.create(chatGuid)

    init {
        // NO send.initialize() call needed!
        
        // Wiring still happens here (for now - Phase 4 will fix)
        send.setDelegates(messageList, composer)
    }
}

    // NOTE: Creating ChatConnectionDelegate and ChatSendModeManager via factories ensures
    // the send mode is available synchronously, removing the old "initialize, then set mode later" race.
