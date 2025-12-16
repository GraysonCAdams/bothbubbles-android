# Phase 15 — Feature Module Extraction

> **Status**: Structure Complete (🔄 Incremental Migration Remaining)
> **Prerequisite**: Phase 14 (Core Module Extraction complete) ✅

## Implementation Progress

| Component | Status | Notes |
|-----------|--------|-------|
| `:navigation` module | ✅ Complete | Routes.kt with all type-safe routes |
| `:core:data` module | ✅ Complete | SettingsProvider, ServerConnectionProvider, DeveloperLogger interfaces |
| `:feature:settings` | ✅ Structure | Navigation stub, dependencies wired |
| `:feature:setup` | ✅ Structure | Navigation stub, dependencies wired |
| `:feature:conversations` | ✅ Structure | Navigation stub, dependencies wired |
| `:feature:chat` | ✅ Structure | Navigation stub, dependencies wired |
| Actual code migration | ⏳ Incremental | See migration strategy below |

### What's Complete

1. **Module structure**: All feature modules created with proper Gradle configuration
2. **Navigation contracts**: Type-safe routes in `:navigation` module
3. **Dependency wiring**: All modules have correct dependencies (`:core:model`, `:core:network`, `:core:data`)
4. **Interface layer**: `:core:data` provides interfaces for settings/connection that feature modules can depend on
5. **SettingsProvider binding**: `SettingsDataStore` implements `SettingsProvider` interface
6. **Hilt wiring**: `ServiceModule` binds `SettingsProvider` for DI
7. **Build verification**: All modules compile successfully

### What Remains (Incremental)

The actual migration of screens and ViewModels to feature modules can happen incrementally:

1. ✅ **Implement SettingsProvider in app module**: SettingsDataStore implements SettingsProvider
2. ⏳ **Migrate one settings screen**: Move AboutScreen + AboutViewModel to `:feature:settings`
   - Requires moving `SocketEvent` types to `:core:data` or creating a simpler interface
3. ⏳ **Wire navigation**: Update AppNavHost to use feature module navigation extensions
4. ⏳ **Repeat for other screens**: Settings → Setup → Conversations → Chat

### Incremental Migration Example

To migrate AboutScreen to `:feature:settings`:

```kotlin
// 1. Create ServerVersionProvider interface in :core:data
interface ServerVersionProvider {
    val serverVersion: StateFlow<String?>
}

// 2. Update AboutViewModel to use interfaces
@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverVersionProvider: ServerVersionProvider,  // Instead of SocketConnection
    private val settingsProvider: SettingsProvider,            // Instead of SettingsDataStore
    private val developerLogger: DeveloperLogger               // Instead of DeveloperEventLog
) : ViewModel()

// 3. Add binding in app module ServiceModule
@Binds
abstract fun bindServerVersionProvider(socketService: SocketService): ServerVersionProvider

// 4. Move AboutScreen.kt and AboutViewModel.kt to :feature:settings

// 5. Update SettingsNavigation.kt
composable<Route.About> {
    AboutScreen(onNavigateBack = { navController.navigateUp() })
}
```

## Layman's Explanation

With core modules in place (`:core:model`, `:core:network`, `:core:data`, `:core:design`), we can now extract features into their own modules. Each feature becomes self-contained: its screens, ViewModels, delegates, and feature-specific components live together.

This enables parallel development, faster builds, and clearer ownership boundaries.

## Connection to Shared Vision

Feature modules are the culmination of our modularization strategy. Each module enforces encapsulation — a feature's internal implementation details can't leak to other features.

## Goals

1. **`:feature:chat`**: Chat screen, ChatViewModel, all chat delegates
2. **`:feature:conversations`**: Conversation list, ConversationsViewModel, delegates
3. **`:feature:settings`**: All settings screens and ViewModels
4. **`:feature:setup`**: Setup wizard screens and SetupViewModel
5. **`:navigation`**: Type-safe route definitions, deeplink contracts

## Target Module Structure

