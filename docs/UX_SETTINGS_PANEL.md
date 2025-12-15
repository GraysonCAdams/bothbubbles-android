# Settings Panel UX Architecture

## Overview

This document describes the Settings Panel user experience in BothBubbles, including navigation flow, animation behavior, and conditional content display based on iMessage/SMS configuration state.

---

## Panel Entry & Structure

The Settings Panel is a slide-in sheet that appears from the **right edge** of the screen, providing access to all app configuration options.

### Visual Layout

```
┌──────────────────────────────────────────────────────────────┐
│  [←]  Settings                                        [✕]   │  <- TopAppBar
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Archived                                      (3)   │   │  <- Quick Actions Card
│  │  ─────────────────────────────────────────────────   │   │
│  │  Blocked contacts                                    │   │
│  │  ─────────────────────────────────────────────────   │   │
│  │  Spam protection                                     │   │
│  │  ─────────────────────────────────────────────────   │   │
│  │  Message categorization                              │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  Messaging                 [✓ iMessage ›] [○ SMS ›]         │  <- Section Header with Status Badges
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Notifications                                       │   │  <- Messaging Card
│  │  ─────────────────────────────────────────────────   │   │
│  │  iMessage                                            │   │
│  │  ─────────────────────────────────────────────────   │   │
│  │  SMS/MMS                                             │   │
│  │  ─────────────────────────────────────────────────   │   │
│  │  Quick reply templates                               │   │
│  │  ─────────────────────────────────────────────────   │   │
│  │  Auto-responder                                      │   │
│  │  ─────────────────────────────────────────────────   │   │
│  │  ETA sharing                                         │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  iMessage features (server required)                        │  <- Conditional Section Title
│  ...                                                        │
└──────────────────────────────────────────────────────────────┘
```

---

## Navigation Architecture

### Page Hierarchy

The Settings Panel uses a **flat page model with internal navigation**. Pages are organized in a single-level structure, with some pages acting as parents to nested sub-pages.

```
Main (Root)
├── Server (iMessage)
├── Archived
├── Blocked
├── Spam
├── Categorization
├── Sync
├── Export
├── SMS/MMS
│   └── SmsBackup (nested)
├── Notifications
├── Swipe
├── Effects
├── ImageQuality
├── Templates
├── AutoResponder
├── EtaSharing
├── About
│   └── OpenSourceLicenses (nested)
```

### Navigation State Management

Navigation is managed by `SettingsPanelNavigator`, which maintains:

1. **Current Page** - The active settings page being displayed
2. **Back Stack** - History of previously visited pages for back navigation

**Navigation Flow:**

```
User taps "iMessage" → Navigator pushes "Main" to back stack
                     → Navigator sets currentPage = "Server"
                     → AnimatedContent transitions forward

User taps "← Back"   → Navigator pops from back stack
                     → Navigator sets currentPage = "Main"
                     → AnimatedContent transitions backward

User taps "✕ Close"  → Panel dismisses entirely
```

### TopAppBar Behavior

| Context | Left Icon | Right Icon |
|---------|-----------|------------|
| **Main page** | None | Close (✕) |
| **Sub-page** | Back (←) | Close (✕) |

- **Back button**: Navigates to previous page in stack
- **Close button**: Always visible; dismisses the entire panel
- **System back**: Same behavior as back button; closes panel if at root

---

## Animation Specifications

### Transition Direction Logic

The panel tracks navigation direction (`isForward`) to determine animation:

| Action | Direction | Description |
|--------|-----------|-------------|
| Tap menu item | Forward | New page slides in from right |
| Tap back button | Backward | Previous page slides in from left |
| System back | Backward | Same as back button |

### Forward Navigation (Drilling Down)

**Entering page** (new content):
- Slides in from **100% right** (off-screen)
- Fades in over 200ms

**Exiting page** (current content):
- Slides **left by 25%** (partial exit)
- Fades out over 150ms

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Main Page     │ →→→ │ Main │ iMessage │ →→→ │   iMessage Page │
│                 │     │ (25%)│  (100%)  │     │                 │
└─────────────────┘     └─────────────────┘     └─────────────────┘
     Start                   During                    End
