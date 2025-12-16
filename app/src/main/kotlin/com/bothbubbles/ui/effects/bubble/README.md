# Bubble Effects

## Purpose

Effects that apply to individual message bubbles (slam, loud, gentle, invisible ink).

## Files

| File | Description |
|------|-------------|
| `BubbleEffectWrapper.kt` | Wrapper that applies effects to bubbles |
| `GentleEffect.kt` | Gentle/soft appearance effect |
| `InvisibleInkEffect.kt` | Hidden message that reveals on tap |
| `LoudEffect.kt` | Large, shaking appearance |
| `SlamEffect.kt` | Message slams down from above |

## Required Patterns

### Effect Wrapper

```kotlin
@Composable
fun BubbleEffectWrapper(
    effect: MessageEffect?,
    isPlaying: Boolean,
    onEffectComplete: () -> Unit,
    content: @Composable () -> Unit
) {
    when (effect) {
        MessageEffect.SLAM -> SlamEffect(isPlaying, onEffectComplete, content)
        MessageEffect.LOUD -> LoudEffect(isPlaying, onEffectComplete, content)
        MessageEffect.GENTLE -> GentleEffect(isPlaying, onEffectComplete, content)
        MessageEffect.INVISIBLE_INK -> InvisibleInkEffect(content)
        else -> content()
    }
}
```

### Slam Effect

```kotlin
@Composable
fun SlamEffect(
    isPlaying: Boolean,
    onComplete: () -> Unit,
    content: @Composable () -> Unit
) {
    var offset by remember { mutableStateOf(if (isPlaying) -200f else 0f) }
    var scale by remember { mutableStateOf(if (isPlaying) 1.5f else 1f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            // Animate slam down
            animate(
                initialValue = -200f,
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.4f)
            ) { value, _ -> offset = value }

            // Scale down impact
            animate(
                initialValue = 1.5f,
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.3f)
            ) { value, _ -> scale = value }

            onComplete()
        }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(0, offset.roundToInt()) }
            .scale(scale)
    ) {
        content()
    }
}
```

## Best Practices

1. Use spring animations for natural feel
2. Reset state after effect completes
3. Support replay
4. Handle effect during scroll
5. Provide haptic feedback
