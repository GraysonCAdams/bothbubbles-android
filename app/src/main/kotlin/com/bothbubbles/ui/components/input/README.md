# Input Components

## Purpose

Input components for user data entry including search, text fields, and pickers.

## Files

| File | Description |
|------|-------------|
| `AttachmentPickerPanel.kt` | Photo/video picker panel |
| `EmojiPickerPanel.kt` | Emoji selection panel |
| `SmartReplyChips.kt` | Smart reply suggestion chips |

## Required Patterns

### Search Bar

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search",
    onSearch: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, "Search") },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, "Clear")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() })
    )
}
```

### Smart Reply Chips

```kotlin
@Composable
fun SmartReplyChips(
    suggestions: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(suggestions) { suggestion ->
            SuggestionChip(
                onClick = { onSelect(suggestion) },
                label = { Text(suggestion) }
            )
        }
    }
}
```

### Picker Panel

```kotlin
@Composable
fun AttachmentPickerPanel(
    onMediaSelected: (List<Uri>) -> Unit,
    onCameraClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        onMediaSelected(uris)
    }

    Row(modifier = modifier.padding(16.dp)) {
        IconButton(onClick = onCameraClick) {
            Icon(Icons.Default.CameraAlt, "Camera")
        }
        IconButton(onClick = {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }) {
            Icon(Icons.Default.PhotoLibrary, "Gallery")
        }
    }
}
```

## Best Practices

1. Use Material 3 input components
2. Handle keyboard actions properly
3. Support clear/reset functionality
4. Debounce search input
5. Use Photo Picker API (Android 13+)