```
bothbubbles-app/
├── app/                        # Application shell only
│   ├── BothBubblesApp.kt
│   ├── MainActivity.kt
│   └── di/                     # Root DI wiring
├── core/
│   ├── model/                  # ✅ Phase 6
│   ├── network/                # ✅ Phase 14
│   ├── data/                   # ✅ Phase 14
│   └── design/                 # ✅ Phase 14
├── feature/
│   ├── chat/
│   │   ├── ChatScreen.kt
│   │   ├── ChatViewModel.kt
│   │   ├── delegates/
│   │   ├── components/
│   │   └── paging/
│   ├── conversations/
│   │   ├── ConversationsScreen.kt
│   │   ├── ConversationsViewModel.kt
│   │   ├── delegates/
│   │   └── components/
│   ├── settings/
│   │   ├── SettingsScreen.kt
│   │   └── screens/            # Sub-settings screens
│   └── setup/
│       ├── SetupScreen.kt
│       ├── SetupViewModel.kt
│       └── delegates/
└── navigation/                 # Route contracts
    ├── Routes.kt
    └── DeeplinkContracts.kt
```

## Module Dependency Rules

```
:app
  ↓ depends on all features (implementation)
  ↓ depends on :navigation (for route wiring)

:feature:chat
  ↓ depends on :core:* (api)
  ↓ depends on :navigation (api) for route contracts

:feature:conversations
  ↓ depends on :core:* (api)
  ↓ depends on :navigation (api)
  ✗ does NOT depend on :feature:chat

:navigation
  ↓ depends on :core:model only (for route parameters)
  ✗ does NOT depend on any feature modules
```

**Key Rule**: Feature modules NEVER depend on each other. All cross-feature navigation goes through `:navigation` contracts.

## Implementation Steps

### Step 1: Create `:navigation` Module (First!)

The navigation module defines route contracts that features implement:

```kotlin
// navigation/src/main/kotlin/.../Routes.kt
sealed interface Route {
    @Serializable
    data object Conversations : Route

    @Serializable
    data class Chat(val chatGuid: String) : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data class SettingsDetail(val screen: SettingsScreen) : Route

    @Serializable
    data object Setup : Route
}

// navigation/src/main/kotlin/.../Navigator.kt
interface Navigator {
    fun navigateTo(route: Route)
    fun navigateBack()
    fun navigateToChat(chatGuid: String)
}
```

**build.gradle.kts:**
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.bothbubbles.navigation"
}

dependencies {
    implementation(project(":core:model"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
}
```

### Step 2: Create `:feature:chat` Module

**Contents to move:**
```
app/src/main/kotlin/com/bothbubbles/ui/chat/
├── ChatScreen.kt
├── ChatViewModel.kt
├── delegates/                  # All 14 delegates
│   ├── ChatSendDelegate.kt
│   ├── ChatMessageListDelegate.kt
│   └── ...
├── components/
│   ├── ChatComposer.kt
│   ├── ChatMessageList.kt
│   └── ...
└── paging/
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
    namespace = "com.bothbubbles.feature.chat"
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Core modules
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:design"))
    implementation(project(":navigation"))

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48")
    ksp("com.google.dagger:hilt-compiler:2.48")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
}
```

### Step 3: Create `:feature:conversations` Module

**Contents to move:**
```
app/src/main/kotlin/com/bothbubbles/ui/conversations/
├── ConversationsScreen.kt
├── ConversationsViewModel.kt
├── delegates/
│   ├── ConversationLoadingDelegate.kt
│   ├── ConversationActionsDelegate.kt
│   └── ...
└── components/
```

### Step 4: Create `:feature:settings` Module

**Contents to move:**
```
app/src/main/kotlin/com/bothbubbles/ui/settings/
├── SettingsScreen.kt
├── server/
├── notifications/
├── sms/
├── developer/
└── ...
```

### Step 5: Create `:feature:setup` Module

**Contents to move:**
```
app/src/main/kotlin/com/bothbubbles/ui/setup/
├── SetupScreen.kt
├── SetupViewModel.kt
└── delegates/
```

### Step 6: Update `:app` Module for Navigation Wiring

```kotlin
// app/src/main/kotlin/.../navigation/AppNavHost.kt
@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: Route = Route.Conversations
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Each feature provides its own composable destinations
        chatNavigation(navController)
        conversationsNavigation(navController)
        settingsNavigation(navController)
        setupNavigation(navController)
    }
}

