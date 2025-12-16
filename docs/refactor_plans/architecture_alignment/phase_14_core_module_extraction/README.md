# Phase 14 — Core Module Extraction

> **Status**: Planned
> **Prerequisite**: Phase 6 (`:core:model` already extracted), Phase 11 (architecture stable)

## Layman's Explanation

Phase 6 extracted shared entities into `:core:model`. Now we take that further by extracting the data layer (`:core:data`), networking (`:core:network`), and shared UI components (`:core:design`) into their own modules.

This reduces build times, enforces clearer boundaries, and makes it easier to share code across feature modules.

## Connection to Shared Vision

Modularization reinforces our dependency boundaries. When data layer code is in its own module, UI code physically cannot import it incorrectly — the compiler enforces the architecture.

## Goals

1. **`:core:network`**: Retrofit, OkHttp, Moshi, Socket.IO, API interfaces
2. **`:core:data`**: Room database, DAOs, repositories
3. **`:core:design`**: Shared Compose components, theme, design tokens

## Current Module Structure

```
bothbubbles-app/
├── app/                    # Main application module
├── core/
│   └── model/              # ✅ Extracted in Phase 6
└── (proposed modules)
    ├── core/network/       # API layer
    ├── core/data/          # Database + repositories
    └── core/design/        # Shared UI components
```

## Target Module Structure

```
bothbubbles-app/
├── app/                    # Thin shell: Application, Activities, DI wiring
├── core/
│   ├── model/              # ✅ Already exists
│   ├── network/            # Retrofit, OkHttp, BothBubblesApi, DTOs
│   ├── data/               # Room, DAOs, repositories
│   └── design/             # Theme, shared components, design tokens
└── feature/                # (Phase 15)
    ├── chat/
    ├── conversations/
    └── settings/
```

## Module Dependency Graph

```
                    ┌─────────────┐
                    │    :app     │
                    └──────┬──────┘
                           │ implements all DI
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
    ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
    │:feature:chat│ │:feature:conv│ │:feature:set │
    └──────┬──────┘ └──────┬──────┘ └──────┬──────┘
           │               │               │
           └───────────────┼───────────────┘
                           │ all features depend on core
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
    ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
    │:core:design │ │ :core:data  │ │:core:network│
    └──────┬──────┘ └──────┬──────┘ └──────┬──────┘
           │               │               │
           └───────────────┼───────────────┘
                           ▼
                    ┌─────────────┐
                    │ :core:model │
                    └─────────────┘
```

## Implementation Steps

### Step 1: Create `:core:network` Module

**Contents to move:**
```
app/src/main/kotlin/com/bothbubbles/data/remote/
├── api/
│   ├── BothBubblesApi.kt
│   ├── AuthInterceptor.kt
│   ├── dto/                    # All DTOs
│   └── ServerCapabilities.kt
└── socket/
    └── (Socket.IO related if not service-bound)
```

**build.gradle.kts:**
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.bothbubbles.core.network"
    // ...
}

dependencies {
    implementation(project(":core:model"))

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.0")

    // Socket.IO
    implementation("io.socket:socket.io-client:2.1.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48")
    ksp("com.google.dagger:hilt-compiler:2.48")
}
```

**DI Module:**
```kotlin
// core/network/src/main/kotlin/.../di/NetworkModule.kt
@Module
@InstallIn(SingletonComponent::class)
object CoreNetworkModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(...): OkHttpClient

    @Provides
    @Singleton
    fun provideRetrofit(...): Retrofit

    @Provides
    @Singleton
    fun provideBothBubblesApi(...): BothBubblesApi
}
```

### Step 2: Create `:core:data` Module

**Contents to move:**
```
app/src/main/kotlin/com/bothbubbles/data/
├── local/
│   ├── db/
│   │   ├── BothBubblesDatabase.kt
│   │   ├── DatabaseMigrations.kt
│   │   ├── dao/                # All 24 DAOs
│   │   └── entity/             # Keep aliases in app, real entities in :core:model
│   └── prefs/
│       └── PreferencesManager.kt
└── repository/
    ├── MessageRepository.kt
    ├── ChatRepository.kt
    ├── AttachmentRepository.kt
    └── ...
```

**build.gradle.kts:**
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.bothbubbles.core.data"
    // Room schema export
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48")
    ksp("com.google.dagger:hilt-compiler:2.48")
}
```

### Step 3: Create `:core:design` Module

