# Screen Effects

## Purpose

Full-screen effects that overlay the entire screen (confetti, balloons, fireworks, etc.).

## Files

| File | Description |
|------|-------------|
| `BalloonsEffect.kt` | Floating balloons rising up |
| `CelebrationEffect.kt` | Combined celebration effect |
| `ConfettiEffect.kt` | Falling confetti |
| `EchoEffect.kt` | Message echoes across screen |
| `FireworksEffect.kt` | Exploding fireworks |
| `HeartsEffect.kt` | Floating hearts |
| `LasersEffect.kt` | Laser beams effect |
| `ScreenEffectOverlay.kt` | Container for screen effects |
| `SpotlightEffect.kt` | Spotlight on message |

## Required Patterns

### Screen Effect Overlay

```kotlin
@Composable
fun ScreenEffectOverlay(
    effect: MessageEffect?,
    isPlaying: Boolean,
    onComplete: () -> Unit
) {
    if (effect == null || !isPlaying) return

    Box(modifier = Modifier.fillMaxSize()) {
        when (effect) {
            MessageEffect.CONFETTI -> ConfettiEffect(onComplete)
            MessageEffect.BALLOONS -> BalloonsEffect(onComplete)
            MessageEffect.FIREWORKS -> FireworksEffect(onComplete)
            MessageEffect.CELEBRATION -> CelebrationEffect(onComplete)
            MessageEffect.HEARTS -> HeartsEffect(onComplete)
            MessageEffect.LASERS -> LasersEffect(onComplete)
            else -> { onComplete() }
        }
    }
}
```

### Confetti Effect

```kotlin
@Composable
fun ConfettiEffect(onComplete: () -> Unit) {
    val particlePool = remember { ParticlePool() }
    var elapsedTime by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        // Spawn confetti
        particlePool.spawn(200) { particle ->
            particle.x = Random.nextFloat() * screenWidth
            particle.y = -50f
            particle.velocityX = Random.nextFloat() * 200 - 100
            particle.velocityY = Random.nextFloat() * 200 + 100
            particle.color = confettiColors.random()
            particle.lifetime = 3f
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        particlePool.forEachActive { particle ->
            drawRect(
                color = particle.color,
                topLeft = Offset(particle.x, particle.y),
                size = Size(10f, 20f),
                alpha = particle.alpha
            )
        }
    }

    LaunchedEffect(Unit) {
        while (elapsedTime < 3f) {
            delay(16) // ~60fps
            elapsedTime += 0.016f
            particlePool.update(0.016f)
        }
        onComplete()
    }
}
```

## Best Practices

1. Use Canvas for rendering particles
2. Set duration limit (auto-dismiss)
3. Respect reduced motion setting
4. Don't block touch events
5. Clean up resources after completion
