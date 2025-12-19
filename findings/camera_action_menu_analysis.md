# Analysis of Camera Action Menu (CaptureTypeSheet)

## Compliance Status
**Status:** Partially Compliant / Breaking Patterns

The quick action menu for the camera action (`CaptureTypeSheet.kt`) is partially compliant with Material Design 3 (MD3) but breaks several key patterns, particularly regarding color usage and component selection.

## Issues Identified

### 1. Hardcoded Colors (Breaking Pattern)
The menu uses hardcoded hex values for the icon backgrounds instead of referencing the dynamic theme colors.
- **Current:** `Color(0xFF2196F3)` (Blue) and `Color(0xFFE91E63)` (Pink).
- **Issue:** This ignores the user's dynamic color scheme (Material You) and does not adapt to light/dark themes properly. It creates a visual inconsistency with the rest of the app which uses `MaterialTheme.colorScheme`.

### 2. Custom List Implementation (Breaking Pattern)
The menu items are implemented using a custom `Row` layout inside a `Surface`.
- **Current:** `Surface` -> `Row` -> `Icon` + `Text`.
- **Issue:** MD3 provides the `ListItem` component which handles:
    - Correct padding and spacing (leading/trailing/headline).
    - Proper text styling for headlines and supporting text.
    - Built-in accessibility support.
    - Correct state layers (ripple, hover, focus).
    - Using `Surface` with `MaterialTheme.colorScheme.surface` inside a `ModalBottomSheet` (which defaults to `SurfaceContainerLow`) creates a subtle background mismatch.

### 3. Icon Container Styling
The icons use a custom `Surface` with a hardcoded alpha (`0.15f`) for the background opacity.
- **Current:** `color = iconColor.copy(alpha = 0.15f)`
- **Issue:** While visually acceptable, the "Material way" is to use tonal color pairs, such as `PrimaryContainer` with `OnPrimaryContainer`. This ensures accessible contrast ratios and consistent visual weight across the app.

## Recommendations

Refactor the `CaptureOption` composable to use the standard `ListItem` component and semantic color roles.

### Proposed Implementation

```kotlin
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults

@Composable
private fun CaptureOption(
    icon: ImageVector,
    containerColor: Color, // e.g. MaterialTheme.colorScheme.primaryContainer
    contentColor: Color,   // e.g. MaterialTheme.colorScheme.onPrimaryContainer
    title: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = containerColor,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent // Let sheet background show through
        ),
        modifier = Modifier.clickable(onClick = onClick)
    )
}
```

### Usage Example

```kotlin
// Photo Option
CaptureOption(
    icon = Icons.Outlined.PhotoCamera,
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    title = "Take photo",
    onClick = onTakePhoto
)

// Video Option (Use Tertiary or Secondary for distinction)
CaptureOption(
    icon = Icons.Outlined.Videocam,
    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    title = "Record video",
    onClick = onRecordVideo
)
```
