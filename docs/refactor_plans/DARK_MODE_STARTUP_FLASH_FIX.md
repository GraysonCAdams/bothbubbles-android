# Dark Mode Startup Flash Fix

## Problem Statement

When opening the app from the app switcher (or cold start) while in dark mode, users experience a jarring white flash before the dark mode UI renders. This creates a poor user experience that feels unpolished.

## Root Cause Analysis

The flash occurs due to a timing gap between Android's system-level window rendering and Compose's theme application:

### Current Architecture

1. **XML Theme** ([app/src/main/res/values/themes.xml](app/src/main/res/values/themes.xml)):
   - Uses `Theme.Material.Light.NoActionBar` as parent (light theme only)
   - No `windowBackground` attribute set (defaults to white)
   - No `values-night/` directory exists (no dark mode XML theme)

2. **Compose Theme** ([app/src/main/kotlin/com/bothbubbles/ui/theme/Theme.kt](app/src/main/kotlin/com/bothbubbles/ui/theme/Theme.kt)):
   - Uses `isSystemInDarkTheme()` to detect dark mode
   - Applies `DarkColorScheme` with background `Color(0xFF1C1B1F)` (~dark gray)

3. **Startup Flow** ([app/src/main/kotlin/com/bothbubbles/MainActivity.kt](app/src/main/kotlin/com/bothbubbles/MainActivity.kt)):
   - `installSplashScreen()` called before `super.onCreate()`
   - `BothBubblesTheme` wraps content in `setContent {}`

### The Timing Gap

```
Timeline of App Launch/Resume:

┌─────────────────────────────────────────────────────────────────────┐
│ 1. System creates "Starting Window" using XML theme                 │
│    └─ windowBackground = white (from Light theme, no dark override) │
├─────────────────────────────────────────────────────────────────────┤
│ 2. SplashScreen displays (also uses XML theme background)           │
│    └─ Background color = white                                      │
├─────────────────────────────────────────────────────────────────────┤
│ 3. Activity.onCreate() runs                                         │
├─────────────────────────────────────────────────────────────────────┤
│ 4. setContent {} executes                                           │
├─────────────────────────────────────────────────────────────────────┤
│ 5. BothBubblesTheme applies (Compose level)                        │
│    └─ isSystemInDarkTheme() → uses DarkColorScheme                  │
├─────────────────────────────────────────────────────────────────────┤
│ 6. First Compose frame renders with dark background                 │
│    └─ WHITE → DARK transition = FLASH                               │
└─────────────────────────────────────────────────────────────────────┘
```

The problem is that steps 1-4 use XML-defined colors (white), while step 5-6 use Compose-defined colors (dark). The user sees this as a flash.

## Solution Overview

Fix the issue by making the XML theme aware of dark mode so the starting window and splash screen match the final Compose theme.

### Required Changes

#### 1. Create Color Resources

**Update `app/src/main/res/values/colors.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#1E88E5</color>
    <!-- Match Compose LightColorScheme.background exactly -->
    <color name="light_background">#FFFBFE</color>
    <color name="light_surface">#FFFBFE</color>
</resources>
```

**Create `app/src/main/res/values-night/colors.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Match Compose DarkColorScheme.background exactly -->
    <color name="dark_background">#1C1B1F</color>
    <color name="dark_surface">#1C1B1F</color>
</resources>
```

#### 2. Create Theme Resources with Dedicated Splash Theme

We will use the `androidx.core:core-splashscreen` library's best practices by creating a dedicated splash theme that transitions to the main theme.

**Update `app/src/main/res/values/themes.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Base App Theme -->
    <style name="Theme.BothBubbles" parent="android:Theme.Material.Light.NoActionBar">
        <!-- Match Compose LightColorScheme.background (0xFFFFFBFE) -->
        <item name="android:windowBackground">@color/light_background</item>
        <item name="android:colorBackground">@color/light_background</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:windowLightNavigationBar">true</item>
        <item name="android:enforceStatusBarContrast">false</item>
        <item name="android:enforceNavigationBarContrast">false</item>
        <item name="android:windowLayoutInDisplayCutoutMode">shortEdges</item>
    </style>

    <!-- Dedicated Splash Theme -->
    <style name="Theme.BothBubbles.Splash" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">@color/light_background</item>
        <item name="windowSplashScreenAnimatedIcon">@mipmap/ic_launcher</item>
        <item name="postSplashScreenTheme">@style/Theme.BothBubbles</item>
    </style>
</resources>
```

