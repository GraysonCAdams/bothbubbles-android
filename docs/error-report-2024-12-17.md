# BothBubbles Error Report - December 17, 2024

**Generated from ADB Logcat scan**
**Time range:** 17:12 - 17:24 (logs available in buffer)
**Device:** 57080DLCH0001M

---

## Summary

| Severity | Count | Description |
|----------|-------|-------------|
| Critical | 1 | Process killed due to permission revocation |
| Warning | 6 | Service crashes, timeouts, permission denials |
| Info | 18+ | App filter blocks (expected behavior) |

---

## Critical Issues

### 1. Process Killed - Permission Revoked

**Time:** 17:12:03.634
**Log:**
```
I ActivityManager: Killing 5242:com.bothbubbles.messaging/u0a547 (adj 250): one-time permission revoked
```

**Impact:** The entire app process was terminated because a one-time permission was revoked.

**Root Cause:** User may have granted a one-time permission (likely location or contacts) that expired or was revoked, causing Android to kill the process.

**Recommendation:** Implement graceful permission handling - check permission status before operations and handle `SecurityException` gracefully instead of crashing.

---

## Warning Issues

### 2. NavigationListenerService Crash Loop

**Time:** 17:12:03.636 - 17:12:09.320
**Logs:**
```
W ActivityManager: Scheduling restart of crashed service com.bothbubbles.messaging/com.bothbubbles.services.eta.NavigationListenerService in 1000ms for connection
W ActivityManager: Rescheduling restart of crashed service ... in 9961ms for mem-pressure-event
W ActivityManager: Rescheduling restart of crashed service ... in 0ms for mem-pressure-event
```

**Impact:** `NavigationListenerService` crashed and Android attempted multiple restarts.

