plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    // Note: Firebase Crashlytics/Analytics/Performance removed for privacy
    // Using local-only crash reporting with manual opt-in sharing
}

android {
    namespace = "com.bothbubbles"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bothbubbles.messaging"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

// Room schema export for migration testing
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core modules
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:design"))
    implementation(project(":navigation"))

    // Feature modules
    implementation(project(":feature:settings"))
    implementation(project(":feature:setup"))
    implementation(project(":feature:conversations"))
    implementation(project(":feature:chat"))

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Linting
    lintChecks(libs.slack.compose.lint)

    // Immutable Collections
    implementation(libs.kotlinx.collections.immutable)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Material (for MaterialColors.harmonize in dynamic theming)
    implementation(libs.google.material)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Networking - Retrofit + OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // JSON - Moshi
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.codegen)

    // Socket.IO
    implementation(libs.socketio)

    // Database - Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Images - Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.video)

    // HEIC/AVIF decoding with alpha support
    implementation(libs.avif.coder)

    // Blurhash placeholder decoding
    implementation(libs.blurhash)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Media3 (ExoPlayer)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    // ML Kit
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.mlkit.smart.reply)
    implementation(libs.mlkit.entity.extraction)

    // WorkManager & Startup
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.startup)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Firebase (only messaging for push notifications - no tracking/analytics)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.database)
    implementation(libs.firebase.firestore)

    // Google Play Services
    implementation(libs.play.services.location)

    // Android Auto (Car App Library)
    implementation(libs.car.app)
    implementation(libs.car.app.projected)

    // Phone Number Formatting
    implementation(libs.libphonenumber)

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // LeakCanary (debug only)
    debugImplementation(libs.leakcanary)

    // ACRA - Local crash reporting with manual opt-in sharing (no automatic data collection)
    implementation(libs.acra.core)
    implementation(libs.acra.dialog)
    implementation(libs.acra.mail)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.room.testing)

    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