**Create `app/src/main/res/values-night/themes.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Base App Theme (Dark) -->
    <style name="Theme.BothBubbles" parent="android:Theme.Material.NoActionBar">
        <!-- Match Compose DarkColorScheme.background (0xFF1C1B1F) -->
        <item name="android:windowBackground">@color/dark_background</item>
        <item name="android:colorBackground">@color/dark_background</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:windowLightNavigationBar">false</item>
        <item name="android:enforceStatusBarContrast">false</item>
        <item name="android:enforceNavigationBarContrast">false</item>
        <item name="android:windowLayoutInDisplayCutoutMode">shortEdges</item>
    </style>

    <!-- Dedicated Splash Theme (Dark) -->
    <style name="Theme.BothBubbles.Splash" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">@color/dark_background</item>
        <item name="windowSplashScreenAnimatedIcon">@mipmap/ic_launcher</item>
        <item name="postSplashScreenTheme">@style/Theme.BothBubbles</item>
    </style>
</resources>
```

#### 3. Update Manifest

**Update `app/src/main/AndroidManifest.xml`:**

Change the `android:theme` attribute for the `<application>` and `<activity>` tags to use the new splash theme:

```xml
<application
    ...
    android:theme="@style/Theme.BothBubbles.Splash">
    
    <activity
        android:name=".MainActivity"
        ...
        android:theme="@style/Theme.BothBubbles.Splash">
```

## Color Synchronization

**Critical**: The XML colors MUST match the Compose colors exactly to prevent any flash.

| Context | Light Mode | Dark Mode |
|---------|------------|-----------|
| Compose `background` | `0xFFFFFBFE` | `0xFF1C1B1F` |
| XML `windowBackground` | `#FFFBFE` | `#1C1B1F` |
| Splash Background | `#FFFBFE` | `#1C1B1F` |

### Dynamic Color Synchronization (Required for Android 12+)

Since `BothBubblesTheme` uses dynamic colors (Material You) by default on Android 12+, the window background must be updated at runtime to match the user's wallpaper-generated theme. Without this, the app will launch with the neutral XML background and then "flash" to the tinted dynamic background.

**Update `MainActivity.kt`:**

```kotlin
// In MainActivity.onCreate(), after installSplashScreen():
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    val isDark = resources.configuration.uiMode and
                 Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val dynamicScheme = if (isDark) {
        dynamicDarkColorScheme(this)
    } else {
        dynamicLightColorScheme(this)
    }
    // Sync window background with dynamic theme
    window.decorView.setBackgroundColor(dynamicScheme.background.toArgb())
}
```

This ensures pixel-perfect matching with dynamic colors.

## Implementation Checklist

- [x] Create `app/src/main/res/values-night/` directory
- [x] Create `app/src/main/res/values-night/colors.xml` with dark colors
- [x] Update `app/src/main/res/values/colors.xml` with light background color
- [x] Update `app/src/main/res/values/themes.xml` with `Theme.BothBubbles.Splash`
- [x] Create `app/src/main/res/values-night/themes.xml` with `Theme.BothBubbles.Splash`
- [x] Update `AndroidManifest.xml` to use `@style/Theme.BothBubbles.Splash`
- [x] Add dynamic color sync logic in `MainActivity.onCreate()`
- [ ] Test on Android 11 (pre-splash API)
- [ ] Test on Android 12+ (with splash API and dynamic colors)
- [ ] Test light mode startup
- [ ] Test dark mode startup
- [ ] Test app switcher resume in both modes
- [ ] Test cold start in both modes

## Additional Considerations

### App Switcher Thumbnail

The app switcher shows a snapshot of the app's last visible state. If the user switches away while in dark mode, the snapshot is dark. When returning, Android briefly shows the starting window (using XML theme) before the snapshot, which can also cause a flash.

Setting `windowBackground` correctly fixes this because the starting window will match the expected theme.

### Configuration Changes

When the system theme changes (user toggles dark mode in system settings), the activity may recreate. Ensure `configChanges` in the manifest doesn't include `uiMode` unless you handle the theme change manually:

```xml
<!-- Don't add uiMode here - let the system recreate for theme changes -->
<activity
    android:name=".MainActivity"
    android:configChanges="orientation|screenSize|keyboardHidden"
    ... />
```

### Edge-to-Edge Considerations

Since the app uses `enableEdgeToEdge()`, ensure the `windowBackground` color works well when status/navigation bars are transparent. The MD3 baseline colors are designed for this.

## Testing Procedure

1. **Cold Start Test**:
   - Force stop the app
   - Enable dark mode in system settings
   - Launch app from launcher
   - Verify no white flash

2. **App Switcher Test**:
   - Open app in dark mode
   - Switch to another app
   - Return via app switcher
   - Verify no white flash

3. **Quick Switch Test**:
   - Open app in dark mode
   - Quickly switch away and back multiple times
   - Verify no flashing

4. **Theme Toggle Test**:
   - While app is in foreground, toggle system dark mode
   - Verify smooth transition (activity recreate is acceptable)

## References

- [Android SplashScreen API](https://developer.android.com/develop/ui/views/launch/splash-screen)
- [Material Design 3 Color System](https://m3.material.io/styles/color/system/overview)
- [AndroidX SplashScreen Library](https://developer.android.com/jetpack/androidx/releases/core-splashscreen)
- [Dark Theme Best Practices](https://developer.android.com/develop/ui/views/theming/darktheme)
