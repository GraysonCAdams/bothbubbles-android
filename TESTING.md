# Testing BlueBubbles on Android Emulator

This guide covers setting up an Android 16 (API 36) emulator for testing on **macOS**.

## Quick Start

```bash
# Set JAVA_HOME (required - macOS doesn't have Java by default)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# Start Android 16 emulator (in background)
~/Library/Android/sdk/emulator/emulator -avd Android16_BlueBubbles -no-snapshot-load &

# Wait for device and verify
adb wait-for-device
adb devices

# Build and install
flutter build apk --debug --flavor bothbubbles
adb install -r build/app/outputs/flutter-apk/app-bothbubbles-debug.apk

# Launch app (note: package is com.bothbubbles.messaging for bothbubbles flavor)
adb shell am start -n com.bothbubbles.messaging/com.bothbubbles.messaging.MainActivity
```

## Prerequisites

- macOS with Android Studio installed
- Flutter 3.24+
- Android SDK with command-line tools
- Java (uses Android Studio's bundled JDK)

## Setup Steps

### 1. Set Environment Variables

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME=~/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools
```

Add these to your `~/.zshrc` or `~/.bashrc` for persistence.

### 2. Install Android 16 System Image

```bash
# For SMS testing (lighter image)
sdkmanager "system-images;android-36;google_apis;arm64-v8a"

# For Google Play services (FCM, Google Sign-In)
sdkmanager "system-images;android-36;google_apis_playstore;arm64-v8a"

# Install the platform
sdkmanager "platforms;android-36"
```

### 3. Create an Android Virtual Device (AVD)

```bash
avdmanager create avd -n Android16_BlueBubbles -k "system-images;android-36;google_apis_playstore;arm64-v8a" -d "pixel_7"
```

### 4. Launch the Emulator

```bash
emulator -avd Android16_BlueBubbles
```

Additional flags:
- `-no-snapshot-load` - Start fresh without loading a snapshot
- `-wipe-data` - Wipe user data completely
- `-gpu host` - Use host GPU for better performance

## Build Commands

### Debug Build

```bash
flutter build apk --debug --flavor bothbubbles
```

APK location: `build/app/outputs/flutter-apk/app-bothbubbles-debug.apk`

### Release Build

```bash
flutter build apk --release --flavor bothbubbles
```

APK location: `build/app/outputs/flutter-apk/app-bothbubbles-release.apk`

### Clean Build (after major changes)

```bash
flutter clean
flutter pub get
flutter build apk --debug --flavor bothbubbles
```

## Installing and Running

### Install

```bash
adb install -r build/app/outputs/flutter-apk/app-bothbubbles-debug.apk
```

### Launch

```bash
adb shell am start -n com.bothbubbles.messaging/com.bothbubbles.messaging.MainActivity
```

### Force Restart (after reinstall)

```bash
adb shell am force-stop com.bothbubbles.messaging
adb shell am start -n com.bothbubbles.messaging/com.bothbubbles.messaging.MainActivity
```

## Development Workflow

### Quick Iteration One-Liner

```bash
flutter build apk --debug --flavor bothbubbles && \
adb install -r build/app/outputs/flutter-apk/app-bothbubbles-debug.apk && \
adb shell am force-stop com.bothbubbles.messaging && \
adb shell am start -n com.bothbubbles.messaging/com.bothbubbles.messaging.MainActivity
```

### Hot Reload (Fastest)

```bash
flutter run --flavor bothbubbles
```

## Testing SMS

### Send a Fake SMS to the Emulator

```bash
adb emu sms send 5551234567 "This is a test SMS message"
```

### Using Emulator Extended Controls

1. Click the `...` button on the emulator sidebar
2. Go to **Phone** section
3. Enter a phone number and message
4. Click **Send Message**

## Viewing Logs

```bash
# Flutter logs
flutter logs

# ADB logcat (filtered)
adb logcat | grep -i "bluebubbles\|flutter"
```

## Troubleshooting

### App Shows Old Code After Install

```bash
# Force stop, uninstall, and reinstall
adb shell am force-stop com.bothbubbles.messaging
adb uninstall com.bothbubbles.messaging
adb install build/app/outputs/flutter-apk/app-bothbubbles-debug.apk
adb shell am start -n com.bothbubbles.messaging/com.bothbubbles.messaging.MainActivity
```

### Clear App Data

```bash
adb shell pm clear com.bothbubbles.messaging
```

### Build Fails

```bash
flutter clean
flutter pub get
flutter build apk --debug --flavor bothbubbles
```

### ADB Not Detecting Emulator

```bash
adb kill-server
adb start-server
adb devices
```

### Java Not Found

Use Android Studio's bundled JDK:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

## Package Information

| Flavor | Build Type | Package Name | Activity |
|--------|------------|--------------|----------|
| bothbubbles | Debug | `com.bothbubbles.messaging` | `com.bothbubbles.messaging.MainActivity` |
| bothbubbles | Release | `com.bothbubbles.messaging` | `com.bothbubbles.messaging.MainActivity` |

**Note:** The `--flavor bothbubbles` flag is required for all builds.

## macOS-Specific Notes

### Java Runtime
macOS does not include Java by default. The Android build system requires Java, which is bundled with Android Studio.

**Always set JAVA_HOME before running Gradle/Flutter builds:**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

Add these lines to your `~/.zshrc` to make them persistent:
```bash
echo 'export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"' >> ~/.zshrc
echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.zshrc
```

### Common Errors

**"Unable to locate a Java Runtime"** - You forgot to set JAVA_HOME. Run the export commands above.

**"Gradle build failed to produce an .apk file"** - Usually a Java issue. Check JAVA_HOME is set correctly.
