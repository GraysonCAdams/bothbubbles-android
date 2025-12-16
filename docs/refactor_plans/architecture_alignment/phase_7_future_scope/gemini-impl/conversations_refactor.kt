// Phase 7 Implementation: ConversationsViewModel Refactor

// Target state for the next big refactor

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val conversationListDelegateFactory: ConversationListDelegate.Factory,
    private val syncDelegateFactory: SyncDelegate.Factory,
    // ...
) : ViewModel() {

    // 1. Use Factories (Phase 3 pattern)
    val listDelegate = conversationListDelegateFactory.create(viewModelScope)
    val syncDelegate = syncDelegateFactory.create(viewModelScope)

    init {
        // 2. Explicit Coordination (Phase 4 pattern)
        // Instead of passing callbacks to the delegate, observe flows
        
        listDelegate.events
            .onEach { event ->
                when (event) {
                    is ListEvent.OpenChat -> navigateToChat(event.guid)
                    is ListEvent.DeleteChat -> syncDelegate.deleteChat(event.guid)
                }
            }
            .launchIn(viewModelScope)
    }
}

// 3. Delegate (AssistedInject)
class ConversationListDelegate @AssistedInject constructor(
    private val chatRepository: ChatRepository,
    @Assisted private val scope: CoroutineScope
) {
    private val _events = MutableSharedFlow<ListEvent>()
    val events = _events.asSharedFlow()
    
    // No callbacks in constructor!
}
