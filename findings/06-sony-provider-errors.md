# Sony Resource Provider Errors

## Status: FIXED

## Severity: INFO (Harmless but noisy)

## Occurrence Count
- 4 occurrences during app launch

## Log Pattern
```
E/ActivityThread(21796): Failed to find provider info for com.sonymobile.home.resourceprovider
```

## What This Means
The app (or a library) is trying to access a content provider specific to Sony devices. This provider doesn't exist on the device being tested (appears to be a Pixel based on "google/blazer" in build info).

## Impact
- No functional impact
- Adds error noise to logs
- Could slow down resource loading slightly

## Root Cause Analysis
Some code path is attempting to access Sony-specific resources, likely:
1. A third-party library checking for device-specific theming
2. Legacy code that checked for manufacturer-specific customizations
3. A resource overlay system trying various providers

## Recommended Fix
This is low priority but could be cleaned up by:
1. Identifying which code/library is making this call
2. Adding manufacturer checks before attempting access
3. Catching/suppressing this specific ContentResolver failure

## Fix Applied
Added manufacturer checks in `NotificationService.kt` to only call manufacturer-specific badge APIs on the appropriate devices:
- Samsung badge API only called when `Build.MANUFACTURER == "samsung"`
- Sony badge API only called when `Build.MANUFACTURER == "sony"`

This prevents the ContentResolver from logging errors when trying to access non-existent providers on other devices.
