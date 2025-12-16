# Media Viewer

## Purpose

Full-screen media viewer for images and videos. Supports swipe navigation, zoom, and sharing.

## Files

| File | Description |
|------|-------------|
| `MediaPage.kt` | Single media item page |
| `MediaPlayers.kt` | Video player components |
| `MediaViewerScreen.kt` | Full-screen viewer screen |
| `MediaViewerViewModel.kt` | ViewModel for media viewer |

## Architecture

```
MediaViewerScreen
├── HorizontalPager (swipe between items)
│   └── MediaPage
│       ├── ImageViewer (zoomable)
│       └── VideoPlayer (ExoPlayer)
├── TopBar (back, share actions)
└── BottomBar (thumbnail strip)
```

## Required Patterns

### Media Viewer

```kotlin
@Composable
fun MediaViewerScreen(
    attachments: List<Attachment>,
    initialIndex: Int,
    viewModel: MediaViewerViewModel = hiltViewModel(),
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) {
        attachments.size
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState) { page ->
            MediaPage(attachments[page])
        }

        // Top bar with share/download
        MediaViewerTopBar(
            onDismiss = onDismiss,
            onShare = { viewModel.share(attachments[pagerState.currentPage]) },
            onDownload = { viewModel.download(attachments[pagerState.currentPage]) }
        )
    }
}
```

### Zoomable Image

```kotlin
@Composable
fun ZoomableImage(
    imageUrl: String,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    AsyncImage(
        model = imageUrl,
        contentDescription = "Media",
        modifier = modifier
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset += pan
                }
            }
    )
}
```

## Best Practices

1. Use HorizontalPager for swipe navigation
2. Support pinch-to-zoom for images
3. Use ExoPlayer for videos
4. Preload adjacent pages
5. Handle back gesture to dismiss
