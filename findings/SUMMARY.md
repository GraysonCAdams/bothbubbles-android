# BothBubbles ADB Log Analysis Summary

**Analysis Date**: 2025-12-17
**Log Period**: Last 3 hours + dropbox crash history

---

## Issues Found: 6

### Critical Issues (2)

| Issue | File | Description |
|-------|------|-------------|
| Room Schema Mismatch | [01-critical-room-schema-mismatch.md](01-critical-room-schema-mismatch.md) | Database schema changed without version increment. App crashes on QuickReplyTemplateDao access. |
| Android Auto Crash | [02-android-auto-crash.md](02-android-auto-crash.md) | ListTemplate action constraint violation in ConversationDetailScreen.kt:326 |

### Medium Issues (1)

| Issue | File | Description |
|-------|------|-------------|
| AppsFilter BLOCKED | [03-appsfilter-blocked-interactions.md](03-appsfilter-blocked-interactions.md) | 72 blocked interactions with Google Messages. Missing `<queries>` declaration. |

### Low/Info Issues (3)

| Issue | File | Description |
|-------|------|-------------|
| Notification Listener Spam | [04-notification-listener-binding-spam.md](04-notification-listener-binding-spam.md) | 20+ redundant binding attempts for NavigationListenerService |
| InsetsController Spam | [05-keyboard-insets-controller-spam.md](05-keyboard-insets-controller-spam.md) | 8 rapid keyboard hide calls in 1.4 seconds |
| Sony Provider Errors | [06-sony-provider-errors.md](06-sony-provider-errors.md) | Harmless attempts to access Sony-specific provider |

---

## Priority Fixes

### P0 - Fix Immediately
1. **Room Schema Mismatch** - App is crashing. Increment database version or add migration.

### P1 - Fix Soon
2. **Android Auto Crash** - Breaking feature. Fix action constraints in ConversationDetailScreen.kt

### P2 - Fix When Possible
3. **AppsFilter BLOCKED** - Add `<queries>` to AndroidManifest.xml for proper SMS app detection

### P3 - Nice to Have
4. **Notification Listener Spam** - Add state tracking to prevent redundant binds
5. **InsetsController Spam** - Debounce keyboard hide calls
6. **Sony Provider Errors** - Low priority cleanup

---

## No Crashes in Last 3 Hours
The current running session (PID 21796) shows no crashes or ANRs. The crashes found are from:
- 2025-12-15 (Android Auto)
- 2025-12-17 00:04-00:06 (Room schema)

---

## Positive Observations
- No socket/network connection issues detected
- No memory/OOM issues
- No ANRs for BothBubbles
- WorkManager tasks completing successfully
- App running stable in current session
