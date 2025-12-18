# Fix Plan: NavigationListenerService Crash Loop

**Error:** Service crashes and repeatedly restarts
**Severity:** High
**Log:** `Scheduling restart of crashed service .../NavigationListenerService`

---

## Problem

`NavigationListenerService` crashes and Android keeps trying to restart it, causing a crash loop that wastes battery and system resources.

## Root Cause

The service:
1. Extends `NotificationListenerService`
2. Crashes during initialization (likely permission-related)
3. Uses default restart policy causing repeated restarts
4. Doesn't check permissions before accessing protected resources

## Implementation Plan

### Step 1: Audit NavigationListenerService

Location: `app/src/main/kotlin/com/bothbubbles/services/eta/NavigationListenerService.kt`

Review:
- What permissions does it need?
- What does `onCreate()` do?
- What does `onListenerConnected()` do?
- What resources does it access?

### Step 2: Add Safe Initialization

```kotlin
class NavigationListenerService : NotificationListenerService() {

    private var isInitialized = false

    override fun onCreate() {
        super.onCreate()
        try {
            // Check permissions first
            if (!hasRequiredPermissions()) {
                Log.w(TAG, "Missing required permissions, stopping service")
                stopSelf()
                return
            }

            initializeService()
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NavigationListenerService", e)
            // Don't crash - stop gracefully
            stopSelf()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        // Check READ_CONTACTS if needed
        val hasContacts = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        // Check other permissions as needed
        return hasContacts // && other permissions
    }

    private fun initializeService() {
        // Move existing onCreate logic here
    }
}
```

### Step 3: Protect onListenerConnected

```kotlin
override fun onListenerConnected() {
    if (!isInitialized) {
        Log.w(TAG, "Listener connected but service not initialized")
        return
    }

    try {
        super.onListenerConnected()
        // ... existing logic
    } catch (e: SecurityException) {
        Log.e(TAG, "Permission denied in onListenerConnected", e)
        // Handle gracefully
    } catch (e: Exception) {
        Log.e(TAG, "Error in onListenerConnected", e)
    }
}
```

### Step 4: Protect onNotificationPosted

```kotlin
override fun onNotificationPosted(sbn: StatusBarNotification?) {
    if (!isInitialized || sbn == null) return

    try {
        // ... existing logic with permission checks
    } catch (e: SecurityException) {
        Log.w(TAG, "Permission lost while processing notification")
        // Don't crash
    } catch (e: Exception) {
        Log.e(TAG, "Error processing notification", e)
    }
}
```

### Step 5: Handle Service Restart Policy

Consider whether service should restart on failure:

```kotlin
// If service shouldn't restart without user action:
// The NotificationListenerService is managed by Android,
// but we can track failure state

companion object {
    private var consecutiveFailures = 0
    private const val MAX_FAILURES = 3
}

override fun onCreate() {
    super.onCreate()

    if (consecutiveFailures >= MAX_FAILURES) {
        Log.e(TAG, "Too many consecutive failures, giving up")
        stopSelf()
        return
    }

    try {
        initializeService()
        consecutiveFailures = 0  // Reset on success
    } catch (e: Exception) {
        consecutiveFailures++
        stopSelf()
    }
}
```

### Step 6: Add Manifest Fallback

Ensure service has correct manifest declaration:

```xml
<service
    android:name=".services.eta.NavigationListenerService"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    android:exported="false">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

## Testing

1. Revoke notification access permission
2. Verify service stops without crash loop
3. Re-grant permission
4. Verify service starts correctly
5. Revoke contacts permission while service running
6. Verify no crash loop

## Success Criteria

- [ ] Service doesn't enter crash loop
- [ ] Service stops gracefully when permissions missing
- [ ] Service recovers when permissions restored
- [ ] No repeated "restart of crashed service" logs
- [ ] Battery usage normalized
