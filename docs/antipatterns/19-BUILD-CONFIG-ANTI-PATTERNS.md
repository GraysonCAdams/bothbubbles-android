# Build Configuration Anti-Patterns

**Scope:** Gradle, ProGuard, dependencies, lint

---

## High Severity Issues

### 1. Missing ProGuard Rules for Critical Libraries - FIXED (2025-12-20)

**Location:** `app/proguard-rules.pro`

**Previously Missing Rules For:**
- **Hilt DI** - `@HiltAndroidApp`, `@Inject`, module classes
- **Firebase Messaging** - `FirebaseMessagingService`
- **CameraX** - Camera lifecycle management
- **ML Kit** - Barcode scanning, smart reply, entity extraction
- **Lifecycle** - `ViewModel`, `ViewModelProvider`, observers
- **ACRA** - Crash handler classes

**Risk:** Runtime crashes due to obfuscation of essential classes.

**Resolution:** All rules have been added to `proguard-rules.pro`:
```proguard
# Hilt DI
-keep class com.bothbubbles.di.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class *
-keep @javax.inject.* class * { *; }

# Firebase Messaging
-keep class com.google.firebase.messaging.FirebaseMessagingService { *; }

# CameraX
-keep class androidx.camera.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }

# AndroidX Lifecycle
-keep class androidx.lifecycle.** { *; }

# ACRA
-keep class org.acra.** { *; }
```

---

## Medium Severity Issues

### 2. Hardcoded Timber Dependency Version - FIXED (2025-12-20)

**Locations:**
- `app/build.gradle.kts` (line 214)
- `core/network/build.gradle.kts` (line 62)

**Issue:**
```kotlin
implementation("com.jakewharton.timber:timber:5.0.1")  // Hardcoded!
```

All other dependencies use version catalog pattern.

**Resolution:** Added to `gradle/libs.versions.toml`:
```toml
timber = "5.0.1"
[libraries]
timber = { group = "com.jakewharton.timber", name = "timber", version.ref = "timber" }
```

Both build files now use `implementation(libs.timber)`.

---

### 3. Exposed API Dependencies in core:network

**Location:** `core/network/build.gradle.kts` (lines 31-38)

**Issue:**
```kotlin
api(libs.retrofit)              // Exposes Retrofit
api(libs.okhttp)                // Exposes OkHttp
api(libs.moshi)                 // Exposes Moshi
```

**Problem:** Tight coupling - downstream modules depend on implementation details.

**Fix:** Change to `implementation()`. Other modules should depend on abstractions.

---

### 4. Missing Lint Configuration - FIXED (2025-12-20)

**Location:** `app/build.gradle.kts`

**Issue:** No explicit lint configuration block despite using Slack Compose lint checks.

**Fix:**
```kotlin
android {
    lint {
        checkReleaseBuilds = true
        abortOnError = false
        disable += listOf("MissingTranslation", "ExtraTranslation")
    }
}
```

**Resolution:** Lint configuration added to `app/build.gradle.kts` with proper settings for release builds and translation checks.

---

## Low Severity Issues

### 5. Beta/Alpha Dependencies in Production

**Location:** `gradle/libs.versions.toml`

**Dependencies:**
- `mlkitEntityExtraction = "16.0.0-beta5"` - ML Kit beta
- `securityCrypto = "1.1.0-alpha06"` - Security Crypto alpha

**Recommendation:** Monitor for stability, pin until stable releases.

---

### 6. Legacy Jetifier Enabled

**Location:** `gradle.properties` (line 9)

**Issue:** `android.enableJetifier=true`

**Note:** All modern libraries support AndroidX. Can be safely removed after dependency audit.

---

## Positive Findings

- 99% of dependencies use version catalog pattern
- Resource shrinking enabled: `isShrinkResources = true`
- Minification properly configured in release
- Consistent SDK versions across modules (compileSdk=35, targetSdk=35, minSdk=26)
- `FAIL_ON_PROJECT_REPOS` enforced
- Room schema export enabled for migrations
- KSP properly configured for Room and Hilt

---

## Summary Table

| Issue | Severity | File | Type | Status |
|-------|----------|------|------|--------|
| Missing ProGuard rules | HIGH | proguard-rules.pro | Minification | FIXED (2025-12-20) |
| Hardcoded Timber version | MEDIUM | build.gradle.kts (2 files) | Version Management | FIXED (2025-12-20) |
| Exposed API dependencies | MEDIUM | core/network/build.gradle.kts | Architecture | Open |
| No lint configuration | MEDIUM | app/build.gradle.kts | Quality Gate | FIXED (2025-12-20) |
| Beta/Alpha dependencies | LOW | libs.versions.toml | Stability | Open |
| Legacy Jetifier | LOW | gradle.properties | Modernization | Open |
