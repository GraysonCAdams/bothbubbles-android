# Settings Panel UX Improvements

Based on the architecture in `UX_SETTINGS_PANEL.md`, the following improvements are proposed to enhance discoverability, accessibility, and error handling.

## Implementation Status

| Feature                                     | Status          |
| ------------------------------------------- | --------------- |
| Status Badge Accessibility (icons)          | **Implemented** |
| Touch Targets (48dp)                        | **Implemented** |
| Interactive Affordance (chevrons)           | **Implemented** |
| Disabled Row Interaction (shake + snackbar) | **Implemented** |
| Connecting State (loading spinner)          | **Implemented** |
| Actionable Error States (tappable links)    | **Implemented** |
| Search Functionality                        | Planned         |
| Gesture Navigation                          | Planned         |
| Contextual Help                             | Planned         |
| Reset to Default                            | Planned         |

---

## 1. Navigation & Discoverability

### Search Functionality

The current hierarchy is deep. Adding a search bar to the `TopAppBar` would allow users to find specific settings (like "Private API" or "Theme") without navigating through multiple layers.

```kotlin
// Concept
Row(verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = { /* Open Search */ }) {
        Icon(Icons.Default.Search, contentDescription = "Search settings")
    }
}
```

### Gesture Navigation

Ensure **edge-to-edge swipe gestures** are supported for navigation. Users expect to swipe from the left edge to go back, rather than reaching for the top-left arrow button.

---

## 2. Accessibility & Visual Affordance ✅ IMPLEMENTED

### Status Badge Accessibility ✅

**Solution:** Added distinct icons inside badges for colorblind users:

- **CONNECTED:** ✓ Checkmark icon (14dp, tertiary color)
- **ERROR:** ⚠ Warning icon (14dp, error color)
- **DISABLED:** ○ Hollow circle (10dp, outline color)

**Touch Targets:** Added `defaultMinSize(minHeight = 48.dp)` to badge Surface.

### Interactive Affordance ✅

**Solution:** Added chevron (`KeyboardArrowRight`, 16dp) to all status badges to indicate they are tappable navigation elements.

```
[✓ iMessage ›]  [○ SMS ›]
```

---

## 3. Feature Management & Help

### Contextual Help

For complex toggles like "Private API," the subtitle is helpful but limited.

- **Improvement:** Add a "Help" or "Info" icon next to the toggle that opens a bottom sheet or dialog explaining _exactly_ what the feature does and the risks/benefits involved.

### Undo/Reset Actions

- **Improvement:** Add a "Reset to Default" option within specific sub-pages (like Image Quality or Effects) to allow safe experimentation.

---

## 4. Conditional Logic & Error Handling ✅ IMPLEMENTED

### Interaction on Disabled Rows (The "Dead Click" Problem) ✅

**Solution:** Enhanced `SettingsMenuItem` component with:

1. **Shake Animation:** Horizontal shake (±8dp → ±6dp → ±3dp → 0) over 300ms when disabled row is tapped
2. **Haptic Feedback:** `HapticFeedbackType.LongPress` on disabled click
3. **Snackbar with Action:** Shows message explaining why feature is disabled, with optional action button

```kotlin
SettingsMenuItem(
    // ...
    enabled = false,
    onDisabledClick = {
        showSnackbar(
            message = "Configure iMessage server first",
            actionLabel = "Configure",
            onAction = { navigateToServerSettings() }
        )
    }
)
```

**Example flow:**

```
User taps "Send typing indicators" (disabled) →
  Row shakes → Haptic feedback →
  Snackbar: "Enable Private API to use this feature"
```

### The "Connecting" Limbo ✅

**Solution:** Added `isLoading` parameter to `SettingsMenuItem`:

- When `isLoading = true`, shows `CircularProgressIndicator` (24dp) instead of trailing content
- Row remains clickable but ignores clicks during loading state
- Applied to Private API toggle during `ConnectionState.CONNECTING`

```kotlin
SettingsMenuItem(
    // ...
    isLoading = uiState.connectionState == ConnectionState.CONNECTING,
    enabled = !isLoading
)
```

### Actionable Error States ✅

**Solution:** Added `subtitleAction` and `onSubtitleActionClick` parameters to `SettingsMenuItem`:

- Appends tappable link text to subtitle using `ClickableText`
- Link styled with primary color and underline
- Enables in-place actions without navigating away

```
┌────────────────────────────────────────────────────────────────┐
│  🔑  Enable Private API                              [Toggle]  │
│      Server disconnected. Tap to reconnect                     │
│                           ^^^^^^^^^^^^^^^^ (tappable link)     │
└────────────────────────────────────────────────────────────────┘
```

```kotlin
SettingsMenuItem(
    subtitle = "Server disconnected.",
    subtitleAction = "Tap to reconnect",
    onSubtitleActionClick = { viewModel.reconnect() }
)
```

### Refined Connection State Logic ✅

| ConnectionState          | UI Behavior                                                                               |
| ------------------------ | ----------------------------------------------------------------------------------------- |
| `CONNECTING`             | Loading spinner replaces toggle. Interaction blocked. Subtitle: "Connecting to server..." |
| `CONNECTED`              | Normal toggle operation.                                                                  |
| `DISCONNECTED` / `ERROR` | Toggle disabled. Actionable subtitle: "Server disconnected. Tap to reconnect"             |
| `NOT_CONFIGURED`         | Toggle disabled. Shake + snackbar with "Configure" action on tap.                         |

---

## Component Changes

### SettingsMenuItem (Enhanced)

New parameters added to `ui/settings/components/SettingsComponents.kt`:

```kotlin
@Composable
fun SettingsMenuItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    // NEW: Called when disabled row is tapped (shake + snackbar)
    onDisabledClick: (() -> Unit)? = null,
    // NEW: Tappable action text appended to subtitle
    subtitleAction: String? = null,
    onSubtitleActionClick: (() -> Unit)? = null,
    // NEW: Shows loading spinner instead of trailing content
    isLoading: Boolean = false,
    trailingContent: @Composable (() -> Unit)? = null
)
```

### SettingsContent (Enhanced)

Added to `ui/settings/SettingsScreen.kt`:

- `SnackbarHostState` for showing feedback messages
- `showDisabledSnackbar()` helper function with optional action
- Connection state logic for iMessage features section
