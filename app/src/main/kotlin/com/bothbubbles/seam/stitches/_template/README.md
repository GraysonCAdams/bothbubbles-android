# Stitch Template

Use this template when creating a new Stitch.

## Implementation Checklist

- [ ] Create `YourStitch.kt` implementing `Stitch` interface
- [ ] Define `StitchCapabilities` for your platform
- [ ] Wrap existing services (don't replace)
- [ ] Add `@Binds @IntoSet` in `di/StitchModule.kt`
- [ ] Test connection state transitions
- [ ] Document settings route if any

## Required Properties

```kotlin
@Singleton
class YourStitch @Inject constructor(
    // Inject wrapped services here
    private val yourExistingService: YourService
) : Stitch {

    companion object {
        const val ID = "your_id"              // Unique identifier
        const val DISPLAY_NAME = "Your Name"  // User-facing name
        const val CHAT_GUID_PREFIX = "prefix;-;"  // Or null if not applicable
        const val SETTINGS_ROUTE = "settings/your_stitch"  // Or null if no settings
    }

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME
    override val iconResId: Int = R.drawable.ic_your_icon
    override val chatGuidPrefix: String? = CHAT_GUID_PREFIX

    override val capabilities: StitchCapabilities = StitchCapabilities(
        supportsReactions = true,  // Set based on your platform
        // ... configure all capabilities
    )

    private val _connectionState = MutableStateFlow<StitchConnectionState>(
        StitchConnectionState.NotConfigured
    )
    override val connectionState: StateFlow<StitchConnectionState> =
        _connectionState.asStateFlow()

    private val _isEnabled = MutableStateFlow(false)
    override val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    @Deprecated("Use settingsContribution instead")
    override val settingsRoute: String? = SETTINGS_ROUTE

    // Settings contribution - defines how this Stitch appears in Settings
    override val settingsContribution: SettingsContribution
        get() = SettingsContribution(
            dedicatedMenuItem = DedicatedSettingsMenuItem(
                id = ID,
                title = DISPLAY_NAME,
                subtitle = "Configure your platform",
                icon = Icons.Default.Settings,  // Your icon
                iconTint = SettingsIconColors.Connectivity,
                section = SettingsSection.CONNECTIVITY,
                route = SETTINGS_ROUTE,
                enabled = true
            )
        )

    override suspend fun initialize() {
        // Called on app startup
        // Set up listeners, check configuration, update connection state
    }

    override suspend fun teardown() {
        // Called on shutdown
        // Clean up resources, disconnect listeners
    }
}
```

## StitchModule Binding

Add this to `di/StitchModule.kt`:

```kotlin
@Binds
@IntoSet
abstract fun bindYourStitch(impl: YourStitch): Stitch
```

## Capability Guidelines

Only declare capabilities your platform truly supports. Don't fake capabilities.

### Common Capabilities

```kotlin
StitchCapabilities(
    // Message features
    supportsReactions = false,              // Can react with emojis/tapbacks
    reactionTypes = emptySet(),             // Set of supported reactions
    supportsTypingIndicators = false,       // Shows "..." when typing
    supportsReadReceipts = false,           // Sends/receives read receipts
    supportsDeliveryReceipts = false,       // Sends/receives delivery receipts
    supportsMessageEditing = false,         // Can edit sent messages
    supportsMessageUnsend = false,          // Can delete/unsend messages
    supportsMessageEffects = false,         // Supports effects (slam, loud, etc.)
    supportsReplies = false,                // Thread/reply support

    // Group features
    supportsGroupChats = true,              // Supports multi-person chats
    canModifyGroups = false,                // Can add/remove participants

    // Attachment features
    maxAttachmentSize = null,               // Max size in bytes, or null for unlimited
    supportedMimeTypes = null,              // Set of MIME types, or null for all
    supportsMultipleAttachments = true,     // Can send multiple at once

    // Technical features
    supportsRealTimePush = false,           // Has push notifications
    requiresDefaultSmsApp = false           // Needs default SMS app status
)
```

## Connection State Management

Update connection state based on your platform's status:

```kotlin
private fun updateConnectionState() {
    val newState = when {
        !isConfigured() -> StitchConnectionState.NotConfigured
        isConnecting() -> StitchConnectionState.Connecting
        isConnected() -> StitchConnectionState.Connected
        hasError() -> StitchConnectionState.Error("Error message")
        else -> StitchConnectionState.Disconnected
    }
    _connectionState.value = newState
    _isEnabled.value = (newState is StitchConnectionState.Connected)
}
```

## Wrapper Pattern Example

**Bad** - Stitch replaces existing logic:
```kotlin
class YourStitch : Stitch {
    override suspend fun sendMessage(text: String) {
        // Directly implements sending - BAD!
        // Now you have duplicate logic
    }
}
```

**Good** - Stitch wraps existing service:
```kotlin
class YourStitch @Inject constructor(
    private val messageSender: MessageSendingService  // Existing service
) : Stitch {
    // Stitch just provides metadata and coordinates
    // Actual sending still happens in MessageSendingService
}
```

## Real-World Examples

See existing Stitches for reference:
- `sms/SmsStitch.kt` - Simple example, wraps SMS permissions
- `bluebubbles/BlueBubblesStitch.kt` - Complex example, wraps socket and API

## Testing

Create a fake for testing:

```kotlin
class FakeYourStitch(
    override val connectionState: StateFlow<StitchConnectionState> =
        MutableStateFlow(StitchConnectionState.Connected)
) : Stitch {
    override val id = "fake_your"
    override val displayName = "Fake Your"
    override val iconResId = 0
    override val chatGuidPrefix = null
    override val capabilities = StitchCapabilities()
    override val isEnabled = MutableStateFlow(true)
    override val settingsRoute = null

    override suspend fun initialize() {}
    override suspend fun teardown() {}
}
```
