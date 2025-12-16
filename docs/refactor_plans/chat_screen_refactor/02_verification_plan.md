# Verification Plan: ChatScreen Performance

## Objective
Verify that the performance optimizations have successfully eliminated the 7 identified regressions.

## Tools
*   **Android Studio Layout Inspector**: Use to visualize recompositions.
*   **Logcat**: Filter for `PerfTrace` tag (if enabled in code).
*   **Manual Testing**: Observe UI smoothness.

## Test Cases

### 1. Typing Performance
*   **Action**: Open a chat and type rapidly in the text field.
*   **Expected Result**:
    *   `ChatInputUI` recomposes on every character (to update text/suggestions).
    *   `ChatScreen` **DOES NOT** recompose.
    *   `ChatMessageList` **DOES NOT** recompose.
*   **Failure Criteria**: If `ChatMessageList` flashes/increments count while typing.

### 2. Emoji/Media Picker
*   **Action**: Tap the Emoji or App Store icon to open the picker.
*   **Expected Result**:
    *   The picker panel opens smoothly.
    *   `ChatMessageList` **DOES NOT** recompose (count stays same).
*   **Failure Criteria**: If opening the picker causes a full list re-render.

### 3. Sending Message
*   **Action**: Send a text message.
*   **Expected Result**:
    *   `ChatInputUI` updates (clears text).
    *   `ChatMessageList` updates (adds new message bubble).
    *   `ChatScreen` **DOES NOT** recompose (sending state is handled internally).

### 4. Swipe to Reply
*   **Action**: Swipe a message bubble to reply.
*   **Expected Result**:
    *   The swipe animation is smooth (60fps).
    *   `ChatScreen` **DOES NOT** recompose (state is local to `ChatMessageList` via `ChatScreenState`).

### 5. Tapback Overlay
*   **Action**: Long-press a message to open Tapback menu.
*   **Expected Result**:
    *   Overlay appears.
    *   `ChatScreen` **DOES NOT** recompose.
    *   Only the specific message item or an overlay layer updates.

### 6. Video Call Dialog
*   **Action**: Tap the Video Call icon.
*   **Expected Result**:
    *   Dialog appears.
    *   `ChatScreen` **DOES NOT** recompose.
    *   Only `ChatDialogsHost` recomposes.

### 7. File Download (Critical)
*   **Action**: Receive or tap to download a large attachment (video/image).
*   **Expected Result**:
    *   Download progress indicator updates smoothly on the specific bubble.
    *   `ChatScreen` **DOES NOT** recompose during the download (0 recompositions).
    *   `ChatMessageList` **DOES NOT** recompose (0 recompositions).
*   **Failure Criteria**: If `ChatScreen` recomposes on every percentage update (1% -> 2%).

## Debugging Tips
*   If `ChatScreen` is still recomposing, check `ChatScreenRecompositionDebug` output in Logcat.
*   Ensure `MessageListCallbacks` are stable (wrapped in `remember`).
*   Ensure no `MutableState` is being read in the `ChatScreen` body that changes frequently.
