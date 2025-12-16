# Phase 12 Implementation Tracking

## Status: Not Started

## Progress Tracker

### Firebase Crashlytics Setup

| Task | Status | PR | Notes |
|------|--------|-----|-------|
| Add Firebase BOM | ☐ Not Started | — | |
| Add Crashlytics dependency | ☐ Not Started | — | |
| Add Crashlytics plugin | ☐ Not Started | — | |
| Verify google-services.json | ☐ Not Started | — | Must exist |
| Test crash reporting | ☐ Not Started | — | Force crash in debug |

### Timber Migration

| Area | Files | Status | PR | Notes |
|------|-------|--------|-----|-------|
| CrashlyticsTree creation | 1 | ☐ Not Started | — | `util/logging/` |
| Services layer | ~20 | ☐ Not Started | — | High priority |
| Data layer | ~15 | ☐ Not Started | — | Repositories, DAOs |
| UI layer | ~30 | ☐ Not Started | — | ViewModels, Screens |
| Utils | ~10 | ☐ Not Started | — | |

### LeakCanary

| Task | Status | PR | Notes |
|------|--------|-----|-------|
| Add debugImplementation | ☐ Not Started | — | Auto-initializes |
| Verify detection works | ☐ Not Started | — | Manual test |

### Firebase Performance

| Task | Status | PR | Notes |
|------|--------|-----|-------|
| Add Performance dependency | ☐ Not Started | — | |
| Add Performance plugin | ☐ Not Started | — | |
| Verify metrics in console | ☐ Not Started | — | |

### DeveloperEventLog Integration

| Task | Status | PR | Notes |
|------|--------|-----|-------|
| Forward socket events to Timber | ☐ Not Started | — | |
| Forward FCM events to Timber | ☐ Not Started | — | |

## Log Migration Script

```bash
# Example regex for migrating Log calls to Timber
# Run in project root with sed/perl

# Log.d(TAG, "message") → Timber.d("message")
find . -name "*.kt" -exec sed -i '' 's/Log\.d(TAG, /Timber.d(/g' {} \;

# Log.e(TAG, "message", e) → Timber.e(e, "message")
# This one needs manual review due to parameter order change
```

## Files Modified

_To be updated as implementation progresses_

## Verification Checklist

Before marking Phase 12 complete:

- [ ] Firebase Console shows app registered
- [ ] Test crash appears in Crashlytics dashboard
- [ ] Timber logs appear in logcat (debug)
- [ ] Breadcrumbs appear before crash in Crashlytics
- [ ] LeakCanary notification appears on leak (debug)
- [ ] Performance metrics visible in Firebase Console
- [ ] App startup time not regressed > 10%
- [ ] APK size increase documented
