# Shared UI Components

## Purpose

Reusable UI components shared across multiple screens. Organized by category.

## Architecture

```
components/
├── attachment/      - Attachment display components
├── common/          - General purpose components
├── conversation/    - Conversation list components
├── dialogs/         - Dialog and bottom sheet components
├── input/           - Input components
└── message/         - Message display components
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `attachment/` | Image, video, audio, file attachment components |
| `common/` | Avatar, link preview, empty states, banners |
| `conversation/` | Conversation tiles, badges, swipe actions |
| `dialogs/` | Dialogs, bottom sheets, popups |
| `input/` | Search bars, text inputs, pickers |
| `message/` | Message bubbles, reactions, delivery indicators |

## Required Patterns

### Component Design

```kotlin
@Composable
fun MyComponent(
    // Required parameters first
    data: DataType,
    onClick: () -> Unit,
    // Optional parameters with defaults
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    // Implementation
}
```

### Preview Functions

```kotlin
@Preview
@Composable
private fun MyComponentPreview() {
    BothBubblesTheme {
        MyComponent(
            data = PreviewData.sampleData,
            onClick = {}
        )
    }
}
```

## Best Practices

1. Keep components stateless where possible
2. Use callbacks for all interactions
3. Support Modifier parameter for flexibility
4. Provide preview functions
5. Follow Material 3 guidelines
6. Use semantic colors from theme
