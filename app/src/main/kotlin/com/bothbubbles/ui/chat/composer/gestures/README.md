# Composer Gestures

## Purpose

Gesture handlers for composer interactions like send mode switching.

## Files

| File | Description |
|------|-------------|
| `SendModeGestureHandler.kt` | Handle swipe gestures for iMessage/SMS toggle |

## Required Patterns

### Send Mode Gesture

```kotlin
@Composable
fun SendModeGestureHandler(
    onSwipeToSms: () -> Unit,
    onSwipeToIMessage: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures { change, dragAmount ->
                if (dragAmount > SWIPE_THRESHOLD) {
                    onSwipeToSms()
                } else if (dragAmount < -SWIPE_THRESHOLD) {
                    onSwipeToIMessage()
                }
            }
        }
    ) {
        content()
    }
}
```

## Best Practices

1. Provide visual feedback during gestures
2. Use appropriate thresholds for activation
3. Support accessibility alternatives
4. Add haptic feedback
