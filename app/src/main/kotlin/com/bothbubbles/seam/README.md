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
├── settings/                     # Settings integration
│   ├── SettingsContribution.kt   # Contribution model
│   └── SettingsContributionProvider.kt  # Collects from Stitches/Features
│
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
    ├── reels/
    │   └── ReelsFeature.kt       # TikTok-style video feed
    ├── life360/
    │   └── Life360Feature.kt     # Life360 location integration
    └── eta/
        └── EtaFeature.kt         # ETA sharing feature
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
    val chatGuidPrefix: String?                    // Primary GUID prefix (e.g., "sms;-;")

    val connectionState: StateFlow<StitchConnectionState>  // Connection status
    val isEnabled: StateFlow<Boolean>              // Whether Stitch is enabled
    val capabilities: StitchCapabilities           // Platform capabilities

    fun matchesChatGuid(chatGuid: String): Boolean // Check if this Stitch handles a chat
    suspend fun initialize()                       // Called on app startup
    suspend fun teardown()                         // Called on shutdown

    val settingsRoute: String?                     // Settings screen route (deprecated)
    val settingsContribution: SettingsContribution // Settings page contribution
}
```

### Multi-Prefix Matching

The `matchesChatGuid` method handles Stitches that need multiple prefixes:

```kotlin
// Default implementation uses chatGuidPrefix
fun matchesChatGuid(chatGuid: String): Boolean {
    return chatGuidPrefix?.let { chatGuid.startsWith(it) } ?: false
}

// SmsStitch overrides to handle both SMS and MMS prefixes
override fun matchesChatGuid(chatGuid: String): Boolean {
    return chatGuid.startsWith("sms;-;") || chatGuid.startsWith("mms;-;")
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
    val settingsRoute: String?              // Settings screen route (deprecated)
    val settingsContribution: SettingsContribution  // Settings page contribution

    suspend fun onEnable()                  // Called when feature is enabled
    suspend fun onDisable()                 // Called when feature is disabled
}
```

## Settings Integration

Stitches and Features can contribute to the Settings screen via `SettingsContribution`:

```kotlin
data class SettingsContribution(
    val dedicatedMenuItem: DedicatedSettingsMenuItem? = null,  // Up to 1 dedicated menu item
    val additionalItems: Map<SettingsSection, List<SettingsItem>> = emptyMap()  // Items in existing sections
)
```

### Dedicated Menu Item

Each Stitch/Feature can provide up to **one** dedicated settings menu item that appears in the main settings screen:

```kotlin
override val settingsContribution: SettingsContribution
    get() = SettingsContribution(
        dedicatedMenuItem = DedicatedSettingsMenuItem(
            id = ID,
            title = "Life360",
            subtitle = "Show friends and family locations",
            icon = Icons.Outlined.LocationOn,
            iconTint = SettingsIconColors.Location,
            section = SettingsSection.SHARING,  // Which section to appear in
            route = "settings/life360",          // Navigation route
            enabled = true
        )
    )
```

### Settings Sections

Items are placed in predefined sections:

| Section | Description |
|---------|-------------|
| `CONNECTIVITY` | iMessage, SMS, sync settings |
| `NOTIFICATIONS` | Alerts, sounds, vibration |
| `APPEARANCE` | Effects, swipe, haptics |
| `MESSAGING` | Templates, auto-responder, media |
| `SHARING` | ETA, Life360, calendar |
| `PRIVACY` | Spam, blocked, categorization |
| `DATA` | Storage, export |
| `ABOUT` | Version, licenses |

### SettingsContributionProvider

The `SettingsContributionProvider` collects contributions from all Stitches and Features:

```kotlin
@Singleton
class SettingsContributionProvider @Inject constructor(
    private val stitchRegistry: StitchRegistry,
    private val featureRegistry: FeatureRegistry
) {
    fun getMenuItemsForSection(section: SettingsSection): List<DedicatedSettingsMenuItem>
    fun getAdditionalItemsForSection(section: SettingsSection): List<SettingsItem>
}
```

Use it in ViewModels or Composables to dynamically render contributed settings.

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
sealed class StitchConnectionState {
    data object NotConfigured      // Not set up yet
    data object Disconnected       // Connection lost
    data object Connecting         // Attempting connection
    data object Connected          // Fully operational
    data class Error(val message: String)  // Error state

    // Helper properties
    val isConnected: Boolean       // True only for Connected state
    val isError: Boolean           // True only for Error state
    val isUsable: Boolean          // True for Connected, Disconnected, or Connecting
}
```

