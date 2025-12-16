# Composer Animations

## Purpose

Animation tokens and specifications for composer transitions and effects.

## Files

| File | Description |
|------|-------------|
| `ComposerMotionTokens.kt` | Animation duration, easing, and spring specs |

## Required Patterns

### Motion Tokens

```kotlin
object ComposerMotionTokens {
    // Durations
    const val PANEL_EXPAND_DURATION = 300
    const val SEND_BUTTON_ANIMATION = 200

    // Easing
    val PanelEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    // Spring specs
    val AttachmentSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )
}
```

## Best Practices

1. Centralize animation constants
2. Use consistent motion language
3. Follow Material motion guidelines
4. Keep animations snappy (under 300ms for micro-interactions)
