# Message Effects

## Purpose

iMessage-style message effects including bubble effects (slam, loud, gentle) and screen effects (confetti, balloons, fireworks).

## Files

| File | Description |
|------|-------------|
| `EffectPickerSheet.kt` | Bottom sheet for selecting effects |
| `MessageEffect.kt` | Effect enum and models |

## Architecture

```
Effects Organization:

effects/
├── EffectPickerSheet.kt    - Effect selection UI
├── MessageEffect.kt        - Effect definitions
├── bubble/                 - Bubble-level effects
│   ├── GentleEffect.kt
│   ├── LoudEffect.kt
│   ├── SlamEffect.kt
│   └── InvisibleInkEffect.kt
├── particles/              - Particle system
│   ├── Particle.kt
│   └── ParticlePool.kt
└── screen/                 - Full-screen effects
    ├── BalloonsEffect.kt
    ├── CelebrationEffect.kt
    ├── ConfettiEffect.kt
    └── FireworksEffect.kt
```

## Required Patterns

### Effect Enum

```kotlin
enum class MessageEffect(val displayName: String) {
    // Bubble effects
    SLAM("Slam"),
    LOUD("Loud"),
    GENTLE("Gentle"),
    INVISIBLE_INK("Invisible Ink"),

    // Screen effects
    CONFETTI("Confetti"),
    BALLOONS("Balloons"),
    FIREWORKS("Fireworks"),
    CELEBRATION("Celebration"),
    LASERS("Lasers"),
    HEARTS("Hearts"),
    SPOTLIGHT("Spotlight"),
    ECHO("Echo")
}
```

### Effect Picker

```kotlin
@Composable
fun EffectPickerSheet(
    onSelect: (MessageEffect) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column {
            Text("Send with effect", style = MaterialTheme.typography.titleMedium)

            Text("Bubble", style = MaterialTheme.typography.labelMedium)
            FlowRow {
                MessageEffect.bubbleEffects.forEach { effect ->
                    EffectChip(effect, onClick = { onSelect(effect) })
                }
            }

            Text("Screen", style = MaterialTheme.typography.labelMedium)
            FlowRow {
                MessageEffect.screenEffects.forEach { effect ->
                    EffectChip(effect, onClick = { onSelect(effect) })
                }
            }
        }
    }
}
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `bubble/` | Bubble-level effects (apply to single message) |
| `particles/` | Particle system for animations |
| `screen/` | Full-screen overlay effects |

## Best Practices

1. Use Canvas for custom animations
2. Pool particles for performance
3. Respect reduced motion accessibility setting
4. Auto-dismiss effects after duration
5. Support effect preview in picker
