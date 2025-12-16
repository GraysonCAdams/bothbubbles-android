# Chat Components

## Purpose

Chat-specific UI components used in the chat screen.

## Files

| File | Description |
|------|-------------|
| `AttachmentComponents.kt` | Attachment preview/display components |
| `ChatAttachmentStrip.kt` | Pending attachments strip with warning banner |
| `ChatBackground.kt` | Custom chat background |
| `ChatBanners.kt` | Info banners (spam warning, etc.) |
| `ChatGestures.kt` | Swipe and gesture handlers |
| `ChatInputArea.kt` | Message input area orchestrator (mode switching) |
| `ChatInputFields.kt` | Text input field row with add/emoji/image buttons |
| `ChatPreviewPanel.kt` | Voice memo preview/playback panel |
| `ChatRecordingPanel.kt` | Voice memo recording panel with waveform |
| `ComposerTextField.kt` | Text input field for composer |
| `EmptyStateMessages.kt` | Empty chat state UI |
| `EtaSharingBanner.kt` | ETA sharing status banner |
| `FlipPlaceholderText.kt` | Animated placeholder text |
| `InlineSearchBar.kt` | In-chat search bar |
| `LoadingMoreIndicator.kt` | Loading indicator for pagination |
| `QualitySelectionSheet.kt` | Attachment quality selector |
| `ReorderableAttachmentStrip.kt` | Drag-to-reorder attachments |
| `ReplyPreview.kt` | Reply-to message preview |
| `SearchResultsSheet.kt` | Search results bottom sheet |
| `SendButton.kt` | Send message button |
| `SendModeToggleButton.kt` | iMessage/SMS toggle |
| `SendModeTutorialOverlay.kt` | Tutorial for send mode |

## Architecture

```
Component Hierarchy:

ChatScreen
├── ChatTopBar
├── MessageList
│   ├── MessageBubble
│   ├── LoadingMoreIndicator
│   └── EmptyStateMessages
├── EtaSharingBanner
├── ReplyPreview
├── ChatInputArea (orchestrator - switches between modes)
│   ├── ChatAttachmentStrip (pending attachments + quality indicator)
│   │   ├── ReorderableAttachmentStrip
│   │   └── AttachmentWarningBanner
│   ├── ChatInputFieldRow (NORMAL mode - text input)
│   │   └── ComposerTextField
│   ├── ChatRecordingPanel (RECORDING mode - voice memo)
│   │   └── RecordingWaveform
│   ├── ChatPreviewPanel (PREVIEW mode - playback)
│   ├── VoiceMemoButton
│   └── SendButton / SendModeToggleButton
└── SearchResultsSheet / QualitySelectionSheet
```

### Input Mode State Machine

```
┌─────────┐  hold voice btn  ┌───────────┐  stop/done  ┌─────────┐
│ NORMAL  │ ───────────────► │ RECORDING │ ──────────► │ PREVIEW │
└─────────┘                  └───────────┘             └─────────┘
     ▲                            │                         │
     │         cancel             │        cancel/send      │
     └────────────────────────────┴─────────────────────────┘
```

## Required Patterns

### Component Design

```kotlin
@Composable
fun SendButton(
    isEnabled: Boolean,
    isSending: Boolean,
    sendMode: SendMode,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Stateless composable with all state passed in
}
```

### Gesture Handling

```kotlin
@Composable
fun ChatGestures(
    onSwipeToReply: (Message) -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures { ... }
        }
    ) {
        content()
    }
}
```

## Best Practices

1. Keep components stateless where possible
2. Use callbacks for all interactions
3. Support modifiers for flexibility
4. Handle touch/gesture accessibility
5. Use proper haptic feedback
