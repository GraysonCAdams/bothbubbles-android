# Refactor Plan: ChatViewModel Composer Delegate

**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatViewModel.kt`
**Goal:** Extract composer-related state and logic into a specialized delegate to reduce ViewModel complexity.

## Design Philosophy: The Delegate Pattern
- **State Ownership:** The Delegate owns its internal state (`MutableStateFlow`) and exposes a read-only public state (`StateFlow`).
- **Scoped Logic:** All business logic related to the domain (Composer) lives inside the Delegate.
- **Dependency Injection:** The Delegate receives all necessary repositories and services via `@Inject` constructor.
- **ViewModel as Facade:** The `ChatViewModel` initializes the delegate and forwards relevant UI events to it, exposing the delegate's state directly to the UI.

## Instructions

### 1. Define the State
Ensure `ComposerState` (if not already defined) covers all UI needs.
```kotlin
data class ComposerState(
    val text: String = "",
    val attachments: List<PendingAttachmentInput> = emptyList(),
    val quality: AttachmentQuality = AttachmentQuality.STANDARD,
    val activePanel: ComposerPanel = ComposerPanel.None,
    val isSending: Boolean = false,
    val replyToMessage: MessageUiModel? = null,
    // ... other relevant fields
)
```

### 2. Create the Delegate Class
Create: `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatComposerDelegate.kt`

**Structure:**
```kotlin
class ChatComposerDelegate @Inject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val attachmentLimitsProvider: AttachmentLimitsProvider,
    private val attachmentPreloader: AttachmentPreloader,
    private val settingsDataStore: SettingsDataStore,
    // Inject CoroutineScope if needed, or pass it in init
) {
    // 1. Internal Mutable State
    private val _draftText = MutableStateFlow("")
    private val _pendingAttachments = MutableStateFlow<List<PendingAttachmentInput>>(emptyList())
    // ... other mutable flows

    // 2. Complex State Derivation (The "Combine" Block)
    val state: StateFlow<ComposerState> = combine(
        _draftText,
        _pendingAttachments,
        // ... other flows
    ) { text, attachments, ... ->
        ComposerState(text, attachments, ...)
    }.stateIn(scope, SharingStarted.Eagerly, ComposerState())

    // 3. Public Actions
    fun setDraft(text: String) { ... }
    fun addAttachment(attachment: PendingAttachmentInput) { ... }
    fun removeAttachment(uri: Uri) { ... }
}
```

### 3. Move Logic from ChatViewModel
Move the following from `ChatViewModel.kt` to the new delegate:
- **Fields:** `_draftText`, `_pendingAttachments`, `_attachmentQuality`, `_activePanel`.
- **Logic:** The complex `combine` block that currently creates `composerState`.
- **Methods:** `setDraft`, `addAttachment`, `removeAttachment`, `setQuality`, `setActivePanel`.

### 4. Integrate into ChatViewModel
1.  Inject `ChatComposerDelegate` into `ChatViewModel`.
2.  Delete the moved fields and methods from `ChatViewModel`.
3.  Expose the state: `val composerState = composerDelegate.state`.
4.  Forward calls: `fun setDraft(t: String) = composerDelegate.setDraft(t)`.

## Verification
- **Compilation:** Ensure `ChatViewModel` compiles without the moved fields.
- **Functionality:** Verify typing in the composer updates the UI state.
- **Architecture:** Ensure `ChatViewModel` no longer imports `PendingMessageRepository` or `AttachmentLimitsProvider` (unless used elsewhere).