**Contents to move:**
```
app/src/main/kotlin/com/bothbubbles/ui/
├── theme/
│   ├── Theme.kt
│   ├── Color.kt
│   ├── Typography.kt
│   └── Shape.kt
└── components/
    ├── common/
    │   ├── Avatar.kt
    │   ├── Shimmer.kt
    │   ├── ErrorView.kt
    │   └── LinkPreview.kt
    ├── message/
    │   ├── MessageBubble.kt
    │   ├── ReactionChip.kt
    │   └── TypingIndicator.kt
    └── dialogs/
        ├── ConfirmationDialog.kt
        └── InfoDialog.kt
```

**build.gradle.kts:**
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bothbubbles.core.design"
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:model"))

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Coil
    implementation("io.coil-kt:coil-compose:2.5.0")
}
```

### Step 4: Update App Module

After extraction, the app module becomes thin:

```kotlin
// app/build.gradle.kts
dependencies {
    // Core modules
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:design"))

    // Feature modules (Phase 15)
    // implementation(project(":feature:chat"))
    // implementation(project(":feature:conversations"))
    // implementation(project(":feature:settings"))

    // Application-level only
    implementation("com.google.dagger:hilt-android:2.48")
    ksp("com.google.dagger:hilt-compiler:2.48")
}
```

### Step 5: Handle Circular Dependencies

**Problem**: Repositories often need both network and database.

**Solution**: Repositories live in `:core:data` which depends on `:core:network`.

```
:core:data depends on :core:network ✓
:core:network does NOT depend on :core:data ✓
```

**Problem**: Some services need repositories.

**Solution**: Services that need repositories stay in `:app` or move to feature modules.

### Step 6: Migrate Entity Aliases

Phase 6 created entity aliases in the app module. These can be:
1. Kept as-is (simplest)
2. Removed and imports updated to use `:core:model` directly
3. Deprecated with migration path

**Recommendation**: Keep aliases during Phase 14, remove in Phase 15 when features are extracted.

## Exit Criteria

- [ ] `:core:network` module created and builds
- [ ] `:core:data` module created and builds
- [ ] `:core:design` module created and builds
- [ ] All modules have proper DI setup (Hilt modules)
- [ ] App module depends on all core modules
- [ ] No circular dependencies
- [ ] Room schema export still works
- [ ] App builds and runs correctly
- [ ] No regressions in functionality

## Inventory

| Task | Effort | Owner | Status |
|------|--------|-------|--------|
| Create `:core:network` structure | 2h | _Unassigned_ | ☐ |
| Move API code to `:core:network` | 4h | _Unassigned_ | ☐ |
| Create CoreNetworkModule | 2h | _Unassigned_ | ☐ |
| Create `:core:data` structure | 2h | _Unassigned_ | ☐ |
| Move database code to `:core:data` | 6h | _Unassigned_ | ☐ |
| Move repositories to `:core:data` | 4h | _Unassigned_ | ☐ |
| Create CoreDataModule | 2h | _Unassigned_ | ☐ |
| Create `:core:design` structure | 2h | _Unassigned_ | ☐ |
| Move theme to `:core:design` | 2h | _Unassigned_ | ☐ |
| Move shared components to `:core:design` | 4h | _Unassigned_ | ☐ |
| Update app module dependencies | 2h | _Unassigned_ | ☐ |
| Fix import statements | 4h | _Unassigned_ | ☐ |
| Verify Room schema export | 1h | _Unassigned_ | ☐ |
| Test full app functionality | 4h | _Unassigned_ | ☐ |

**Total Estimated Effort**: 40-45 hours

## Risks

- **High**: Large refactor touching many files — do incrementally
- **Medium**: Room schema export path may need adjustment
- **Medium**: Hilt DI wiring may need careful ordering
- **Low**: Build time improvement may not be immediate (depends on incremental build)

## Dependencies

- Phase 6 must be complete (`:core:model` extracted)
- Phase 11 recommended (architecture stable before major restructure)
- No dependency on CI/CD

## Build Time Impact

Expected improvements after modularization:

| Scenario | Before | After (Est.) |
|----------|--------|--------------|
| Clean build | ~3 min | ~3 min (similar) |
| Incremental (UI change) | ~90 sec | ~30 sec |
| Incremental (data change) | ~90 sec | ~45 sec |
| Unit tests | ~60 sec | ~20 sec (per module) |

## Next Steps

Phase 15 (Feature Module Extraction) creates feature modules that depend on these core modules. The foundation must be solid before proceeding.
