# Common Components

## Purpose

General-purpose UI components used throughout the app.

## Files

| File | Description |
|------|-------------|
| `AnimationUtils.kt` | Animation helper functions |
| `Avatar.kt` | Contact avatar with initials fallback |
| `AvatarVariants.kt` | Different avatar styles |
| `BorderlessLinkPreview.kt` | Link preview without border |
| `ConnectionStatusBanner.kt` | Server connection status |
| `DeveloperOverlay.kt` | Developer mode info overlay |
| `EmptyStates.kt` | Empty state placeholders |
| `ErrorSnackbar.kt` | Error display snackbar |
| `GroupAvatar.kt` | Multi-person avatar stack |
| `IntentHelpers.kt` | Intent launching utilities |
| `LinkPreview.kt` | URL link preview card |
| `LinkPreviewCard.kt` | Link preview card variant |
| `LinkPreviewShimmer.kt` | Loading shimmer for link preview |
| `LinkPreviewStates.kt` | Link preview state models |
| `LinkPreviewUtils.kt` | Link preview utilities |
| `PullToRefresh.kt` | Pull-to-refresh wrapper |
| `QrCodeScanner.kt` | QR code scanner component |
| `ShimmerPlaceholder.kt` | Loading shimmer effect |
| `SnoozeDuration.kt` | Snooze duration selector |
| `SpamSafetyBanner.kt` | Spam warning banner |
| `TextAnnotations.kt` | Clickable text annotations |

## Required Patterns

### Avatar

```kotlin
@Composable
fun Avatar(
    name: String?,
    photoUri: String?,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    if (photoUri != null) {
        AsyncImage(
            model = photoUri,
            contentDescription = "Contact photo",
            modifier = modifier.size(size).clip(CircleShape)
        )
    } else {
        // Initials fallback
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(generateColorFromName(name)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = getInitials(name),
                color = Color.White
            )
        }
    }
}
```

### Empty State

```kotlin
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, modifier = Modifier.size(64.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, style = MaterialTheme.typography.bodyMedium)
        action?.invoke()
    }
}
```

## Best Practices

1. Use consistent sizing tokens
2. Support dark/light theme
3. Handle null/missing data gracefully
4. Use semantic colors
5. Support accessibility
