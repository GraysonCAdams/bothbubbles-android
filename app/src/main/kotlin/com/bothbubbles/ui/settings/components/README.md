# Settings Components

## Purpose

Shared UI components for settings screens.

## Files

| File | Description |
|------|-------------|
| `SettingsComponents.kt` | Reusable settings UI components |

## Key Components

```kotlin
@Composable
fun SettingsCategory(
    title: String,
    content: @Composable ColumnScope.() -> Unit
)

@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
)

@Composable
fun SettingsSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
)

@Composable
fun SettingsSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>
)
```
