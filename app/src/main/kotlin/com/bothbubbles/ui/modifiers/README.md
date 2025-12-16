# Custom Modifiers

## Purpose

Custom Compose modifiers for specialized UI effects.

## Files

| File | Description |
|------|-------------|
| `MaterialAttentionHighlight.kt` | Material-style attention highlight effect |

## Required Patterns

### Modifier Extension

```kotlin
fun Modifier.attentionHighlight(
    isHighlighted: Boolean,
    color: Color = MaterialTheme.colorScheme.primary
): Modifier = composed {
    val alpha by animateFloatAsState(
        targetValue = if (isHighlighted) 0.3f else 0f,
        animationSpec = tween(durationMillis = 300)
    )

    this.drawBehind {
        drawRect(color.copy(alpha = alpha))
    }
}
```

### Usage

```kotlin
@Composable
fun HighlightedMessage(
    message: MessageUi,
    isHighlighted: Boolean
) {
    MessageBubble(
        message = message,
        modifier = Modifier.attentionHighlight(isHighlighted)
    )
}
```

## Best Practices

1. Use `composed` for modifiers with state
2. Provide sensible defaults
3. Support animation customization
4. Document modifier behavior
5. Keep modifiers focused on single effect
