# Fix Plan: Permission Revocation Crash

**Error:** Process killed when one-time permission revoked
**Severity:** Critical
**Log:** `Killing 5242:com.bothbubbles.messaging/u0a547 (adj 250): one-time permission revoked`

---

## Problem

When a user grants a one-time permission (location, contacts, etc.) and it expires or is revoked, Android kills the entire app process. This creates a poor user experience.

## Root Cause

The app likely:
1. Requests one-time permissions
2. Doesn't handle the permission being revoked mid-session
3. Has services/components that crash when permission disappears

## Implementation Plan

### Step 1: Identify Permission-Dependent Code

Search for permission-dependent operations:

```kotlin
// Look for these patterns:
- checkSelfPermission
- requestPermissions
- ContactsContract
- LocationManager
- ContentResolver queries on protected providers
```

Files to audit:
- `services/eta/NavigationListenerService.kt`
- `services/contacts/` directory
- Any location-related services

### Step 2: Add Permission State Monitoring

Create a permission monitor that tracks permission state changes:

```kotlin
// In util/ or services/
class PermissionStateMonitor @Inject constructor(
    private val context: Context
) {
    fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}
```

### Step 3: Wrap Permission-Dependent Operations

Before any permission-dependent operation:

```kotlin
// Instead of:
contentResolver.query(ContactsContract.Contacts.CONTENT_URI, ...)

// Do:
if (permissionMonitor.hasContactsPermission()) {
    try {
        contentResolver.query(ContactsContract.Contacts.CONTENT_URI, ...)
    } catch (e: SecurityException) {
        // Permission was revoked between check and use
        handlePermissionLost()
    }
} else {
    // Permission not available
    handleNoPermission()
}
```

### Step 4: Handle Permission Loss Gracefully

```kotlin
private fun handlePermissionLost() {
    // 1. Stop operations that need the permission
    // 2. Clear cached permission-dependent data
    // 3. Notify user if necessary (don't crash)
    // 4. Degrade gracefully to non-permission functionality
}
```

### Step 5: Service Resilience

For services like `NavigationListenerService`:

```kotlin
override fun onCreate() {
    super.onCreate()
    if (!hasRequiredPermissions()) {
        // Don't crash - just stop gracefully
        stopSelf()
        return
    }
    // ... normal initialization
}

override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (!hasRequiredPermissions()) {
        stopSelf()
        return START_NOT_STICKY  // Don't restart without permission
    }
    // ... normal operation
}
```

## Testing

1. Grant one-time permission
2. Use app with permission-dependent feature
3. Revoke permission via Settings > Apps > BothBubbles > Permissions
4. Verify app doesn't crash
5. Verify graceful degradation

## Success Criteria

- [ ] App doesn't crash when permissions revoked
- [ ] Services stop gracefully without crash loop
- [ ] User sees appropriate UI for missing permissions
- [ ] No SecurityException crashes in crash reporting
