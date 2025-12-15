# Deep Dive: Chat Composer Typing Sluggishness

**Date:** December 14, 2025
**Status:** Research Complete
**Extends:** `RESEARCH_TYPING_SLUGGISHNESS.md`

This document provides a deeper technical analysis of typing performance issues, including **critical issues** not covered in the original research document.

---

## Executive Summary

The typing sluggishness is caused by a cascade of inefficiencies across three layers:

| Priority | Issue | Location | Impact |
|----------|-------|----------|--------|
| **CRITICAL** | `remember` block with 10 dependencies copies state on every keystroke | `ChatScreen.kt:822-850` | Full recomposition cascade |
| **HIGH** | `combine` block executes memoization logic before `distinctUntilChanged` | `ChatViewModel.kt:252-317` | Wasted CPU on every keystroke |
| **MEDIUM** | ComposerState has 23 fields; structural equality is expensive | `ComposerState.kt` | Slower comparison checks |
| **MEDIUM** | Lambda recreation in `TextInputContent` | `ChatComposer.kt:343-364` | Unnecessary child recomposition |

---

## Critical Issue #1: `adjustedComposerState` Remember Block

**File:** `ChatScreen.kt` (lines 822-850)

This is the **most impactful** performance issue.

### The Problem

```kotlin
// ChatScreen.kt:819-850
val composerState by viewModel.composerState.collectAsStateWithLifecycle()

val adjustedComposerState = remember(
    composerState,          // <-- Changes on EVERY keystroke
    isRecording,
    isPreviewingVoiceMemo,
    recordingDuration,
    amplitudeHistory,
    isNoiseCancellationEnabled,
    isPlayingVoiceMemo,
    playbackPosition,
    recordingFile
) {
    composerState.copy(
        inputMode = when {
            isRecording -> ComposerInputMode.VOICE_RECORDING
            isPreviewingVoiceMemo -> ComposerInputMode.VOICE_PREVIEW
            else -> ComposerInputMode.TEXT
        },
        recordingState = if (isRecording || isPreviewingVoiceMemo) {
            RecordingState(
                durationMs = recordingDuration,
                amplitudeHistory = amplitudeHistory,
                isNoiseCancellationEnabled = isNoiseCancellationEnabled,
                playbackPositionMs = playbackPosition,
                isPlaying = isPlayingVoiceMemo,
                recordedUri = recordingFile?.toUri()
            )
        } else null
    )
}
```

### Why This Is Critical

