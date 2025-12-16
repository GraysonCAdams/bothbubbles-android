# Attachment Components

## Purpose

Components for displaying message attachments (images, videos, audio, files, contacts).

## Files

| File | Description |
|------|-------------|
| `AttachmentCommon.kt` | Shared attachment utilities |
| `AttachmentContent.kt` | Main attachment content wrapper |
| `AttachmentErrorOverlay.kt` | Error state overlay |
| `AttachmentPlaceholder.kt` | Loading placeholder |
| `AudioAttachment.kt` | Audio player component |
| `BorderlessVideoAttachment.kt` | Video without borders (stickers) |
| `ContactAttachment.kt` | vCard contact display |
| `FileAttachment.kt` | Generic file attachment |
| `ImageAttachment.kt` | Image display with gestures |
| `VideoAttachment.kt` | Video player component |

## Architecture

```
AttachmentContent (selector)
├── ImageAttachment
│   ├── Coil AsyncImage
│   └── Tap to fullscreen
├── VideoAttachment
│   ├── ExoPlayer
│   └── Play/pause controls
├── AudioAttachment
│   ├── Waveform visualizer
│   └── Playback controls
├── FileAttachment
│   ├── Icon based on type
│   └── Tap to open
└── ContactAttachment
    ├── Contact info preview
    └── Tap to view/add
```

## Required Patterns

### Attachment Content Selector

```kotlin
@Composable
fun AttachmentContent(
    attachment: Attachment,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        attachment.isImage -> ImageAttachment(attachment, onTap, modifier)
        attachment.isVideo -> VideoAttachment(attachment, onTap, modifier)
        attachment.isAudio -> AudioAttachment(attachment, modifier)
        attachment.isContact -> ContactAttachment(attachment, modifier)
        else -> FileAttachment(attachment, onTap, modifier)
    }
}
```

### Image Attachment

```kotlin
@Composable
fun ImageAttachment(
    attachment: Attachment,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(attachment.localPath ?: attachment.downloadUrl)
            .crossfade(true)
            .build(),
        contentDescription = "Image attachment",
        modifier = modifier.clickable(onClick = onTap),
        placeholder = painterResource(R.drawable.image_placeholder)
    )
}
```

## Best Practices

1. Use Coil for image loading
2. Use ExoPlayer for video/audio
3. Show loading placeholders
4. Handle download errors gracefully
5. Support accessibility descriptions
