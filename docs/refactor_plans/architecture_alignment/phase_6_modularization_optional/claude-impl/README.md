# Phase 6: Implementation Guide (Modularization - Optional)

## Status: OPTIONAL / DEFERRED

Only pursue this if:
- Build times are becoming painful (>2-3 minutes)
- Multiple contributors keep breaking architectural boundaries
- You want compiler-enforced "UI cannot touch DB directly"

## Why Modularize?

| Benefit | Explanation |
|---------|-------------|
| **Faster builds** | Only rebuild changed modules |
| **Enforced boundaries** | Compiler prevents UI → DB direct access |
| **Parallel compilation** | Multiple modules build simultaneously |
| **Clear ownership** | Each module has defined responsibility |

## Current State: Single Module

```
settings.gradle.kts:
include(":app")  // Everything in one module
```

## Target State: Multi-Module

```
settings.gradle.kts:
include(":app")
include(":core:model")
include(":core:data")
include(":core:network")
include(":feature:chat")       // Optional
include(":feature:conversations")  // Optional
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

## Step-by-Step Migration

### Step 1: Extract :core:model (Lowest Risk)

Create module with pure data classes - no dependencies on anything else.

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
    // Minimal dependencies - just Room annotations
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    // Kotlinx serialization for API models
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

### Step 2: Extract :core:data

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
    // Depends on model
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

### Step 3: Extract :core:network

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

### Step 4: Update App Module Dependencies

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
        return Room.databaseBuilder(...)
    }

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()
}
```

**core/network/di/NetworkModule.kt:**
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideRetrofit(): Retrofit { ... }

    @Provides
    @Singleton
    fun provideApi(retrofit: Retrofit): BothBubblesApi {
        return retrofit.create(BothBubblesApi::class.java)
    }
}
```

## Feature Modules (Advanced)

Feature modules are optional and add more complexity:

```kotlin
// feature/chat/build.gradle.kts
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))

    // Feature modules should NOT depend on each other
    // implementation(project(":feature:conversations"))  // ❌ BAD
}
```

**Recommendation**: Start with `:core:*` modules only. Feature modules add significant navigation complexity.

## Common Pitfalls

### 1. Circular Dependencies

```kotlin
// ❌ BAD: Circular dependency
:core:data depends on :core:network
:core:network depends on :core:data
```

**Fix**: Extract shared types to `:core:model` which both depend on.

### 2. Hilt Component Mismatch

```kotlin
// ❌ BAD: Module installed in wrong component
@Module
@InstallIn(ActivityComponent::class)  // Can't be used by Singletons!
object DataModule { ... }
```

**Fix**: Use `SingletonComponent` for shared modules.

### 3. Missing API Visibility

```kotlin
// ❌ BAD: Internal class exposed in public API
// core/data/src/.../Repository.kt
class MessageRepository internal constructor(...)

// app/ tries to use it
val repo = MessageRepository(...)  // Compile error!
```

**Fix**: Make public APIs `public` or use Hilt to inject them.

## Verification

```bash
# Build each module independently
./gradlew :core:model:assembleDebug
./gradlew :core:data:assembleDebug
./gradlew :core:network:assembleDebug
./gradlew :app:assembleDebug

# Verify no UI imports in core modules
grep -r "import.*compose" core/
grep -r "import.*ui\." core/
# Should find no matches
```

## Exit Criteria

- [ ] At least `:core:model` extracted
- [ ] Optionally `:core:data` and `:core:network` extracted
- [ ] Build times improved (or at least not worse)
- [ ] Architectural boundaries are compiler-enforced
- [ ] All tests pass

## When NOT to Do This

- Solo developer with small codebase
- Build times are acceptable (<2 minutes)
- Phases 2-4 not yet complete
- Team doesn't have multi-module experience

**Recommendation**: For BothBubbles, defer modularization until Phases 2-4 are complete and you're experiencing actual build time pain.