**Root Cause:** Service likely crashed when the process was killed (consequence of issue #1), but the repeated restart attempts suggest the service may have initialization issues.

**Recommendation:**
- Add try-catch in `NavigationListenerService.onCreate()` and `onBind()`
- Check permissions before accessing protected resources
- Consider using `START_NOT_STICKY` if service shouldn't restart on failure

---

### 3. Permission Denial - Contacts Provider

**Time:** 17:12:10.939
**Log:**
```
W ContentProviderHelper: Permission Denial: opening provider com.android.providers.contacts.ContactsProvider2 from ProcessRecord{1c3c053 7372:com.bothbubbles.messaging/u0a547} (pid=7372, uid=10547) requires android.permission.READ_CONTACTS or android.permission.WRITE_CONTACTS
```

**Impact:** App attempted to access contacts without proper permission.

**Root Cause:** `READ_CONTACTS` or `WRITE_CONTACTS` permission not granted or was revoked.

**Recommendation:**
- Check `checkSelfPermission()` before accessing ContactsProvider
- Handle the case gracefully if permission is denied
- Don't auto-restart services that need permissions without checking first

---

### 4. Activity Timeout Events

**Time:** 17:21:19.166
**Logs:**
```
W ActivityTaskManager: Activity top resumed state loss timeout for ActivityRecord{125660854 u0 com.bothbubbles.messaging/com.bothbubbles.MainActivity t5770}
W ActivityTaskManager: Activity pause timeout for ActivityRecord{125660854 u0 com.bothbubbles.messaging/com.bothbubbles.MainActivity t5770}
```

**Impact:** MainActivity took too long to respond to lifecycle events.

**Root Cause:** Possibly heavy operations on the main thread during activity transitions, or ANR-like behavior.

**Recommendation:**
- Review `onPause()` implementation in MainActivity
- Move heavy operations off the main thread
- Check for blocking I/O or synchronous network calls

---

### 5. Unexpected Activity Event

**Time:** 17:12:03.648
**Log:**
```
W UsageStatsService: Unexpected activity event reported! (com.bothbubbles.messaging/com.bothbubbles.MainActivity event : 23 instanceId : 81239694)
```

**Impact:** Unexpected lifecycle event reported to UsageStatsService.

**Root Cause:** Likely caused by the process being killed unexpectedly (consequence of issue #1).

**Recommendation:** This is a side effect - fixing the root cause (permission handling) should resolve this.

---

## Informational (Expected Behavior)

### 6. AppsFilter BLOCKED Interactions

Multiple logs showing blocked interactions with other apps:
- `com.mobileiron` (MDM software)
- `ginlemon.flowerfree` (launcher)
- `com.verizon.services` (carrier app - 16 instances)

**Assessment:** This is **expected Android 11+ behavior**. Apps are sandboxed by default and cannot query/interact with apps not declared in their manifest.

**No action required** unless the app specifically needs to interact with these packages.

---

### 7. NotificationListener Binding Attempts

Multiple logs showing:
```
V NotificationManagerService.NotificationListeners: Not registering ... is already binding
```

**Assessment:** This indicates the `NavigationListenerService` was already in the process of binding. **Normal behavior** during service registration, though frequency suggests possible over-eager registration calls.

---

## Event Timeline

| Time | Event |
|------|-------|
| 17:12:03.634 | Process killed (permission revoked) |
| 17:12:03.636 | NavigationListenerService crash detected |
| 17:12:03.841 | Window death, notification listener lost |
| 17:12:09.387 | Process restarted for service |
| 17:12:10.905 | Notification listener reconnected |
| 17:12:10.939 | Permission denial accessing contacts |
| 17:18:37+ | Multiple notification listener binding attempts |
| 17:21:19.166 | Activity pause/resume timeout |
| 17:22:55+ | Multiple AppsFilter blocks |

---

## Recommended Actions

### High Priority

1. **Fix permission handling in NavigationListenerService**
   - Location: `services/eta/NavigationListenerService.kt`
   - Add permission checks before accessing contacts
   - Wrap initialization in try-catch

2. **Review MainActivity lifecycle handling**
   - Ensure no blocking operations in `onPause()`/`onResume()`
   - Check for ANR risks

### Medium Priority

3. **Audit one-time permission usage**
   - Identify which permission was revoked
   - Implement proper permission lifecycle handling

### Low Priority

4. **Review NotificationListener registration frequency**
   - Multiple "already binding" logs suggest possible redundant registration calls

---

## Raw Logs

<details>
<summary>Click to expand full log dump</summary>

```
12-17 17:12:03.634  1646  1938 I ActivityManager: Killing 5242:com.bothbubbles.messaging/u0a547 (adj 250): one-time permission revoked
12-17 17:12:03.636  1646  1938 W ActivityManager: Scheduling restart of crashed service com.bothbubbles.messaging/com.bothbubbles.services.eta.NavigationListenerService in 1000ms for connection
12-17 17:12:03.648  1646  2551 W UsageStatsService: Unexpected activity event reported! (com.bothbubbles.messaging/com.bothbubbles.MainActivity event : 23 instanceId : 81239694)
12-17 17:12:03.675  1646  1821 W ActivityManager: Rescheduling restart of crashed service com.bothbubbles.messaging/com.bothbubbles.services.eta.NavigationListenerService in 9961ms for mem-pressure-event
12-17 17:12:03.839  1646  5097 V ActivityManager: Got obituary of 5242:com.bothbubbles.messaging
12-17 17:12:03.841  1646  1820 D NotificationManagerService.NotificationListeners: Removing active service ComponentInfo{com.bothbubbles.messaging/com.bothbubbles.services.eta.NavigationListenerService}
12-17 17:12:03.841  1646  3821 I WindowManager: WIN DEATH: Window{8aacc6c u0 com.bothbubbles.messaging/com.bothbubbles.MainActivity}
12-17 17:12:03.842  1646  1646 V NotificationManagerService.NotificationListeners: 0 notification listener connection lost: ComponentInfo{com.bothbubbles.messaging/com.bothbubbles.services.eta.NavigationListenerService}
12-17 17:12:09.320  1646  1821 W ActivityManager: Rescheduling restart of crashed service com.bothbubbles.messaging/com.bothbubbles.services.eta.NavigationListenerService in 0ms for mem-pressure-event
12-17 17:12:09.387  1646  1826 I ActivityManager: Start proc 7372:com.bothbubbles.messaging/u0a547 for service {com.bothbubbles.messaging/com.bothbubbles.services.eta.NavigationListenerService}
12-17 17:12:10.905  1646  1646 V NotificationManagerService.NotificationListeners: 0 notification listener service connected: ComponentInfo{com.bothbubbles.messaging/com.bothbubbles.services.eta.NavigationListenerService}
12-17 17:12:10.939  1646  2818 W ContentProviderHelper: Permission Denial: opening provider com.android.providers.contacts.ContactsProvider2 from ProcessRecord{1c3c053 7372:com.bothbubbles.messaging/u0a547} (pid=7372, uid=10547) requires android.permission.READ_CONTACTS or android.permission.WRITE_CONTACTS
12-17 17:21:19.166  1646  1805 W ActivityTaskManager: Activity top resumed state loss timeout for ActivityRecord{125660854 u0 com.bothbubbles.messaging/com.bothbubbles.MainActivity t5770}
12-17 17:21:19.166  1646  1805 W ActivityTaskManager: Activity pause timeout for ActivityRecord{125660854 u0 com.bothbubbles.messaging/com.bothbubbles.MainActivity t5770}
```

</details>