```

### Backward Navigation (Going Back)

**Entering page** (returning content):
- Slides in from **25% left** (partial entry)
- Fades in over 200ms

**Exiting page** (current content):
- Slides **right to 100%** (full exit)
- Fades out over 150ms

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   iMessage Page │ ←←← │ Main │ iMessage │ ←←← │    Main Page    │
│                 │     │(25%) │  (100%)  │     │                 │
└─────────────────┘     └─────────────────┘     └─────────────────┘
     Start                   During                    End
```

### Animation Timing

| Parameter | Value | Notes |
|-----------|-------|-------|
| Slide duration | 300ms | FastOutSlowInEasing |
| Fade in duration | 200ms | Standard |
| Fade out duration | 150ms | Faster for responsiveness |
| Easing | FastOutSlowInEasing | MD3 standard motion |

---

## Status Badges & Conditional Content

### Messaging Section Header

The "Messaging" section displays **tappable status badges** for both messaging services:

```
Messaging                 [✓ iMessage ›] [○ SMS ›]
```

### Badge States

| State | Visual | Icon | Meaning |
|-------|--------|------|---------|
| **CONNECTED** | Tertiary color | ✓ Checkmark | Service active and connected |
| **ERROR** | Error color | ⚠ Warning | Service configured but has issues |
| **DISABLED** | Outline color | ○ Hollow circle | Service not configured/enabled |

All badges include a chevron (`›`) to indicate they are tappable navigation elements.

### Badge Interactions

- **Tap iMessage badge** → Navigate to iMessage (Server) settings
- **Tap SMS badge** → Navigate to SMS/MMS settings

---

## Conditional Content Logic

Content visibility and enabled states vary based on messaging service configuration:

### iMessage Connection States

| ConnectionState | Badge | Behavior |
|-----------------|-------|----------|
| `CONNECTED` | ● Green (tertiary) | All iMessage features enabled |
| `CONNECTING` | Transitional | Features remain in previous state |
| `DISCONNECTED` | ● Red (error) | Features disabled, shows error hint |
| `ERROR` | ● Red (error) | Features disabled, shows error hint |
| `NOT_CONFIGURED` | ○ Gray (outline) | Features disabled, shows setup prompt |

### SMS States

| smsEnabled | Badge | Behavior |
|------------|-------|----------|
| `true` | ● Green (tertiary) | SMS features accessible |
| `false` | ○ Gray (outline) | SMS settings still accessible to enable |

---

## iMessage Features Section

This section displays iMessage-specific capabilities that require server connection.

### Section Title

| Server State | Title Text |
|--------------|------------|
| Configured | "iMessage features" |
| Not configured | "iMessage features (server required)" |

### Feature Toggle States

#### Private API Toggle

```
┌────────────────────────────────────────────────────────────────┐
│  🔑  Enable Private API                              [Toggle]  │
│      {dynamic subtitle}                                        │
└────────────────────────────────────────────────────────────────┘
```

| Condition | Subtitle | Toggle State |
|-----------|----------|--------------|
| Server not configured | "Configure server to enable" | Disabled, OFF |
| Server configured, API off | "Enables typing indicators, reactions, and more" | Enabled, OFF |
| Server configured, API on | "Advanced iMessage features enabled" | Enabled, ON |

#### Typing Indicators Toggle

```
┌────────────────────────────────────────────────────────────────┐
│  ⌨️  Send typing indicators                          [Toggle]  │
│      {dynamic subtitle}                                        │
└────────────────────────────────────────────────────────────────┘
```

| Condition | Subtitle | Toggle State |
|-----------|----------|--------------|
| Server not configured | "Configure server to enable" | Disabled, OFF |
| Private API disabled | "Enable Private API first" | Disabled, OFF (preserves last value) |
| Private API enabled | "Let others know when you're typing" | Enabled, respects user preference |

### Dependency Chain

```
Server Connection → Private API Toggle → Typing Indicators Toggle
     Required          Required              Optional
```

---

## Settings Cards

Settings are grouped into rounded cards (28dp corner radius) for visual hierarchy.

### Card Organization