1. **The `remember` key includes `composerState`** - Since `composerState.text` changes on every keystroke, the entire `composerState` object changes (it's a data class, so structural equality requires a new instance).

2. **The block re-executes on every keystroke** - Even though we're only changing text, the entire `remember` block runs, including:
   - The `when` expression for `inputMode`
   - The `if` expression for `recordingState`
   - The `composerState.copy()` call (creates new object with 23 fields)

3. **`composerState.copy()` is expensive** - ComposerState has 23 fields. Kotlin's `.copy()` must:
   - Read all 23 current values
   - Apply the overrides
   - Construct a new object
   - This happens on every single character typed

4. **The result triggers `ChatComposer` recomposition** - The new `adjustedComposerState` is passed to `ChatComposer(state = adjustedComposerState, ...)` at line 852, triggering its entire recomposition tree.

### Proposed Fix

Separate text-only state from voice recording state:

```kotlin
// Option 1: Don't include composerState in remember keys when
// only voice recording state matters

// For voice recording overlay, only depend on voice-specific state
val voiceRecordingState = remember(
    isRecording,
    isPreviewingVoiceMemo,
    recordingDuration,
    // ... other voice state
) {
    // Only create voice state when needed
    if (isRecording || isPreviewingVoiceMemo) {
        RecordingState(...)
    } else null
}

// Pass composerState directly to ChatComposer
// Let ChatComposer handle voice overlay internally
ChatComposer(
    state = composerState,
    voiceRecordingState = voiceRecordingState,
    onEvent = { ... }
)
```

```kotlin
// Option 2: Use derivedStateOf for the computed fields
val adjustedInputMode by remember {
    derivedStateOf {
        when {
            isRecording -> ComposerInputMode.VOICE_RECORDING
            isPreviewingVoiceMemo -> ComposerInputMode.VOICE_PREVIEW
            else -> ComposerInputMode.TEXT
        }
    }
}

// Then read inputMode separately in ChatComposer
// This prevents recomputation when only text changes
```

---

## Critical Issue #2: Combine Block Before DistinctUntilChanged

**File:** `ChatViewModel.kt` (lines 252-317)

### The Problem

```kotlin
// ChatViewModel.kt:252-317
val composerState: StateFlow<ComposerState> = combine(
    composerRelevantState,
    _draftText,             // <-- Emits on every keystroke
    _pendingAttachments,
    _attachmentQuality,
    _activePanel
) { relevant, text, attachments, quality, panel ->
    // THIS ENTIRE BLOCK RUNS ON EVERY KEYSTROKE

    // Memoized attachment transformation
    val attachmentItems = if (attachments === _lastAttachmentInputs && quality == _lastAttachmentQuality) {
        _cachedAttachmentItems
    } else {
        attachments.map { /* transform */ }.also { /* cache */ }
    }

    // Memoized reply preview transformation
    val replyPreview = relevant.replyToMessage?.let { msg ->
        if (msg.guid == _lastReplyMessageGuid && _cachedReplyPreview != null) {
            _cachedReplyPreview
        } else {
            MessagePreview.fromMessageUiModel(msg).also { /* cache */ }
        }
    } ?: run { /* clear cache */ }

    // Construct new ComposerState - 14 parameters!
    ComposerState(
        text = text,
        attachments = attachmentItems,
        // ... 12 more fields
    )
}
    .distinctUntilChanged()  // <-- Too late! Work already done
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ComposerState())
```

### Why This Is High Impact

1. **The combine lambda runs BEFORE `distinctUntilChanged`** - Every keystroke triggers:
   - Reading 5 flow values
   - Referential equality checks for attachment cache
   - String equality check for reply message GUID
   - ComposerState constructor call (14 parameters)

2. **Memoization checks are still work** - While the cache avoids re-mapping attachments, the cache checks (`===`, `==`) still execute on every keystroke.

3. **`distinctUntilChanged` comes too late** - By the time it filters duplicate emissions, the combine block has already done all its work.

### Evidence of Optimization Attempt

The code already has memoization caches (lines 244-249):
```kotlin
@Volatile private var _cachedAttachmentItems: List<AttachmentItem> = emptyList()
@Volatile private var _lastAttachmentInputs: List<PendingAttachmentInput>? = null
@Volatile private var _lastAttachmentQuality: AttachmentQuality? = null
@Volatile private var _cachedReplyPreview: MessagePreview? = null
@Volatile private var _lastReplyMessageGuid: String? = null
```

This shows awareness of the problem, but the solution doesn't prevent the combine block from running.

### Proposed Fix

Use `distinctUntilChanged` on individual flows BEFORE combine:

```kotlin
val composerState: StateFlow<ComposerState> = combine(
    composerRelevantState,  // Already has distinctUntilChanged
    _draftText.distinctUntilChanged(),  // <-- Add this
    _pendingAttachments,    // Changes rarely
    _attachmentQuality,     // Changes rarely
    _activePanel            // Changes rarely
) { ... }
```

Or split text-only updates into a separate, lightweight path:

```kotlin
// Fast path: text-only updates
val textOnlyState: StateFlow<String> = _draftText.asStateFlow()

// Slow path: full composer state (without text)
val composerMetadata: StateFlow<ComposerMetadata> = combine(
    composerRelevantState,
    _pendingAttachments,
    _attachmentQuality,
    _activePanel
) { ... }
    .distinctUntilChanged()
    .stateIn(...)

// In ChatComposer: read both separately
// Text changes don't trigger metadata recomposition
```

---

## Medium Issue #3: ComposerState Has 23 Fields

**File:** `ComposerState.kt`

### The Problem

```kotlin
@Immutable
data class ComposerState(
    // Text Input (3 fields)
    val text: String = "",
    val cursorPosition: Int = 0,
    val isTextFieldFocused: Boolean = false,

    // Attachments (2 fields)
    val attachments: List<AttachmentItem> = emptyList(),
    val attachmentWarning: ComposerAttachmentWarning? = null,

    // Reply (1 field)
    val replyToMessage: MessagePreview? = null,

    // Smart Replies (2 fields)
    val smartReplies: List<String> = emptyList(),
    val showSmartReplies: Boolean = false,

    // Send Mode (3 fields)
    val sendMode: ChatSendMode = ChatSendMode.IMESSAGE,
    val canToggleSendMode: Boolean = false,
    val isSendModeAnimating: Boolean = false,

    // Panels (1 field)
    val activePanel: ComposerPanel = ComposerPanel.None,

    // Input Mode (1 field)
    val inputMode: ComposerInputMode = ComposerInputMode.TEXT,

    // Voice Recording (1 field)
    val recordingState: RecordingState? = null,

    // Tutorial (1 field)
    val tutorialState: ComposerTutorialState = ComposerTutorialState.Hidden,

    // Sending (2 fields)
    val isSending: Boolean = false,
    val sendProgress: Float? = null,

    // SMS-specific (2 fields)
    val smsInputBlocked: Boolean = false,
    val isLocalSmsChat: Boolean = false,

    // Image quality (2 fields)
    val currentImageQuality: AttachmentQuality = AttachmentQuality.DEFAULT,
    val showQualitySheet: Boolean = false
)
```

### Impact

1. **Data class `equals()` compares ALL 23 fields** - Even with `@Immutable`, every `distinctUntilChanged` check must compare all fields.

2. **Computed properties add overhead** - 10 computed properties (`canSend`, `isPickerExpanded`, `showVoiceButton`, etc.) are re-evaluated on every read during recomposition.

3. **`.copy()` operations are expensive** - Creating a new ComposerState requires passing/defaulting 23 parameters.

### Proposed Fix

Split into domain-specific sub-states:

```kotlin
// Text input state - changes frequently
data class TextInputState(
    val text: String = "",
    val cursorPosition: Int = 0,
    val isTextFieldFocused: Boolean = false
)

// Attachment state - changes occasionally
data class AttachmentState(
    val attachments: List<AttachmentItem> = emptyList(),
    val warning: ComposerAttachmentWarning? = null,
    val currentQuality: AttachmentQuality = AttachmentQuality.DEFAULT
)

// Mode state - changes rarely
data class ComposerModeState(
    val sendMode: ChatSendMode = ChatSendMode.IMESSAGE,
    val inputMode: ComposerInputMode = ComposerInputMode.TEXT,
    val activePanel: ComposerPanel = ComposerPanel.None
)

// Then compose them
@Immutable
data class ComposerState(
    val textInput: TextInputState = TextInputState(),
    val attachments: AttachmentState = AttachmentState(),
    val mode: ComposerModeState = ComposerModeState(),
    // ... other grouped states
)
```

This allows more granular `distinctUntilChanged` filtering and smaller `.copy()` operations.

---

## Medium Issue #4: Lambda Recreation in TextInputContent

**File:** `ChatComposer.kt` (lines 343-364)

This is already documented in `RESEARCH_TYPING_SLUGGISHNESS.md` but is worth repeating.

### The Problem

```kotlin
@Composable
private fun TextInputContent(
    state: ComposerState,  // <-- Full state passed in
    onEvent: (ComposerEvent) -> Unit,
    onGalleryClick: () -> Unit
) {
    ComposerTextField(
        text = state.text,
        onTextChange = { onEvent(ComposerEvent.TextChanged(it)) },
        sendMode = state.sendMode,
        isEnabled = !state.smsInputBlocked,
        onSmsBlockedClick = { onEvent(ComposerEvent.SmsInputBlockedTapped) },
        onFocusChanged = { onEvent(ComposerEvent.TextFieldFocusChanged(it)) },
        leadingContent = {
            // NEW LAMBDA INSTANCE ON EVERY RECOMPOSITION
            ComposerActionButtons(
                isExpanded = state.isPickerExpanded,
                onClick = { onEvent(ComposerEvent.ToggleMediaPicker) }
            )
        },
        trailingContent = {
            // NEW LAMBDA INSTANCE ON EVERY RECOMPOSITION
            ComposerMediaButtons(
                showCamera = state.text.isBlank(),
                onCameraClick = { onEvent(ComposerEvent.OpenCamera) },
                onImageClick = onGalleryClick,
                onEmojiClick = { onEvent(ComposerEvent.ToggleEmojiPicker) }
            )
        }
    )
}
```

### Fix (as documented)

```kotlin
@Composable
private fun TextInputContent(...) {
    val isPickerExpanded = state.isPickerExpanded
    val showCamera = state.text.isBlank()

    val leadingContent = remember(isPickerExpanded) {
        movableContentOf {
            ComposerActionButtons(
                isExpanded = isPickerExpanded,
                onClick = { onEvent(ComposerEvent.ToggleMediaPicker) }
            )
        }
    }

    val trailingContent = remember(showCamera) {
        movableContentOf {
            ComposerMediaButtons(
                showCamera = showCamera,
                onCameraClick = { onEvent(ComposerEvent.OpenCamera) },
                onImageClick = onGalleryClick,
                onEmojiClick = { onEvent(ComposerEvent.ToggleEmojiPicker) }
            )
        }
    }

    ComposerTextField(
        // ...
        leadingContent = leadingContent,
        trailingContent = trailingContent
    )
}
```

---

## Full Data Flow: One Keystroke

Here's the complete sequence of operations when a user types one character:

```
1. User types 'H'
   │
   ├─► BasicTextField.onValueChange("Hello" -> "HelloH")
   │
   ├─► ComposerEvent.TextChanged("HelloH") emitted
   │
   ├─► ChatViewModel.onComposerEvent() called
   │      └─► _draftText.value = "HelloH"  [MutableStateFlow emission]
   │
   ├─► combine() block in ChatViewModel triggered  [WORK STARTS]
   │      ├─► Read 5 flows
   │      ├─► Check attachment cache (referential equality)
   │      ├─► Check reply cache (string equality)
   │      └─► Construct new ComposerState(text = "HelloH", ... 22 other fields)
   │
   ├─► distinctUntilChanged() checks equality  [LATE GATE]
   │      └─► Compares all 23 fields between old and new ComposerState
   │      └─► text differs → emit new state
   │
   ├─► ChatScreen collects new composerState
   │
   ├─► remember(composerState, ...) in ChatScreen triggers  [CRITICAL]
   │      └─► composerState.copy() called
   │      └─► Creates ANOTHER new ComposerState with 23 fields
   │
   ├─► ChatComposer(state = adjustedComposerState) recomposes
   │
   ├─► TextInputContent(state) recomposes
   │      ├─► leadingContent lambda recreated  [WASTE]
   │      └─► trailingContent lambda recreated  [WASTE]
   │
   ├─► ComposerTextField recomposes
   │      ├─► ComposerActionButtons recomposes  [UNNECESSARY]
   │      └─► ComposerMediaButtons recomposes  [UNNECESSARY]
   │
   └─► Frame renders (user sees 'H' appear)
```

**Total unnecessary work per keystroke:**
- 1 combine block execution
- 2 ComposerState constructions (original + copy)
- 2 ComposerState equality checks
- 2 lambda recreations
- 2 child composable recompositions

---

## Recommended Fix Priority

1. **CRITICAL: Fix `adjustedComposerState` remember block** (ChatScreen.kt:822-850)
   - Impact: Eliminates `.copy()` call and remember recalculation per keystroke
   - Effort: Medium (requires restructuring voice state passing)

2. **HIGH: Add `distinctUntilChanged()` to `_draftText`** (ChatViewModel.kt)
   - Impact: No extra benefit for text, but documents intent
   - Effort: Trivial

3. **HIGH: Split text path from combine block** (ChatViewModel.kt)
   - Impact: Eliminates combine execution on text-only changes
   - Effort: Medium (requires new flow architecture)

4. **MEDIUM: Memoize lambdas in TextInputContent** (ChatComposer.kt)
   - Impact: Prevents button recomposition
   - Effort: Low

5. **LOW: Split ComposerState into sub-states** (ComposerState.kt)
   - Impact: Faster equality checks, smaller copies
   - Effort: High (large refactor)

---

## Testing Methodology

To verify these issues and measure improvement:

### 1. Enable Compose Compiler Reports
```gradle
// build.gradle.kts
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}
```

### 2. Use Layout Inspector
- Android Studio → View → Tool Windows → Layout Inspector
- Enable "Show Recomposition Counts"
- Type in composer and observe:
  - `ComposerActionButtons` recomposition count
  - `ComposerMediaButtons` recomposition count
  - These should stay at 0 during typing (they currently increment)

### 3. Profile with Systrace
```bash
# Record trace while typing
adb shell atrace -t 5 -b 16384 gfx view dalvik -o /data/local/tmp/trace.html

# Pull and analyze
adb pull /data/local/tmp/trace.html
```

Look for:
- Main thread blocking during `Choreographer#doFrame`
- Excessive `View#draw` calls
- GC pressure from object allocations

### 4. Measure Frame Time
```kotlin
// Add to ChatScreen temporarily
val frameTime = remember { mutableStateOf(0L) }
LaunchedEffect(Unit) {
    while (true) {
        val start = System.nanoTime()
        withFrameNanos { }
        frameTime.value = (System.nanoTime() - start) / 1_000_000
    }
}
// Display frameTime - should be <16ms for 60fps
```

---

## Appendix: File Reference

| File | Lines | Role |
|------|-------|------|
| `ChatScreen.kt` | 822-850 | **CRITICAL**: adjustedComposerState remember block |
| `ChatViewModel.kt` | 252-317 | **HIGH**: composerState combine block |
| `ChatViewModel.kt` | 194-195 | _draftText StateFlow declaration |
| `ChatViewModel.kt` | 319-324 | onComposerEvent TextChanged handler |
| `ComposerState.kt` | 14-110 | ComposerState data class (23 fields) |
| `ComposerTextField.kt` | 116-160 | BasicTextField with decoration box |
| `ChatComposer.kt` | 343-364 | TextInputContent with lambda recreation |

---

## Related Documents

- `RESEARCH_TYPING_SLUGGISHNESS.md` - Original research (lambda recreation focus)
- `PERFORMANCE_ANALYSIS.md` - General performance considerations
- `CHAT_COMPOSER_UI.md` - Composer architecture documentation
