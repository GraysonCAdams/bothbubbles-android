# Refactor Plan: ChatInputArea Components

**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/components/ChatInputArea.kt`
**Goal:** Decompose the monolithic input area into smaller, reusable UI components.

## Design Philosophy: State Hoisting

- **Hoisting:** Components like `ComposerTextField` should not own the "Draft" state. They should receive `value: String` and emit `onValueChange: (String) -> Unit`.
- **Events:** Use a sealed interface for complex component events if necessary, or simple lambdas for single actions.
- **Composition:** `ChatInputArea` becomes a layout container that arranges these smaller components.

## Instructions

### 1. Extract ComposerTextField

Create: `app/src/main/kotlin/com/bothbubbles/ui/chat/components/ComposerTextField.kt`

**Signature:**

```kotlin
@Composable
fun ComposerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
    placeholder: String = "BlueBubbles",
    // ... other visual config
)
```

_Move the `TextField` implementation here. Ensure it is purely controlled (no internal `remember { mutableStateOf(...) }` for the text)._

### 2. Extract SendButton

Create: `app/src/main/kotlin/com/bothbubbles/ui/chat/components/SendButton.kt`

**Signature:**

```kotlin
@Composable
fun SendButton(
    isSending: Boolean,
    sendMode: ChatSendMode,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
)
```

_Move the morphing animation logic (Mic icon <-> Arrow icon) here._

### 3. Update ChatInputArea

1.  In `ChatInputArea.kt`, remove the inline `TextField` and `IconButton` logic.
2.  Call `ComposerTextField` and `SendButton`.
3.  Pass the state from `ChatComposerDelegate` (via `ChatViewModel` -> `ChatScreen`) down to these components.
    - The `ChatViewModel` should now be exposing `composerState` from the delegate (completed in Stage 1).

## Verification

- **State Sync:** Typing in `ComposerTextField` updates the ViewModel state.
- **Animations:** The `SendButton` animates correctly when text is entered (Mic -> Arrow).
- **Focus:** Keyboard handling works as expected.