| Card | Contents |
|------|----------|
| **Quick Actions** | Archived, Blocked, Spam, Categorization |
| **Messaging** | Notifications, iMessage, SMS, Templates, Auto-responder, ETA |
| **iMessage Features** | Private API, Typing Indicators |
| **Appearance & Behavior** | App title, Swipe, Effects, Image quality, Sounds |
| **Connection & Data** | Sync, Export, Link previews |
| **About** | Version, Licenses, Help |

### Card Entrance Animation

Cards use staggered entrance animations:

| Parameter | Value |
|-----------|-------|
| Stagger delay | 30ms per card |
| Fade duration | 150ms |
| Translation | 16dp → 0dp (spring) |
| Spring damping | 0.8 |
| Spring stiffness | Medium |

---

## Sub-Page Nesting

### SMS → Backup & Restore

From the SMS settings page, users can navigate deeper to backup options:

```
Main → SMS/MMS → Backup & Restore
```

Back navigation returns through the stack correctly.

### About → Open Source Licenses

From the About page, users can view license information:

```
Main → About → Open Source Licenses
```

---

## Design Principles

1. **Progressive disclosure** - Main page shows categories; details require navigation
2. **Contextual hints** - Disabled features explain why they're disabled
3. **Consistent motion** - Same animation pattern for all navigation
4. **Quick access** - Status badges allow single-tap access to service settings
5. **Clear hierarchy** - Cards group related settings; sections separate categories
6. **Graceful degradation** - Features remain visible but disabled when prerequisites missing

---

## Component File Locations

| Component | File Path |
|-----------|-----------|
| SettingsPanel (container) | `ui/settings/SettingsPanel.kt` |
| SettingsPanelNavigator | `ui/settings/SettingsPanelNavigator.kt` |
| SettingsPanelPage (enum) | `ui/settings/SettingsPanelPage.kt` |
| SettingsContent (main list) | `ui/settings/SettingsScreen.kt` |
| SettingsViewModel | `ui/settings/SettingsViewModel.kt` |
| SettingsCard, SettingsMenuItem | `ui/settings/components/SettingsComponents.kt` |
| MessagingSectionHeader | `ui/settings/components/SettingsComponents.kt` |
| StatusBadge, BadgeStatus | `ui/settings/components/SettingsComponents.kt` |
| SettingsSwitch | `ui/settings/components/SettingsComponents.kt` |

---

