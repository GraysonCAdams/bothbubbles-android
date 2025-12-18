# Notification Listener Service Binding Spam

## Severity: LOW (Performance/Log noise)

## Occurrence Count
- 20+ binding attempts in rapid succession

## Log Pattern
```
V/NotificationManagerService.NotificationListeners( 1646): enabling notification listener for 0:
    ComponentInfo{com.bothbubbles.messaging/com.bothbubbles.services.eta.NavigationListenerService}
V/NotificationManagerService.NotificationListeners( 1646): Not registering
    ComponentInfo{com.bothbubbles.messaging/com.bothbubbles.services.eta.NavigationListenerService}
    is already binding
```

## Timeline
- 20:24:16 -> 20:24:34 -> 20:24:35 -> 20:24:43 -> 20:24:44 -> 20:25:14 -> 20:25:15 -> 20:25:23
- Multiple attempts within seconds of each other

## Impact
- Adds unnecessary system overhead
- Floods logs with verbose messages
- Indicates potentially redundant re-binding logic

## Root Cause Analysis
The `NavigationListenerService` (ETA feature) is repeatedly trying to bind/register as a notification listener when it's already in the binding process. This suggests:
1. Missing state check before attempting to bind
2. Possible lifecycle issue causing multiple bind attempts
3. Race condition in service initialization

## Recommended Fix
1. Add state tracking to prevent redundant binding attempts
2. Check if already binding before calling bind
3. Review `NavigationListenerService` lifecycle management

## Files to Check
- `app/src/main/kotlin/com/bothbubbles/services/eta/NavigationListenerService.kt`
