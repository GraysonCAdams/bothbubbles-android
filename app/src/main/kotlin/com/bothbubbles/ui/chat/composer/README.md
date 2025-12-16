# Chat Composer

## Purpose

Message composition UI including text input, attachments, media panels, and drawing tools.

## Files

| File | Description |
|------|-------------|
| `AttachmentEditScreen.kt` | Edit attachment before sending |
| `AttachmentEditor.kt` | Attachment editing tools |
| `ChatComposer.kt` | Main composer composable |
| `ComposerEvent.kt` | Composer event definitions |
| `ComposerState.kt` | Composer state models |
| `ComposerViewModel.kt` | ViewModel for composer (if separate) |

## Architecture

```
Composer Architecture:

ChatComposer
├── ComposerTextField
│   └── BasicTextField with decorations
├── AttachmentThumbnailRow
│   └── Draggable attachment previews
├── SmartReplyRow
│   └── ML-suggested replies
├── ComposerActionButtons
│   └── Camera, Gallery, GIF, Voice
├── ComposerSendButton
│   └── Send with effect selection
└── ComposerPanelHost
    ├── MediaPickerPanel
    ├── GifPickerPanel
    ├── EmojiKeyboardPanel
    └── VoiceMemoPanel
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `animations/` | Composer animation tokens |
| `components/` | Composer sub-components |
| `drawing/` | Drawing canvas for markup |
| `gestures/` | Composer gesture handlers |
| `panels/` | Expandable panels (media, GIF, emoji) |
| `tutorial/` | Onboarding tutorial overlays |

## Required Patterns

### Composer State

```kotlin
data class ComposerState(
    val text: String = "",
    val attachments: List<PendingAttachmentInput> = emptyList(),
    val replyTo: Message? = null,
    val activePanel: ComposerPanel? = null,
    val isSending: Boolean = false
)

enum class ComposerPanel {
    MEDIA, GIF, EMOJI, VOICE
}
```

### Panel Switching

```kotlin
@Composable
fun ComposerPanelHost(
    activePanel: ComposerPanel?,
    onMediaSelected: (List<Uri>) -> Unit,
    onGifSelected: (GifResult) -> Unit,
    onEmojiSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedContent(targetState = activePanel) { panel ->
        when (panel) {
            ComposerPanel.MEDIA -> MediaPickerPanel(onMediaSelected)
            ComposerPanel.GIF -> GifPickerPanel(onGifSelected)
            ComposerPanel.EMOJI -> EmojiKeyboardPanel(onEmojiSelected)
            ComposerPanel.VOICE -> VoiceMemoPanel()
            null -> Spacer(modifier = Modifier.height(0.dp))
        }
    }
}
```

## Best Practices

1. Handle keyboard/IME transitions smoothly
2. Support drag-to-reorder attachments
3. Animate panel transitions
4. Remember panel state across recompositions
5. Support accessibility for all inputs
