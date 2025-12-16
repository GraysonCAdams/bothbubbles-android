# Phase 6: Modularization (Optional) — Unified Implementation Plan

> **Status**: ✅ COMPLETE (2024-12-16)
> **Blocking**: None
> **Code Changes**: `:core:model` extracted
> **Risk Level**: Low (Data classes only)

## Overview

This phase creates Gradle modules to enforce architectural boundaries at compile time. `:core:model` has been extracted to separate pure data classes from the Android app module.

## When to Do This

Only pursue this if:
- [ ] Build times are painful (>2-3 minutes for incremental)
- [ ] Multiple contributors repeatedly break architectural boundaries
- [ ] You want compiler-enforced "UI cannot touch DB directly"
- [ ] Team has multi-module experience

## When NOT to Do This

- Solo developer with small codebase
- Build times are acceptable (<2 minutes)
- Phases 2-4 not yet complete
- Team doesn't have multi-module experience

## Benefits

| Benefit | Explanation |
|---------|-------------|
| **Faster builds** | Only rebuild changed modules |
| **Enforced boundaries** | Compiler prevents UI → DB direct access |
| **Parallel compilation** | Multiple modules build simultaneously |
| **Clear ownership** | Each module has defined responsibility |

## Current State

```kotlin
// settings.gradle.kts
include(":app")
include(":core:model")
```

## Target State

```kotlin
// settings.gradle.kts
include(":app")
include(":core:model")
// Deferred:
// include(":core:data")
// include(":core:network")
```

## Recommended Module Structure

```
project/
├── app/                      # Application shell
│   └── src/main/kotlin/
│       └── BothBubblesApp.kt
│       └── MainActivity.kt
│       └── di/AppModule.kt
│
├── core/
│   ├── model/                # Data classes, no dependencies
│   │   └── MessageEntity.kt
│   │   └── ChatEntity.kt
│   │   └── enums/
│   │
│   ├── data/                 # Room, DAOs, Repositories
│   │   └── db/
│   │   └── repository/
│   │   └── di/DataModule.kt
│   │
│   └── network/              # Retrofit, API interfaces
│       └── api/
│       └── di/NetworkModule.kt
│
└── feature/                  # Optional feature modules
    ├── chat/
    └── conversations/
```

## Implementation Tasks

### Task 1: Extract :core:model (Lowest Risk)

Start with pure data classes - no Android dependencies.

**settings.gradle.kts:**
```kotlin
include(":app")
include(":core:model")
```

**core/model/build.gradle.kts:**
```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.bothbubbles.core.model"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    // Minimal dependencies
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.serialization.json)
}
```

**Move these files:**
```
app/src/main/kotlin/com/bothbubbles/data/local/db/entity/*.kt
    → core/model/src/main/kotlin/com/bothbubbles/core/model/entity/*.kt

app/src/main/kotlin/com/bothbubbles/data/model/*.kt
    → core/model/src/main/kotlin/com/bothbubbles/core/model/*.kt
```

**Update app/build.gradle.kts:**
```kotlin
dependencies {
    implementation(project(":core:model"))
    // ... other deps
}
```

### Task 2: Extract :core:data

**core/data/build.gradle.kts:**
```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.bothbubbles.core.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    implementation(project(":core:model"))

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
```

**Move these files:**
```
app/src/main/kotlin/com/bothbubbles/data/local/db/dao/*.kt
    → core/data/src/main/kotlin/com/bothbubbles/core/data/db/dao/*.kt

app/src/main/kotlin/com/bothbubbles/data/repository/*.kt
    → core/data/src/main/kotlin/com/bothbubbles/core/data/repository/*.kt

app/src/main/kotlin/com/bothbubbles/di/DatabaseModule.kt
    → core/data/src/main/kotlin/com/bothbubbles/core/data/di/DatabaseModule.kt
```

### Task 3: Extract :core:network