## StitchRouter

The `StitchRouter` provides intelligent routing based on chat GUIDs, connection state, and capabilities:

```kotlin
@Singleton
class StitchRouter @Inject constructor(
    private val registry: StitchRegistry
) {
    // Basic routing
    fun getStitchForChat(chatGuid: String): Stitch?
    fun getConnectedStitchForChat(chatGuid: String): Stitch?
    fun getConnectedStitches(): List<Stitch>

    // Capability-based routing
    fun getStitchWithCapability(requirement: (StitchCapabilities) -> Boolean): Stitch?
    fun getStitchesWithCapability(requirement: (StitchCapabilities) -> Boolean): List<Stitch>

    // Action checks (returns true if chat's stitch is connected AND supports capability)
    fun canPerformAction(chatGuid: String, capabilityCheck: (StitchCapabilities) -> Boolean): Boolean
    fun canReact(chatGuid: String): Boolean
    fun canEdit(chatGuid: String): Boolean
    fun canUnsend(chatGuid: String): Boolean
    fun canReply(chatGuid: String): Boolean
    fun canSendTypingIndicator(chatGuid: String): Boolean
    fun canSendWithEffect(chatGuid: String): Boolean

    // Attachment constraints
    fun getMaxAttachmentSize(chatGuid: String): Long?
    fun isMimeTypeSupported(chatGuid: String, mimeType: String): Boolean
}
```

### Usage Examples

```kotlin
// Check before showing reaction button
if (stitchRouter.canReact(chatGuid)) {
    ReactionButton(onClick = { /* ... */ })
}

// Check MIME type before sending attachment
if (!stitchRouter.isMimeTypeSupported(chatGuid, "image/heic")) {
    // Convert to JPEG first
}

// Find any stitch that supports typing indicators
val typingStitch = stitchRouter.getStitchWithCapability { it.supportsTypingIndicators }
```

## Contact Availability

Stitches can check whether they can reach a specific contact identifier:

```kotlin
// Types of identifiers
enum class ContactIdentifierType {
    PHONE_NUMBER,  // Standard phone number
    EMAIL,         // Email address
    SIGNAL_ID,     // Signal-specific identifier
    DISCORD_USERNAME  // Discord username
}

// Create an identifier
val phoneContact = ContactIdentifier.phone("+15551234567")
val emailContact = ContactIdentifier.email("user@icloud.com")

// Check availability
val availability = stitch.checkContactAvailability(phoneContact)
when (availability) {
    is ContactAvailability.Available -> // Can reach this contact
    is ContactAvailability.Unavailable -> // Cannot reach (with reason)
    is ContactAvailability.Unknown -> // Check failed (with fallbackHint)
    ContactAvailability.UnsupportedIdentifierType -> // Wrong identifier type
}
```

### Supported Identifier Types

| Stitch | Phone | Email | Notes |
|--------|-------|-------|-------|
| SMS | Yes | No | All phone numbers reachable when default app |
| BlueBubbles | Yes | Yes | Email always iMessage, phone needs server |

### Priority Ordering

Users can prioritize which Stitch to use when multiple can reach a contact:

```kotlin
// Default priorities: BlueBubbles (100), SMS (50)
val preferred = registry.getPreferredStitch(userPriorityOrder)

// Get stitches that can handle phone numbers, in priority order
val phoneStitches = registry.getStitchesForIdentifierType(
    ContactIdentifierType.PHONE_NUMBER,
    userPriorityOrder
)
```

### Platform Colors

Each Stitch has a default bubble color:
- **BlueBubbles (iMessage)**: `0xFF007AFF` (iOS blue)
- **SMS**: `0xFF34C759` (iOS SMS green)

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
