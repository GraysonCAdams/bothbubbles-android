# Theme

## Purpose

Material Design 3 theming for the app including colors, typography, shapes, and motion.

## Files

| File | Description |
|------|-------------|
| `Motion.kt` | Animation tokens and specifications |
| `Shape.kt` | Shape tokens (corner radii) |
| `Theme.kt` | Main theme composable |
| `Type.kt` | Typography definitions |

## Architecture

```
Theme Structure:

BothBubblesTheme
├── ColorScheme (dynamic or static)
├── Typography (Material 3 type scale)
├── Shapes (corner radii)
└── Motion (animation specs)
```

## Required Patterns

### Theme Composable

```kotlin
@Composable
fun BothBubblesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BothBubblesTypography,
        shapes = BothBubblesShapes,
        content = content
    )
}
```

### Using Theme Values

```kotlin
@Composable
fun MyComponent() {
    // Colors
    val backgroundColor = MaterialTheme.colorScheme.surface
    val primaryColor = MaterialTheme.colorScheme.primary

    // Typography
    val titleStyle = MaterialTheme.typography.titleLarge
    val bodyStyle = MaterialTheme.typography.bodyMedium

    // Shapes
    val cardShape = MaterialTheme.shapes.medium
}
```

### Motion Tokens

```kotlin
object BothBubblesMotion {
    val FastDuration = 150
    val MediumDuration = 300
    val SlowDuration = 500

    val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    val Spring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )
}
```

## Best Practices

1. Use Material 3 tokens consistently
2. Support dynamic color (Android 12+)
3. Support dark theme
4. Use semantic colors (primary, surface, error)
5. Define motion tokens centrally