**core/network/build.gradle.kts:**
```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.bothbubbles.core.network"
    compileSdk = 35
}

dependencies {
    implementation(project(":core:model"))

    // Retrofit
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Moshi
    implementation(libs.moshi)
    ksp(libs.moshi.codegen)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
```

**Move these files:**
```
app/src/main/kotlin/com/bothbubbles/data/remote/api/*.kt
    → core/network/src/main/kotlin/com/bothbubbles/core/network/api/*.kt

app/src/main/kotlin/com/bothbubbles/di/NetworkModule.kt
    → core/network/src/main/kotlin/com/bothbubbles/core/network/di/NetworkModule.kt
```

### Task 4: Update App Module Dependencies

**app/build.gradle.kts:**
```kotlin
dependencies {
    // Core modules
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))

    // UI dependencies stay in app
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // ... etc
}
```

## Dependency Graph

```
        ┌─────────────────┐
        │      :app       │
        │  (UI, Services) │
        └────────┬────────┘
                 │
    ┌────────────┼────────────┐
    │            │            │
    ▼            ▼            ▼
┌────────┐  ┌────────┐  ┌──────────┐
│:core:  │  │:core:  │  │:core:    │
│ data   │  │network │  │ model    │
└────┬───┘  └────┬───┘  └──────────┘
     │           │            ▲
     └───────────┴────────────┘
         (both depend on model)
```

## Hilt with Multi-Module

Each module needs its own `@Module`:

**core/data/di/DataModule.kt:**
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "bothbubbles.db"
        ).build()
    }

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideChatDao(db: AppDatabase): ChatDao = db.chatDao()
}
```

**core/network/di/NetworkModule.kt:**
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.example.com/")
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideApi(retrofit: Retrofit): BothBubblesApi {
        return retrofit.create(BothBubblesApi::class.java)
    }
}
```

## Common Pitfalls

### 1. Circular Dependencies

```kotlin
// BAD - Circular dependency
:core:data depends on :core:network
:core:network depends on :core:data
```

**Fix**: Extract shared types to `:core:model` which both depend on.

### 2. Hilt Component Mismatch

```kotlin
// BAD - Module installed in wrong component
@Module
@InstallIn(ActivityComponent::class)  // Can't be used by Singletons!
object DataModule { ... }
```

**Fix**: Use `SingletonComponent` for shared modules.

### 3. Missing API Visibility

```kotlin
// BAD - Internal class exposed in public API
// core/data/src/.../Repository.kt
class MessageRepository internal constructor(...)

// app/ tries to use it
val repo = MessageRepository(...)  // Compile error!
```

**Fix**: Make public APIs `public` or use Hilt to inject them.

## Feature Modules (Advanced — Not Recommended Initially)

Feature modules add significant navigation complexity:

```kotlin
// feature/chat/build.gradle.kts
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))

    // Feature modules should NOT depend on each other
    // implementation(project(":feature:conversations"))  // BAD
}
```

**Recommendation**: Start with `:core:*` modules only. Defer feature modules until core is stable.

## Exit Criteria

- [ ] At least `:core:model` extracted and building
- [ ] Optionally `:core:data` and `:core:network` extracted
- [ ] Build times improved (or at least not worse)
- [ ] Architectural boundaries are compiler-enforced
- [ ] All tests pass
- [ ] No circular dependencies

## Verification Commands

```bash
# Build each module independently
./gradlew :core:model:assembleDebug
./gradlew :core:data:assembleDebug
./gradlew :core:network:assembleDebug
./gradlew :app:assembleDebug

# Verify no UI imports in core modules
grep -r "import.*compose" core/
grep -r "import.*ui\." core/
# Should find NO matches

# Check dependency graph
./gradlew :app:dependencies --configuration implementation
```

---

**Recommendation**: For BothBubbles, defer modularization until Phases 2-4 are complete AND you're experiencing actual build time pain. This phase adds significant complexity.
