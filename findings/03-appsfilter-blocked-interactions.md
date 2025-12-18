# AppsFilter BLOCKED - Package Visibility Issue

## Severity: MEDIUM (Potential SMS functionality issues)

## Occurrence Count
- 72 BLOCKED interactions in analyzed logs

## Log Pattern
```
I/AppsFilter( 1646): interaction: PackageSetting{508046a com.bothbubbles.messaging/10547}
    -> PackageSetting{206de8a com.google.android.apps.messaging/10150} BLOCKED
```

## What This Means
Starting with Android 11 (API 30), apps need to declare which other packages they want to interact with. The `AppsFilter` is blocking BothBubbles from querying/interacting with Google Messages (com.google.android.apps.messaging).

## Impact
- May affect ability to detect default SMS app
- Could impact SMS/MMS functionality that checks Google Messages state
- May cause failures when trying to check if Google Messages is handling RCS

## Recommended Fix
Add `<queries>` element to `AndroidManifest.xml`:

```xml
<manifest>
    <queries>
        <!-- For checking default SMS app -->
        <intent>
            <action android:name="android.intent.action.SENDTO" />
            <data android:scheme="smsto" />
        </intent>

        <!-- For Google Messages specifically -->
        <package android:name="com.google.android.apps.messaging" />

        <!-- For other SMS apps -->
        <intent>
            <action android:name="android.intent.action.SEND" />
            <data android:mimeType="text/plain" />
        </intent>
    </queries>
</manifest>
```

## Files to Check
- `app/src/main/AndroidManifest.xml`
- Any code querying Google Messages package
