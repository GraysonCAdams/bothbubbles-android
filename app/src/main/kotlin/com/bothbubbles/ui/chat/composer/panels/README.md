# Composer Panels

## Purpose

Expandable panels for media selection, GIF picker, emoji keyboard, and voice memo.

## Files

| File | Description |
|------|-------------|
| `ComposerPanelHost.kt` | Container for animated panel switching |
| `EmojiKeyboardPanel.kt` | Emoji picker panel |
| `GifPickerPanel.kt` | GIF search and selection (Tenor) |
| `MediaPickerPanel.kt` | Photo/video picker panel |
| `VoiceMemoPanel.kt` | Voice recording panel |

## Architecture

```
Panel Host:

ComposerPanelHost
├── AnimatedContent (transitions)
├── MediaPickerPanel
│   └── Photo Picker integration
├── GifPickerPanel
│   ├── Search bar
│   ├── Category chips
│   └── GIF grid (Tenor API)
├── EmojiKeyboardPanel
│   ├── Category tabs
│   ├── Emoji grid
│   └── Recent emojis
└── VoiceMemoPanel
    ├── Record button
    ├── Waveform visualizer
    └── Playback controls
```

## Required Patterns

### Panel Host

```kotlin
@Composable
fun ComposerPanelHost(
    activePanel: ComposerPanel?,
    panelHeight: Dp,
    onMediaSelected: (List<Uri>) -> Unit,
    onGifSelected: (GifResult) -> Unit,
    onEmojiSelected: (String) -> Unit,
    onVoiceMemoRecorded: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = activePanel,
        transitionSpec = {
            slideInVertically { it } togetherWith slideOutVertically { it }
        }
    ) { panel ->
        Box(modifier = Modifier.height(panelHeight)) {
            when (panel) {
                ComposerPanel.MEDIA -> MediaPickerPanel(onMediaSelected)
                ComposerPanel.GIF -> GifPickerPanel(onGifSelected)
                ComposerPanel.EMOJI -> EmojiKeyboardPanel(onEmojiSelected)
                ComposerPanel.VOICE -> VoiceMemoPanel(onVoiceMemoRecorded)
                null -> { /* Empty */ }
            }
        }
    }
}
```

### GIF Picker

```kotlin
@Composable
fun GifPickerPanel(
    onGifSelected: (GifResult) -> Unit,
    viewModel: GifPickerViewModel = hiltViewModel()
) {
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()

    Column {
        SearchBar(onSearch = viewModel::search)
        LazyVerticalGrid(columns = GridCells.Fixed(2)) {
            items(searchResults) { gif ->
                GifThumbnail(gif, onClick = { onGifSelected(gif) })
            }
        }
    }
}
```

## Best Practices

1. Match keyboard height for smooth transitions
2. Remember scroll position per panel
3. Prefetch next page of results
4. Handle permission requests for voice recording
5. Support panel resize via drag
