# Plan: Fix Notification Access Detection Logic

## Problem

The current implementation of `checkNotificationAccess()` in the ViewModel relies on manual string parsing of `Settings.Secure.getString(..., "enabled_notification_listeners")`.

- It uses `contains()` which can lead to false positives.
- It hardcodes the component string, which is brittle to refactoring or obfuscation.

## Proposed Solution

Use the standard Android API `NotificationManagerCompat` which handles the parsing and OS-level differences.

### Recommended Approach

Use `NotificationManagerCompat.getEnabledListenerPackages(context)`.

```kotlin
import androidx.core.app.NotificationManagerCompat

private fun checkNotificationAccess(): Boolean {
    return NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)
}
```

### Alternative (Specific Service Check)

If we strictly need to check for `NavigationListenerService` specifically (rarely needed as permission is granted at app level):

```kotlin
private fun checkNotificationAccess(): Boolean {
    val componentName = ComponentName(context, com.bothbubbles.services.eta.NavigationListenerService::class.java)
    val flatName = componentName.flattenToString()

    val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")

    return enabledListeners?.split(":")?.contains(flatName) == true
}
```

## Action Items

1. Locate the ViewModel containing `checkNotificationAccess()`.
2. Replace the manual string parsing with `NotificationManagerCompat.getEnabledListenerPackages(context)`.
3. Verify imports (`androidx.core.app.NotificationManagerCompat`).
4. Test the change.
