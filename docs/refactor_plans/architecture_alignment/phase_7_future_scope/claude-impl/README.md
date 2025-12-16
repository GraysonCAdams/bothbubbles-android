# Phase 7: Implementation Guide (Future Scope)

## Status: AFTER PHASES 2-4 COMPLETE

Do not start this until Chat refactor is stable and shipped.

## Primary Target: ConversationsViewModel

ConversationsViewModel (880+ lines) has the same issues we fixed in Chat:

### Current Problems

```kotlin
// ConversationsViewModel.kt
class ConversationsViewModel @Inject constructor(
    val loading: ConversationLoadingDelegate,
    val actions: ConversationActionsDelegate,
    val observer: ConversationObserverDelegate,
    // ...
) : ViewModel() {
    init {
        // ❌ Same initialize() pattern as Chat
        loading.initialize(viewModelScope)
        actions.initialize(viewModelScope, ::refreshConversations)
        observer.initialize(
            viewModelScope,
            onDataChanged = { refreshConversations() },
            onNewMessage = { handleNewMessage() },
            onMessageUpdated = { handleMessageUpdated() },
            onChatRead = { guid -> markChatRead(guid) }
        )
    }
}
```

### Callback Hell in ConversationObserverDelegate

```kotlin
// ❌ CURRENT: 4 callbacks stored as nullable vars
class ConversationObserverDelegate @Inject constructor(...) {
    private var onDataChanged: (suspend () -> Unit)? = null
    private var onNewMessage: (suspend () -> Unit)? = null
    private var onMessageUpdated: (suspend () -> Unit)? = null
    private var onChatRead: ((String) -> Unit)? = null

    fun initialize(
        scope: CoroutineScope,
        onDataChanged: suspend () -> Unit,
        onNewMessage: suspend () -> Unit,
        onMessageUpdated: suspend () -> Unit,
        onChatRead: (String) -> Unit
    ) {
        this.scope = scope
        this.onDataChanged = onDataChanged
        // ... store all callbacks
    }
}
```

### Target State

```kotlin
// ✅ AFTER: Sealed events instead of callbacks
class ConversationObserverDelegate @AssistedInject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    @Assisted private val scope: CoroutineScope
) {
    sealed interface ConversationEvent {
        object DataChanged : ConversationEvent
        object NewMessage : ConversationEvent
        object MessageUpdated : ConversationEvent
        data class ChatRead(val guid: String) : ConversationEvent
    }

    private val _events = MutableSharedFlow<ConversationEvent>()
    val events: SharedFlow<ConversationEvent> = _events.asSharedFlow()

    init {
        observeChanges()
    }

    private fun observeChanges() {
        scope.launch {
            chatDao.observeAll().collect {
                _events.emit(ConversationEvent.DataChanged)
            }
        }
        // ... other observations emit events
    }

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): ConversationObserverDelegate
    }
}
```

Then in ViewModel:

```kotlin
@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val observerFactory: ConversationObserverDelegate.Factory,
    // ...
) : ViewModel() {
    private val observer = observerFactory.create(viewModelScope)

    init {
        // ✅ Single collection point, pattern matching
        viewModelScope.launch {
            observer.events.collect { event ->
                when (event) {
                    is ConversationEvent.DataChanged -> refreshConversations()
                    is ConversationEvent.NewMessage -> handleNewMessage()
                    is ConversationEvent.MessageUpdated -> handleMessageUpdated()
                    is ConversationEvent.ChatRead -> markChatRead(event.guid)
                }
            }
        }
    }
}
```

## Migration Steps for ConversationsViewModel

### Step 1: Apply AssistedInject to Delegates

Same pattern as Chat delegates:

```kotlin
// ConversationLoadingDelegate
class ConversationLoadingDelegate @AssistedInject constructor(
    private val chatRepository: ChatRepository,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): ConversationLoadingDelegate
    }
}
```

### Step 2: Replace Callbacks with Events

Convert `ConversationObserverDelegate` from callbacks to SharedFlow events.

### Step 3: Update ConversationsViewModel

