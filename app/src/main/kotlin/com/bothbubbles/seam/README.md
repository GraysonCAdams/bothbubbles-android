# Seam Architecture

Seam is the modular messaging platform architecture that enables BothBubbles to support multiple messaging services.

## Terminology

| User-Facing | Code | Description |
|-------------|------|-------------|
| **Stitch** | `Stitch` interface | Platform integration (SMS, iMessage via BlueBubbles) |
| **Hem** | `Feature` interface | Cross-platform UX enhancement (Reels feed) |

In code, we use "Feature" because it's a more common programming term. User-facing UI uses "Hem" for brand consistency.

## Key Constraints

1. **At least one Stitch required** - `StitchRegistry` enforces minimum 1 enabled Stitch
2. **Features work across all Stitches** - Features process messages regardless of source
3. **Wrapper pattern** - Stitches wrap existing services without replacing them

## Folder Structure

```
seam/
├── stitches/                     # Platform integrations
│   ├── Stitch.kt                 # Core interface
│   ├── StitchConnectionState.kt  # Connection states
│   ├── StitchCapabilities.kt     # Capability declarations
│   ├── StitchRegistry.kt         # Collects via @IntoSet
│   ├── StitchRouter.kt           # Message routing
│   ├── _template/                # Template for new Stitches
│   │   └── README.md
│   ├── sms/
│   │   └── SmsStitch.kt          # Android SMS/MMS
│   └── bluebubbles/
│       └── BlueBubblesStitch.kt  # iMessage via BlueBubbles server
│
└── hems/                         # Cross-platform features
    ├── Feature.kt                # Core interface
    ├── FeatureRegistry.kt        # Collects via @IntoSet
    ├── _template/                # Template for new Features
    │   └── README.md
    └── reels/
        └── ReelsFeature.kt       # TikTok-style video feed
```

## Creating a New Stitch

1. Create folder: `seam/stitches/your_stitch/`
2. Implement `Stitch` interface (see template: `stitches/_template/README.md`)
3. Add `@Binds @IntoSet` binding in `di/StitchModule.kt`
4. Wrap existing services - don't replace them

See `sms/SmsStitch.kt` for a simple example.

## Creating a New Feature (Hem)

1. Create folder: `seam/hems/your_feature/`
2. Implement `Feature` interface (see template: `hems/_template/README.md`)
3. Add `@Binds @IntoSet` binding in `di/FeatureModule.kt`
4. Add feature flag to `FeaturePreferences`

See `reels/ReelsFeature.kt` for example.

## Stitch Interface

```kotlin
interface Stitch {
    val id: String                                  // Unique identifier (e.g., "sms", "bluebubbles")
    val displayName: String                         // User-facing name (e.g., "SMS/MMS")
    val iconResId: Int                             // Icon resource ID
    val chatGuidPrefix: String?                    // GUID prefix (e.g., "sms;-;") or null

    val connectionState: StateFlow<StitchConnectionState>  // Connection status
    val isEnabled: StateFlow<Boolean>              // Whether Stitch is enabled
    val capabilities: StitchCapabilities           // Platform capabilities

    suspend fun initialize()                       // Called on app startup
    suspend fun teardown()                         // Called on shutdown

    val settingsRoute: String?                     // Settings screen route or null
}
```

## Feature Interface

```kotlin
interface Feature {
    val id: String                           // Unique identifier (e.g., "reels")
    val displayName: String                  // User-facing name (e.g., "Reels Feed")
    val description: String                  // What the feature does
    val featureFlagKey: String              // Preference key for enable/disable

    val isEnabled: StateFlow<Boolean>       // Whether feature is enabled
    val settingsRoute: String?              // Settings screen route or null

    suspend fun onEnable()                  // Called when feature is enabled
    suspend fun onDisable()                 // Called when feature is disabled
}
```

## Stitch Capabilities

Each Stitch declares its capabilities via `StitchCapabilities`:

