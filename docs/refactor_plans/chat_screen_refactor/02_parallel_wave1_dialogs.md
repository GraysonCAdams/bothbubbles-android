# Wave 1 (Parallel): Dialogs & TopBar Independence

**Prerequisites**: Read `00_shared_conventions.md` first.

**Owned Files**: `ChatDialogsHost.kt`, `ChatTopBar.kt`, `AnimatedThreadOverlay.kt`
**Can Read**: `ChatScreen.kt`, delegate classes

---

## Objective

Make dialog and overlay components collect their own state internally, so opening dialogs doesn't recompose the message list.

---

## Tasks

### 1. Update ChatTopBar

#### Add Delegate Parameter

```kotlin
@Composable
fun ChatTopBar(
    // NEW: Delegate for internal collection
    operationsDelegate: ChatOperationsDelegate,
    chatInfoDelegate: ChatInfoDelegate,

    // DEPRECATED: Keep temporarily
    @Deprecated("Collected internally from operationsDelegate")
    operationsState: OperationsState? = null,
    @Deprecated("Collected internally from chatInfoDelegate")
    infoState: ChatInfoState? = null,

    // ... rest of existing parameters ...
)
```

#### Add Internal Collection

```kotlin
@Composable
fun ChatTopBar(
    operationsDelegate: ChatOperationsDelegate,
    chatInfoDelegate: ChatInfoDelegate,
    // ...
) {
    // PERF FIX: Collect state internally to avoid ChatScreen recomposition
    val operationsStateInternal by operationsDelegate.state.collectAsStateWithLifecycle()
    val infoStateInternal by chatInfoDelegate.state.collectAsStateWithLifecycle()

    val effectiveOperationsState = operationsStateInternal
    val effectiveInfoState = infoStateInternal

    // ... rest of composable using effective* values ...
}
```

---

### 2. Update ChatDialogsHost

#### Add Delegate/ViewModel Parameter

```kotlin
@Composable
fun ChatDialogsHost(
    viewModel: ChatViewModel,  // Already has this
    context: Context,

    // NEW: For internal collection
    connectionDelegate: ChatConnectionDelegate,

    // DEPRECATED: Keep temporarily
    @Deprecated("Collected internally from connectionDelegate")
    connectionState: ConnectionState? = null,
    @Deprecated("Collected internally via viewModel.getForwardableChats()")
    forwardableChats: List<ChatUiModel>? = null,

    // ... rest of existing parameters ...
)
```

#### Add Internal Collection

```kotlin
@Composable
fun ChatDialogsHost(
    viewModel: ChatViewModel,
    connectionDelegate: ChatConnectionDelegate,
    // ...
) {
    // PERF FIX: Collect connection state internally
    val connectionStateInternal by connectionDelegate.state.collectAsStateWithLifecycle()

    // PERF FIX: Only collect forwardable chats when dialog is shown
    val forwardableChatsInternal by remember(showForwardDialog) {
        if (showForwardDialog) {
            viewModel.getForwardableChats()
        } else {
            flowOf(emptyList())
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    // PERF FIX: Move WhatsApp check to LaunchedEffect (not during composition)
    var isWhatsAppAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isWhatsAppAvailable = viewModel.operations.isWhatsAppAvailable(context)
    }

    val effectiveConnectionState = connectionStateInternal

    // ... rest of composable ...
}
```

---

### 3. Update AnimatedThreadOverlay

#### Add Delegate Parameter

```kotlin
@Composable
fun AnimatedThreadOverlay(
    // NEW: Delegate for internal collection
    threadDelegate: ChatThreadDelegate,

    // DEPRECATED: Keep temporarily
    @Deprecated("Collected internally from threadDelegate")
    threadOverlayState: ThreadOverlayState? = null,

    // ... rest of existing parameters ...
)
```

#### Add Internal Collection

```kotlin
@Composable
fun AnimatedThreadOverlay(
    threadDelegate: ChatThreadDelegate,
    // ...
) {
    // PERF FIX: Collect thread state internally
    val threadStateInternal by threadDelegate.state.collectAsStateWithLifecycle()

    val effectiveThreadState = threadStateInternal

    // Only render if thread is active
    if (effectiveThreadState.isActive) {
        // ... overlay content ...
    }
}
```

---

### 4. Add Required Imports

For all three files:

```kotlin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.flowOf
```

---

## Verification

- [ ] All new collections use `collectAsStateWithLifecycle()`
- [ ] All internal collections have `// PERF FIX:` comments
- [ ] `forwardableChats` only collected when `showForwardDialog == true`
- [ ] `isWhatsAppAvailable` moved to `LaunchedEffect` (not synchronous)
- [ ] Old parameters marked `@Deprecated`
- [ ] Build succeeds: `./gradlew assembleDebug`

---

## Notes

- Do NOT modify `ChatScreen.kt` - those changes happen in `03_sequential_chatscreen.md`
- The `isWhatsAppAvailable` check was a synchronous package manager call during composition - moving it to `LaunchedEffect` prevents main thread blocking
- Conditional collection of `forwardableChats` prevents unnecessary database queries when the forward dialog isn't shown
