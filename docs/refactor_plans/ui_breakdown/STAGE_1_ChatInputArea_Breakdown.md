# Stage 1: ChatInputArea Refactoring Plan

**Goal:** Decompose `ChatInputArea.kt` (1097 lines) into distinct panels for different input modes (Text, Recording, Preview).

**Source File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/components/ChatInputArea.kt`

## 1. Extract `ChatRecordingPanel.kt`
**Responsibility:** UI for voice memo recording.
**Input:** Recording duration, amplitude history, callbacks.
**Action:**
- Move `ExpandedRecordingPanel` (if it exists inline or as a private composable) here.
- Move the waveform visualization logic here.

## 2. Extract `ChatPreviewPanel.kt`
**Responsibility:** UI for reviewing a recorded voice memo.
**Input:** Playback progress, duration, callbacks.
**Action:**
- Move `PreviewContent` (or equivalent UI block) here.
- Include play/pause and seek bar logic.

## 3. Extract `ChatAttachmentStrip.kt`
**Responsibility:** Show pending attachments and warnings.
**Input:** List of attachments, warning state.
**Action:**
- Move `ReorderableAttachmentStrip` usage and the `AnimatedVisibility` block for attachments here.
- Move the attachment warning banner logic here.

## 4. Extract `ChatInputFields.kt`
**Responsibility:** The main text input field and surrounding buttons.
**Input:** Text state, focus state, callbacks.
**Action:**
- Move the `ComposerTextField` wrapper here.
- Move the "Add" button (and its rotation logic) here.

## Execution Order
1.  Create `ChatAttachmentStrip.kt` and move attachment logic.
2.  Create `ChatRecordingPanel.kt` and move recording UI.
3.  Create `ChatPreviewPanel.kt` and move preview UI.
4.  Create `ChatInputFields.kt` and move text input UI.
5.  Update `ChatInputArea.kt` to switch between these components based on `InputMode`.
