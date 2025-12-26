# Feature (Hem) Template

Use this template when creating a new Feature.

## Implementation Checklist

- [ ] Create `YourFeature.kt` implementing `Feature` interface
- [ ] Add preference key to `FeaturePreferences`
- [ ] Add `@Binds @IntoSet` in `di/FeatureModule.kt`
- [ ] Test enable/disable lifecycle
- [ ] Document settings route if any

## Required Properties

```kotlin
@Singleton
class YourFeature @Inject constructor(
    private val featurePreferences: FeaturePreferences,
    @ApplicationScope private val applicationScope: CoroutineScope
) : Feature {

    companion object {
        const val ID = "your_id"
        const val DISPLAY_NAME = "Your Feature Name"
        const val DESCRIPTION = "What your feature does"
        const val FEATURE_FLAG_KEY = "your_feature_enabled"
        const val SETTINGS_ROUTE = "settings/your_feature"  // Or null if no settings
    }

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME
    override val description: String = DESCRIPTION
    override val featureFlagKey: String = FEATURE_FLAG_KEY

    override val isEnabled: StateFlow<Boolean> =
        featurePreferences.yourFeatureEnabled.stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    @Deprecated("Use settingsContribution instead")
    override val settingsRoute: String? = SETTINGS_ROUTE

    // Settings contribution - defines how this Feature appears in Settings
    // Use SettingsContribution.NONE if no dedicated settings menu item is needed
    override val settingsContribution: SettingsContribution
        get() = SettingsContribution(
            dedicatedMenuItem = DedicatedSettingsMenuItem(
                id = ID,
                title = DISPLAY_NAME,
                subtitle = DESCRIPTION,
                icon = Icons.Default.Extension,  // Your icon
                iconTint = SettingsIconColors.Appearance,  // Your color
                section = SettingsSection.MESSAGING,  // Your section
                route = SETTINGS_ROUTE,
                enabled = true
            )
        )

    override suspend fun onEnable() {
        // Called when feature is enabled
        // Initialize resources, start background tasks, etc.
    }

    override suspend fun onDisable() {
        // Called when feature is disabled
        // Clean up resources, stop background tasks, etc.
    }
}
```

## Adding to FeaturePreferences

Add to `data/local/prefs/FeaturePreferences.kt`:

```kotlin
val yourFeatureEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
    prefs[PreferencesKeys.YOUR_FEATURE_ENABLED] ?: false
}

suspend fun setYourFeatureEnabled(enabled: Boolean) {
    dataStore.edit { prefs ->
        prefs[PreferencesKeys.YOUR_FEATURE_ENABLED] = enabled
    }
}

// In PreferencesKeys object:
val YOUR_FEATURE_ENABLED = booleanPreferencesKey("your_feature_enabled")
```

## FeatureModule Binding

Add this to `di/FeatureModule.kt`:

```kotlin
@Binds
@IntoSet
abstract fun bindYourFeature(impl: YourFeature): Feature
```

## Design Guidelines

### Cross-Platform Requirements

Features MUST work with ALL Stitches. If your feature is platform-specific, it belongs IN the Stitch, not as a separate Feature.

**Example of good cross-platform feature:**
```kotlin
// Reels Feature - extracts video links from ANY message source
class ReelsFeature : Feature {
    // Processes messages from SMS, iMessage, future platforms
}
```

**Example of bad platform-specific feature:**
```kotlin
// BAD - iMessage reactions are platform-specific
class ReactionsFeature : Feature {
    // Only works with iMessage - should be part of BlueBubblesStitch!
}
```

### Lifecycle Management

Use `onEnable()` and `onDisable()` for lifecycle:

```kotlin
override suspend fun onEnable() {
    // Start background processing
    scope.launch {
        // Begin monitoring for content
    }

    // Initialize caches
    cache.initialize()

    // Register listeners
    eventBus.register(listener)
}

override suspend fun onDisable() {
    // Stop background tasks
    jobs.forEach { it.cancel() }

    // Clear caches (optional)
    cache.clear()

    // Unregister listeners
    eventBus.unregister(listener)
}
```

## Real-World Example

See `reels/ReelsFeature.kt` for a complete implementation:
- Cross-platform (works with SMS and iMessage)
- Preference-driven enable/disable
- No settings route (uses main preferences)
- Delegates actual work to other components

## Feature vs ViewModel Delegate

**Use Feature when:**
- Logic works across multiple screens
- Background processing needed
- Global state management
- Preference-driven enable/disable

**Use ViewModel Delegate when:**
- Screen-specific logic
- UI state management
- User interaction handling
- Scoped to single screen lifecycle

**Example:**
- Reels = Feature (processes messages globally, background work)
- ChatReelsDelegate = Delegate (manages Reels UI in ChatViewModel)

## Testing

Create a fake for testing:

```kotlin
class FakeYourFeature(
    override val isEnabled: StateFlow<Boolean> = MutableStateFlow(true)
) : Feature {
    override val id = "fake_your"
    override val displayName = "Fake Your"
    override val description = "Fake feature"
    override val featureFlagKey = "fake_your_enabled"
    override val settingsRoute = null

    var onEnableCalled = false
    var onDisableCalled = false

    override suspend fun onEnable() {
        onEnableCalled = true
    }

    override suspend fun onDisable() {
        onDisableCalled = true
    }
}
```

## Common Pitfalls

### Don't Duplicate Stitch Capabilities

**Bad:**
```kotlin
// Don't create a Feature for platform-specific capabilities
class TypingIndicatorFeature : Feature {
    // This is a Stitch capability, not a cross-platform Feature!
}
```

### Don't Put UI Logic in Feature

**Bad:**
```kotlin
class YourFeature : Feature {
    fun updateUiState() { /* UI logic */ }  // NO - use ViewModel/Delegate
}
```

**Good:**
```kotlin
class YourFeature : Feature {
    suspend fun processData() { /* Business logic */ }  // YES - domain logic
}
```

### Don't Block on Enable/Disable

**Bad:**
```kotlin
override suspend fun onEnable() {
    runBlocking {  // NEVER use runBlocking!
        heavyOperation()
    }
}
```

**Good:**
```kotlin
override suspend fun onEnable() {
    withContext(Dispatchers.IO) {
        heavyOperation()
    }
}
```
