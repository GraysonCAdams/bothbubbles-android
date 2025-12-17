// Top-level build file for BlueBubbles native Android app
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    // Note: Firebase Crashlytics/Performance plugins removed for privacy
    // App uses local-only crash reporting with manual opt-in sharing
}