// Each feature module exposes a NavGraphBuilder extension
// feature/chat/src/.../ChatNavigation.kt
fun NavGraphBuilder.chatNavigation(navController: NavController) {
    composable<Route.Chat> { backStackEntry ->
        val route = backStackEntry.toRoute<Route.Chat>()
        ChatScreen(
            chatGuid = route.chatGuid,
            onNavigateBack = { navController.navigateUp() }
        )
    }
}
```

### Step 7: Handle Cross-Feature Communication

**Option A: Navigation-based (Preferred)**
```kotlin
// From ConversationsScreen, navigate to chat
onConversationClick = { chatGuid ->
    navigator.navigateTo(Route.Chat(chatGuid))
}
```

**Option B: Shared ViewModel (Rare cases)**
```kotlin
// For truly shared state, use a SharedViewModel scoped to Activity
@HiltViewModel
class SharedMessageViewModel @Inject constructor(...) : ViewModel()
```

### Step 8: Move Tests to Feature Modules

Tests move with their corresponding code:

```
feature/chat/src/test/
├── delegates/
│   ├── ChatSendDelegateTest.kt
│   └── ...
└── ChatViewModelTest.kt

feature/chat/src/androidTest/
└── ChatScreenTest.kt
```

## Exit Criteria

- [x] `:navigation` module created with route contracts
- [x] `:feature:chat` module created and builds
- [x] `:feature:conversations` module created and builds
- [x] `:feature:settings` module created and builds
- [x] `:feature:setup` module created and builds
- [x] No feature module depends on another feature module
- [x] All navigation goes through `:navigation` contracts (Route types defined)
- [ ] ⏳ Tests moved to appropriate modules (incremental)
- [ ] ⏳ App module is thin (~500 LOC max) (incremental)
- [x] App builds and runs correctly
- [x] All features accessible and functional

## Inventory

| Task | Effort | Owner | Status |
|------|--------|-------|--------|
| Create `:navigation` module | 4h | _Unassigned_ | ☐ |
| Define all route contracts | 2h | _Unassigned_ | ☐ |
| Create `:feature:chat` structure | 2h | _Unassigned_ | ☐ |
| Move chat code | 6h | _Unassigned_ | ☐ |
| Move chat delegates (14) | 4h | _Unassigned_ | ☐ |
| Create `:feature:conversations` structure | 2h | _Unassigned_ | ☐ |
| Move conversations code | 4h | _Unassigned_ | ☐ |
| Move conversation delegates (5) | 2h | _Unassigned_ | ☐ |
| Create `:feature:settings` structure | 2h | _Unassigned_ | ☐ |
| Move settings code (~15 screens) | 6h | _Unassigned_ | ☐ |
| Create `:feature:setup` structure | 2h | _Unassigned_ | ☐ |
| Move setup code | 4h | _Unassigned_ | ☐ |
| Update AppNavHost | 4h | _Unassigned_ | ☐ |
| Move tests to modules | 4h | _Unassigned_ | ☐ |
| Fix imports across codebase | 6h | _Unassigned_ | ☐ |
| Remove entity aliases | 2h | _Unassigned_ | ☐ |
| Verify all features work | 6h | _Unassigned_ | ☐ |

**Total Estimated Effort**: 60-70 hours

## Risks

- **High**: Largest refactor — break into incremental PRs per feature
- **Medium**: Navigation wiring can be tricky with type-safe routes
- **Medium**: Hilt component scoping may need adjustment
- **Low**: Test relocation is mechanical

## Dependencies

- Phase 14 must be complete (core modules exist)
- Phase 11 recommended (delegates should be clean before moving)

## Incremental Migration Strategy

Don't do all features at once. Recommended order:

1. **`:navigation`** — Must be first (other modules depend on it)
2. **`:feature:settings`** — Simplest, least coupled
3. **`:feature:setup`** — Self-contained wizard
4. **`:feature:conversations`** — Core feature, moderate complexity
5. **`:feature:chat`** — Most complex, do last

Each feature can be a separate PR. App remains functional after each step.

## Build Time Impact (Expected)

| Scenario | Before | After (Est.) |
|----------|--------|--------------|
| Clean build | ~3 min | ~4 min (more modules) |
| Change in :feature:chat | ~90 sec | ~15 sec |
| Change in :feature:settings | ~90 sec | ~10 sec |
| Change in :core:design | ~90 sec | ~60 sec |
| Unit tests (chat only) | ~60 sec | ~10 sec |

## Next Steps

After Phase 15, the app is fully modularized. Phase 16 (Anti-pattern Cleanup) can proceed with any remaining technical debt, followed by Phase 17 (CI/CD) to automate quality gates.