| Capability | SMS | BlueBubbles |
|------------|-----|-------------|
| Reactions | ❌ | ✅ |
| Typing indicators | ❌ | ✅ |
| Read receipts | ❌ | ✅ |
| Delivery receipts | ✅ | ✅ |
| Message editing | ❌ | ✅ |
| Message unsend | ❌ | ✅ |
| Effects | ❌ | ✅ |
| Group management | ❌ | ✅ |

UI components use capabilities to check before rendering features:

```kotlin
val capabilities = stitch.capabilities
if (capabilities.supportsReactions) {
    // Show reaction button
}
```

## Connection States

Stitches can be in one of these connection states:

```kotlin
sealed interface StitchConnectionState {
    data object NotConfigured      // Not set up yet
    data object Connecting          // Attempting connection
    data object Connected           // Fully operational
    data object Disconnected        // Connection lost
    data class Error(val message: String)  // Error state
}
```

## Database Integration

Messages have `stitch_id` column identifying their source:
- `"sms"` - Local SMS/MMS
- `"bluebubbles"` - iMessage via BlueBubbles

BlueBubbles-specific tables use `bb_` prefix:
- `bb_sync_range` - Sync ranges per chat
- `bb_imessage_cache` - iMessage availability cache

## Dependency Injection

Stitches and Features are collected via Dagger Multibindings:

### StitchModule.kt
```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class StitchModule {
    @Binds
    @IntoSet
    abstract fun bindSmsStitch(impl: SmsStitch): Stitch

    @Binds
    @IntoSet
    abstract fun bindBlueBubblesStitch(impl: BlueBubblesStitch): Stitch
}
```

### FeatureModule.kt
```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class FeatureModule {
    @Binds
    @IntoSet
    abstract fun bindReelsFeature(impl: ReelsFeature): Feature
}
```

The `@IntoSet` annotation tells Dagger to collect all implementations into a `Set<Stitch>` and `Set<Feature>`, which are then injected into the registries.

## Architecture Philosophy

### Why Wrapper Pattern?

Stitches **wrap** existing services instead of replacing them:

**Bad** (Tight coupling):
```kotlin
// Don't do this - Stitch directly implements sending logic
class SmsStitch {
    fun sendMessage(text: String) {
        // Direct SMS logic here
    }
}
```

**Good** (Wrapper pattern):
```kotlin
// Do this - Stitch wraps existing services
class SmsStitch @Inject constructor(
    private val smsSendService: SmsSendService  // Existing service
) : Stitch {
    // Stitch just coordinates, doesn't replace
}
```

This allows:
- Services to work independently of Seam
- Gradual migration without breaking changes
- Testability of services without Stitch complexity

### Why Separate Stitches and Features?

**Stitches** = Platform-specific integrations
- Each platform has unique requirements (SMS needs default app, BlueBubbles needs server)
- Connection state varies by platform
- Capabilities differ significantly

**Features** = Cross-platform enhancements
- Work with ALL platforms (Reels processes both SMS and iMessage links)
- Enable/disable independently of Stitches
- Share logic across platforms

Separation allows mixing and matching: enable SMS + BlueBubbles Stitches with Reels Feature.

## Testing

Mock Stitches for testing:

```kotlin
class FakeStitch(
    override val id: String = "fake",
    override val displayName: String = "Fake",
    override val connectionState: StateFlow<StitchConnectionState> = MutableStateFlow(StitchConnectionState.Connected)
) : Stitch {
    // Minimal implementation for tests
}
```

Mock Features similarly:

```kotlin
class FakeFeature(
    override val id: String = "fake",
    override val isEnabled: StateFlow<Boolean> = MutableStateFlow(true)
) : Feature {
    // Minimal implementation for tests
}
```

## Migration Guide

When adding Seam support to existing code:

1. **Don't refactor existing services** - wrap them instead
2. **Add Stitch gradually** - start with read-only (display capabilities)
3. **Move write logic later** - sending, receiving can migrate in stages
4. **Test at each stage** - ensure existing functionality still works

See git history for examples of gradual migration approach.
