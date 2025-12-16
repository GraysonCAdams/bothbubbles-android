# Wave 1 (Parallel): ChatInputUI Independence

**Prerequisites**: Read `00_shared_conventions.md` first.

**Owned Files**: `ChatInputUI.kt`
**Can Read**: `ChatScreen.kt`, delegate classes

---

## Objective

Make `ChatInputUI` collect its own state internally, so changes to input/composer state don't recompose `ChatScreen`.

---

## Tasks

### 1. Add Delegate Parameters

Update the `ChatInputUI` function signature to accept delegates:

```kotlin
@Composable
fun ChatInputUI(
    // NEW: Delegates for internal collection
    sendDelegate: ChatSendDelegate,
    attachmentDelegate: ChatAttachmentDelegate,
    composerDelegate: ChatComposerDelegate,  // Already exists

    // DEPRECATED: Keep temporarily for backward compatibility
    @Deprecated("Collected internally from sendDelegate")
    sendState: SendState? = null,
    @Deprecated("Collected internally from composerDelegate")
    smartReplySuggestions: List<SuggestionItem>? = null,
    @Deprecated("Collected internally from composerDelegate")
    activePanelState: ActivePanelState? = null,
    @Deprecated("Collected internally from composerDelegate")
    gifPickerState: GifPickerState? = null,
    @Deprecated("Collected internally from composerDelegate")
    gifSearchQuery: String? = null,

    // ... rest of existing parameters ...
)
```

### 2. Add Internal State Collection

At the top of the `ChatInputUI` composable body, add:

```kotlin
@Composable
fun ChatInputUI(
    sendDelegate: ChatSendDelegate,
    attachmentDelegate: ChatAttachmentDelegate,
    composerDelegate: ChatComposerDelegate,
    // ... parameters ...
) {
    // PERF FIX: Collect state internally to avoid ChatScreen recomposition
    val sendStateInternal by sendDelegate.state.collectAsStateWithLifecycle()
    val smartReplySuggestionsInternal by composerDelegate.smartReplySuggestions.collectAsStateWithLifecycle()
    val activePanelStateInternal by composerDelegate.activePanelState.collectAsStateWithLifecycle()
    val gifPickerStateInternal by composerDelegate.gifPickerState.collectAsStateWithLifecycle()
    val gifSearchQueryInternal by composerDelegate.gifSearchQuery.collectAsStateWithLifecycle()

    // Use internal values, falling back to deprecated params during transition
    val effectiveSendState = sendStateInternal
    val effectiveSmartReplies = smartReplySuggestionsInternal
    val effectiveActivePanelState = activePanelStateInternal
    val effectiveGifPickerState = gifPickerStateInternal
    val effectiveGifSearchQuery = gifSearchQueryInternal

    // ... rest of composable using effective* values ...
}
```

### 3. Move replyingToMessage Calculation

Move the `replyingToMessage` derivedStateOf calculation from `ChatScreen.kt` into `ChatInputUI`:

```kotlin
// PERF FIX: Compute replyingToMessage locally to avoid passing full messages list
val messages by messageListDelegate.messagesState.collectAsStateWithLifecycle()
val replyingToMessage by remember {
    derivedStateOf {
        effectiveSendState.replyingToGuid?.let { guid ->
            messages.firstOrNull { it.guid == guid }
        }
    }
}
```

**Note**: This requires adding `messageListDelegate: ChatMessageListDelegate` parameter, OR passing the messages flow directly if preferred.

### 4. Add Required Imports

```kotlin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import com.bothbubbles.ui.chat.delegates.ChatSendDelegate
import com.bothbubbles.ui.chat.delegates.ChatAttachmentDelegate
```

### 5. Update Internal References

Replace all uses of the deprecated parameters with the `effective*` values:

- `sendState` → `effectiveSendState`
- `smartReplySuggestions` → `effectiveSmartReplies`
- `activePanelState` → `effectiveActivePanelState`
- `gifPickerState` → `effectiveGifPickerState`
- `gifSearchQuery` → `effectiveGifSearchQuery`

---

## Verification

- [ ] All new collections use `collectAsStateWithLifecycle()`
- [ ] All internal collections have `// PERF FIX:` comments
- [ ] `replyingToMessage` uses `derivedStateOf`
- [ ] Old parameters are marked `@Deprecated`
- [ ] Build succeeds: `./gradlew assembleDebug`
- [ ] No lint warnings about unused parameters (deprecated ones are expected)

---

## Notes

- Do NOT modify `ChatScreen.kt` - those changes happen in `03_sequential_chatscreen.md`
- The deprecated parameters allow `ChatScreen.kt` to continue working until the sequential cutover
- After Wave 2 (sequential), the deprecated parameters will be removed
