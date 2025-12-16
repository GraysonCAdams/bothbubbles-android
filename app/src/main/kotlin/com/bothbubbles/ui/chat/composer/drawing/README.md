# Drawing Canvas

## Purpose

Drawing tools for marking up images/attachments before sending.

## Files

| File | Description |
|------|-------------|
| `DrawingCanvas.kt` | Canvas for freehand drawing |
| `DrawingToolbar.kt` | Tool selection (pen, highlighter, eraser) |
| `TextOverlay.kt` | Add text annotations to images |

## Required Patterns

### Drawing Canvas

```kotlin
@Composable
fun DrawingCanvas(
    paths: List<DrawingPath>,
    currentPath: DrawingPath?,
    onPathComplete: (DrawingPath) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset -> /* Start new path */ },
                onDrag = { change, _ -> /* Add to current path */ },
                onDragEnd = { /* Complete path */ }
            )
        }
    ) {
        paths.forEach { path ->
            drawPath(path.toPath(), path.color, style = Stroke(path.width))
        }
    }
}
```

### Drawing Tools

```kotlin
enum class DrawingTool {
    PEN,
    HIGHLIGHTER,
    ERASER,
    TEXT
}

data class DrawingPath(
    val points: List<Offset>,
    val color: Color,
    val width: Float,
    val tool: DrawingTool
)
```

## Best Practices

1. Support undo/redo operations
2. Smooth path rendering with bezier curves
3. Support multi-touch for zoom/pan
4. Save drawing state on rotation
