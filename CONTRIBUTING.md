# Contribution Guide

We encourage all contributions to this project! All we ask are you follow these simple rules when contributing:

* Write clean code
* Comment your code
* Follow Kotlin coding conventions

## Current Development Focus

The app is a **pure Kotlin/Jetpack Compose Android app** styled after Google Messages. Key priorities:

- **Conversation List**: Google Messages-style layout with iOS-style pinned section
- **Message View**: iMessage-style bubbles with delivery status
- **SMS/MMS Support**: Native Android SMS/MMS integration

See [PLAN_SMS_MMS_SUPPORT.md](PLAN_SMS_MMS_SUPPORT.md) for detailed feature status.

## Project Structure

```
bluebubbles-app/
├── app/                          # Main Android app module
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── kotlin/com/bluebubbles/
│       │   │   ├── data/         # Data layer (DB, API, repos)
│       │   │   ├── di/           # Hilt dependency injection
│       │   │   ├── services/     # Background services
│       │   │   └── ui/           # Compose UI
│       │   │       ├── chat/
│       │   │       ├── components/
│       │   │       ├── conversations/
│       │   │       ├── navigation/
│       │   │       ├── settings/
│       │   │       └── theme/
│       │   ├── res/              # Android resources
│       │   └── AndroidManifest.xml
│       ├── test/                 # Unit tests
│       └── androidTest/          # Instrumentation tests
├── gradle/
│   ├── libs.versions.toml        # Version catalog
│   └── wrapper/
├── build.gradle.kts              # Root build config
├── settings.gradle.kts
└── referential-old-app/          # Legacy Flutter code (reference only)
```

## Pre-requisites

* **Git**: [download](https://git-scm.com/downloads)
* **Android Studio** (Ladybug or newer): [download](https://developer.android.com/studio)
  - Includes bundled JDK 17+
  - Install Android SDK via SDK Manager
* **ADB** (included with Android SDK)

## Building the App

### Quick Start (Command Line)

```bash
# Clone the repository
git clone https://github.com/BlueBubblesApp/bluebubbles-app.git
cd bluebubbles-app

# Set JAVA_HOME to Android Studio's bundled JDK (macOS)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# Build debug APK
./gradlew :app:assembleDebug

# The APK will be at:
# app/build/outputs/apk/debug/app-debug.apk
```

### Build Commands

| Command | Description |
|---------|-------------|
| `./gradlew :app:assembleDebug` | Build debug APK |
| `./gradlew :app:assembleRelease` | Build release APK |
| `./gradlew :app:installDebug` | Build and install to connected device |
| `./gradlew clean` | Clean build cache |

### APK Output Locations

- **Debug:** `app/build/outputs/apk/debug/app-debug.apk`
- **Release:** `app/build/outputs/apk/release/app-release.apk`

### Installing on Device

```bash
# List connected devices
adb devices

# Install the APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch the app
adb shell am start -n com.bothbubbles/.MainActivity
```

### Building in Android Studio

1. Open the project in Android Studio
2. Wait for Gradle sync to complete
3. Select `app` configuration and your device
4. Click Run (or press Shift+F10)

### macOS Build Script

For convenience, add this to your shell profile (`~/.zshrc` or `~/.bashrc`):

```bash
# BlueBubbles build helper
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
alias bb-build='cd ~/Repos/bluebubbles-app && ./gradlew :app:assembleDebug'
alias bb-install='adb install -r ~/Repos/bluebubbles-app/app/build/outputs/apk/debug/app-debug.apk'
alias bb-run='bb-build && bb-install'
```

Then use:
```bash
bb-run  # Build and install in one command
```

## Forking the Repository

1. Create a GitHub account
2. Fork the repository: [here](https://github.com/BlueBubblesApp/bluebubbles-app)
3. Clone your forked repository:
    * HTTPS: `git clone https://github.com/<your-username>/bluebubbles-app.git`
    * SSH: `git clone git@github.com:<your-username>/bluebubbles-app.git`
4. Set the upstream to the main repo:
    * `git remote add upstream git@github.com:BlueBubblesApp/bluebubbles-app.git`
5. Fetch all branches:
    * `git fetch --all`
6. Pull the latest changes:
    * `git pull upstream master`

## Picking an Issue

Check out our [issues page](https://github.com/BlueBubblesApp/bluebubbles-app/issues):

* Filter by difficulty: `label:"Difficulty: Easy"`, `label:"Difficulty: Medium"`, `label:"Difficulty: Hard"`
* Filter by type: `bug` or `enhancement`
* New contributors: Look for `label:"good first issue"`

If you're working on something without an existing issue, please create one first.

## Committing Code

1. Create a branch:
    * `git checkout -b <your-name>/<feature|bug>/<short-descriptor>`
    * Example: `git checkout -b zach/feature/improved-animations`
2. Make your code changes
3. Stage your changes:
    * `git add -A` (all changes)
    * `git add <file>` (specific file)
4. Commit with a descriptive message:
    * `git commit -m "Description of your changes"`
5. Push to your fork:
    * `git push origin <your-branch-name>`

## Submitting a Pull Request

1. Go to your forked repository on GitHub
2. Go to the `Pull requests` tab
3. Create a new Pull Request to the main `master` branch
4. Include:
    * The problem
    * What your code solves
    * How you fixed it

## Architecture Overview

### Tech Stack

- **UI**: Jetpack Compose with Material 3
- **DI**: Hilt
- **Database**: Room
- **Networking**: Retrofit + OkHttp + Socket.IO
- **Async**: Kotlin Coroutines + Flow

### Key Patterns

- **MVVM**: ViewModels expose StateFlow to Composables
- **Repository Pattern**: Repositories abstract data sources
- **Single Activity**: Navigation via Compose Navigation

### Package Guide

| Package | Purpose |
|---------|---------|
| `data.local.db` | Room database, DAOs, entities |
| `data.remote.api` | Retrofit API interfaces, DTOs |
| `data.repository` | Repository implementations |
| `di` | Hilt modules |
| `services` | Background services (socket, sync, SMS) |
| `ui.components` | Reusable Compose components |
| `ui.conversations` | Conversation list screen |
| `ui.chat` | Chat/message screen |
| `ui.settings` | Settings screens |
| `ui.theme` | Material 3 theme |

## Troubleshooting

### Build fails with "Unable to locate Java Runtime"

Set JAVA_HOME to Android Studio's JDK:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

### Build fails with experimental API errors

Add `@OptIn(ExperimentalFoundationApi::class)` to functions using experimental APIs.

### Gradle sync fails

```bash
./gradlew clean
./gradlew --refresh-dependencies
```

### App won't install - signature mismatch

Uninstall existing app first:
```bash
adb uninstall com.bothbubbles
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Force stop and restart app

```bash
adb shell am force-stop com.bothbubbles
adb shell am start -n com.bothbubbles/.MainActivity
```