```kotlin
@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val loadingFactory: ConversationLoadingDelegate.Factory,
    private val actionsFactory: ConversationActionsDelegate.Factory,
    private val observerFactory: ConversationObserverDelegate.Factory,
) : ViewModel() {
    private val loading = loadingFactory.create(viewModelScope)
    private val actions = actionsFactory.create(viewModelScope)
    private val observer = observerFactory.create(viewModelScope)
}
```

## Secondary Target: SetupViewModel DI

### Current Problem

```kotlin
// SetupViewModel.kt
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val api: BothBubblesApi,
    // ...
) : ViewModel() {
    // ❌ Manual construction - NOT DI
    private val permissionsDelegate = PermissionsDelegate(context)
    private val serverConnectionDelegate = ServerConnectionDelegate(settingsDataStore, api)
    private val smsSetupDelegate = SmsSetupDelegate(smsPermissionHelper, settingsDataStore, smsRepository)
}
```

### Target State

```kotlin
// SetupViewModel.kt
class SetupViewModel @Inject constructor(
    // ✅ Injected via Hilt
    val permissions: PermissionsDelegate,
    val serverConnection: ServerConnectionDelegate,
    val smsSetup: SmsSetupDelegate,
    // ...
) : ViewModel()

// Each delegate becomes @Inject-able
class PermissionsDelegate @Inject constructor(
    @ApplicationContext private val context: Context
)

class ServerConnectionDelegate @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val api: BothBubblesApi
)
```

**Note**: Setup delegates don't need AssistedInject if they don't require runtime parameters like `chatGuid`.

## Service Layer Initialization

### Current Problem

```kotlin
// BothBubblesApp.kt
override fun onCreate() {
    super.onCreate()
    appLifecycleTracker.initialize()      // ❌ Manual calls
    activeConversationManager.initialize()
    connectionModeManager.initialize()
}
```

### Option A: AndroidX Startup

```kotlin
// AppInitializer.kt
class AppLifecycleInitializer : Initializer<AppLifecycleTracker> {
    override fun create(context: Context): AppLifecycleTracker {
        val tracker = EntryPointAccessors.fromApplication(
            context,
            AppLifecycleTrackerEntryPoint::class.java
        ).tracker()
        tracker.initialize()
        return tracker
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

// AndroidManifest.xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup">
    <meta-data
        android:name="com.bothbubbles.AppLifecycleInitializer"
        android:value="androidx.startup" />
</provider>
```

### Option B: Eager DI (Simpler)

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object ServiceInitModule {
    @Provides
    @Singleton
    fun provideAppLifecycleTracker(
        @ApplicationContext context: Context
    ): AppLifecycleTracker {
        return AppLifecycleTracker(context).apply { initialize() }
    }
}
```

## Timeline

| Target | When | Effort |
|--------|------|--------|
| ConversationsViewModel | After Chat ships | 1-2 days |
| SetupViewModel DI | After Conversations | 0.5 day |
| Service Initialization | Optional | 0.5 day |

## Files to Modify

### ConversationsViewModel Refactor

| File | Change |
|------|--------|
| `ConversationLoadingDelegate.kt` | Add AssistedInject |
| `ConversationActionsDelegate.kt` | Add AssistedInject |
| `ConversationObserverDelegate.kt` | Replace callbacks with events |
| `ConversationsViewModel.kt` | Use factories, collect events |

### SetupViewModel DI

| File | Change |
|------|--------|
| `PermissionsDelegate.kt` | Add @Inject constructor |
| `ServerConnectionDelegate.kt` | Add @Inject constructor |
| `SmsSetupDelegate.kt` | Add @Inject constructor |
| `SetupViewModel.kt` | Inject delegates, remove manual construction |

## Exit Criteria

- [ ] ConversationsViewModel uses AssistedInject for all delegates
- [ ] No callback-based initialization in ConversationObserverDelegate
- [ ] SetupViewModel uses DI for all delegates
- [ ] Service initialization is safe (either documented order or automated)
- [ ] No `lateinit var` for critical state in any delegate