## State Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                      SettingsViewModel                          │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Observes:                                                │   │
│  │  • socketService.connectionState                         │   │
│  │  • settingsDataStore.smsEnabled                          │   │
│  │  • settingsDataStore.serverAddress                       │   │
│  │  • settingsDataStore.enablePrivateApi                    │   │
│  │  • settingsDataStore.sendTypingIndicators                │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ SettingsUiState (exposed via StateFlow)                  │   │
│  │  • connectionState: ConnectionState                      │   │
│  │  • smsEnabled: Boolean                                   │   │
│  │  • isServerConfigured: Boolean                           │   │
│  │  • enablePrivateApi: Boolean                             │   │
│  │  • sendTypingIndicators: Boolean                         │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      SettingsContent                            │
│  Uses uiState to:                                               │
│  • Determine badge colors (MessagingSectionHeader)              │
│  • Set enabled/disabled states on toggles                       │
│  • Update subtitles with contextual hints                       │
│  • Show/hide conditional UI elements                            │
└─────────────────────────────────────────────────────────────────┘
```

---

## Future Improvements

### 1. Settings Search

The current hierarchy requires users to navigate through multiple pages to find specific settings.

**Proposal:** Add a search bar to the TopAppBar that filters settings across all pages.

```
┌──────────────────────────────────────────────────────────────┐
│  [←]  Settings                                 [🔍]   [✕]   │
├──────────────────────────────────────────────────────────────┤
│  ╭────────────────────────────────────────────────────────╮  │
│  │ 🔍 Search settings...                                  │  │
│  ╰────────────────────────────────────────────────────────╯  │
```

- Searches across setting titles, subtitles, and section names
- Results show breadcrumb path (e.g., "Messaging > iMessage > Private API")
- Tapping result navigates directly to that page with setting highlighted

---

### 2. Gesture Navigation ✅ IMPLEMENTED

**Solution:** Added edge-swipe gesture support for back navigation.

| Gesture | Action |
|---------|--------|
| Swipe from left edge (20dp) | Navigate back within panel |
| Swipe right on content area | No action (avoid conflicts with scrolling) |

**Implementation Details:**
- Edge detection: 20dp from left edge
- Threshold: 100dp horizontal distance to trigger
- Visual feedback: Content follows finger during swipe
- Uses `Modifier.pointerInput` with `detectHorizontalDragGestures`

---

### 3. Accessibility Enhancements ✅ IMPLEMENTED

#### 3a. Status Badge Iconography ✅

**Issue:** Color-only differentiation fails WCAG for colorblind users.

**Solution (Implemented):** Added icons inside badges for redundant state indication:

| State | Icon |
|-------|------|
| CONNECTED | ✓ Checkmark (14dp) |
| ERROR | ⚠ Warning (14dp) |
| DISABLED | ○ Hollow circle (10dp) |

```
[✓ iMessage ›]  [○ SMS ›]
```

#### 3b. Touch Target Size ✅

**Issue:** Status badges may be smaller than 48x48dp minimum.

**Solution (Implemented):** Added `defaultMinSize(minHeight = 48.dp)` to badge Surface.

---

### 4. Interactive Affordance for Badges ✅ IMPLEMENTED

**Issue:** Users may not realize status badges are tappable navigation elements.

**Solution (Implemented):** Added chevron icon (`KeyboardArrowRight`, 16dp) to all status badges.

```
[✓ iMessage ›]  [○ SMS ›]
```

The Material3 Surface component also provides built-in ripple feedback on press.

---

### 5. Contextual Help for Technical Features ✅ IMPLEMENTED

**Solution:** Added `onInfoClick` parameter to `SettingsMenuItem` and `PrivateApiHelpSheet` bottom sheet.

```
┌────────────────────────────────────────────────────────────────┐
│  🔑  Enable Private API                         [ℹ]  [Toggle] │
│      Advanced iMessage features enabled                        │
└────────────────────────────────────────────────────────────────┘
                                                    ↓ Tap
┌────────────────────────────────────────────────────────────────┐
│                    What is Private API?                        │
├────────────────────────────────────────────────────────────────┤
│  The Private API enables advanced iMessage features by         │
│  accessing macOS system frameworks on your BlueBubbles server. │
│                                                                │
│  ✓ Enables: Typing indicators, read receipts, reactions,       │
│             replies, message editing, scheduled sends          │
│                                                                │
│  ⚠ Requires: SIP disabled on Mac, additional server setup     │
│                                                                │
│  [Learn more →]                              [Got it]          │
└────────────────────────────────────────────────────────────────┘
```

**Implemented for:**
- Private API toggle (via `PrivateApiHelpSheet`)

**Settings that could benefit from help (future):**
- Message categorization (ML-based)
- Spam protection algorithms
- ETA sharing (notification access)

---

### 6. Reset to Default

**Issue:** Users may change settings experimentally and forget original values.

**Proposal:** Add reset option in sub-pages with multiple adjustable values:

**Applicable pages:**
- Image Quality (compression levels)
- Effects (animation toggles)
- Swipe Actions (gesture mappings)
- Notifications (sound, vibration, display)

**UI Pattern:**

```
┌──────────────────────────────────────────────────────────────┐
│  [←]  Image quality                                   [✕]   │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Compression level                                   │   │
│  │  ...                                                 │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  🔄 Reset to defaults                                │   │  <- At bottom of page
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

**Confirmation:** Show dialog before resetting to prevent accidental data loss.

---

## Implementation Priority

| Improvement | Impact | Effort | Priority | Status |
|-------------|--------|--------|----------|--------|
| A11y: Badge icons | High (compliance) | Low | P0 | **Implemented** |
| A11y: Touch targets | High (compliance) | Low | P0 | **Implemented** |
| Interactive affordance | Medium | Low | P1 | **Implemented** |
| Gesture navigation | Medium | Medium | P1 | **Implemented** |
| Contextual help | Medium | Medium | P2 | **Implemented** |
| Settings search | High | High | P2 | Planned |
| Reset to default | Low | Medium | P3 | Planned |
