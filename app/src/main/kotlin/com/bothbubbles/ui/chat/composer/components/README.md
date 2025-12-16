# Composer Components

## Purpose

Sub-components for the chat composer UI.

## Files

| File | Description |
|------|-------------|
| `AttachmentThumbnailRow.kt` | Row of attachment previews |
| `ComposerActionButtons.kt` | Camera, gallery, GIF buttons |
| `ComposerMediaButtons.kt` | Media selection buttons |
| `ComposerSendButton.kt` | Send button with effect picker |
| `ComposerTextField.kt` | Styled text input field |
| `PanelDragHandle.kt` | Drag handle for panel resize |
| `ReplyPreviewBar.kt` | Reply-to message preview bar |
| `SmartReplyRow.kt` | ML smart reply suggestions |

## Required Patterns

### Stateless Components

```kotlin
@Composable
fun ComposerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                innerTextField()
            }
        }
    )
}
```

## Best Practices

1. Keep components stateless
2. Use callbacks for all interactions
3. Support custom modifiers
4. Handle focus properly
