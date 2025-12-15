# Add Auto-Share Rule Screen UX

This document describes the user experience for creating and editing auto-share rules within ETA Sharing settings. Auto-share rules automatically send ETA updates to selected contacts when navigating to specific destinations.

## Overview

The Add Auto-Share Rule dialog allows users to configure automatic ETA sharing based on navigation destination matching. When a user starts navigation in Google Maps or Waze, the app matches the destination against configured rules and automatically shares ETA updates with the specified recipients.

## Access Points

The dialog is accessed from:
- **ETA Sharing Settings** → Auto-Share Rules section → "Add Rule" button
- **ETA Sharing Settings** → Existing rule card → "Edit" button

## Dialog Structure

```
┌────────────────────────────────────────────────────────────────┐
│                    Add Auto-Share Rule                         │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  Destination Name                                              │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ e.g., Home, Work                                         │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│  Location Type                                                 │
│  [🏠 Home]  [💼 Work]  [📍 Custom]                             │
│                                                                │
│  Keywords (match navigation destination)                       │
│  ┌─────────┐ ┌─────────────┐ ┌──────────┐                     │
│  │ 123 Main│ │ Main Street │ │    ✕     │                     │
│  └─────────┘ └─────────────┘ └──────────┘                     │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ Add keyword                                          [+] │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│  Share with (2/5)                                              │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ ☑ Mom                                                    │ │
│  │ ☑ Partner                                                │ │
│  │ ☐ Family Group Chat                         (Group)      │ │
│  │ ☐ Dad                                                    │ │
│  │ ☐ Sister                                                 │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│                              [Cancel]           [Add]          │
└────────────────────────────────────────────────────────────────┘
```

## Form Fields

### 1. Destination Name (Required)

**Component:** `OutlinedTextField`

- **Purpose:** Human-readable name for the rule (e.g., "Home", "Work", "Gym")
- **Placeholder:** "e.g., Home, Work"
- **Validation:** Cannot be blank
- **Uniqueness:** Cannot duplicate existing rule names

### 2. Location Type (Required)

**Component:** Row of `FilterChip` elements

| Type     | Icon            | Use Case                                    |
|----------|-----------------|---------------------------------------------|
| Home     | 🏠 `Home`       | Primary residence                           |
| Work     | 💼 `Work`       | Office or workplace                         |
| Custom   | 📍 `Place`      | Any other location (default)                |

- **Default:** Custom
- **Selection:** Single-select (radio behavior)
- **Visual:** Selected chip shows filled style; unselected shows outline style

### 3. Keywords (Required)

**Purpose:** Match against navigation destination text from Google Maps/Waze notifications.

**Components:**
- `FlowRow` of `InputChip` elements for existing keywords
- `OutlinedTextField` for adding new keywords

**Interaction:**
1. Type keyword in text field
2. Press Enter/Done or tap (+) button to add
3. Keyword appears as chip above input
4. Tap (✕) on chip to remove

**Keyword Matching Logic:**
- Case-insensitive matching
- Street abbreviation normalization (Street → St, Avenue → Ave, etc.)
- Contains matching (keyword found within destination)
- Word-based similarity (60% word overlap threshold)

**Example Keywords for "Home":**
- `123 Main Street`
- `Main St`
- `Home`

### 4. Recipients (Required)

**Component:** `LazyColumn` with `Checkbox` rows

- **Maximum:** 5 recipients per rule
- **Counter:** "Share with (X/5)" shows current selection
- **Loading State:** Shows `CircularProgressIndicator` while fetching chats
- **Chat Display:**
  - Individual chats: Display name only
  - Group chats: Display name + "(Group)" label

**Selection Behavior:**
- Tap row or checkbox to toggle
- Selection blocked when 5 recipients already selected (except to deselect)
- Shows first 20 available chats

## Validation

The "Add" / "Save" button is **disabled** until all conditions are met:

| Field            | Requirement                    |
|------------------|--------------------------------|
| Destination Name | Non-blank                      |
| Keywords         | At least 1 keyword             |
| Recipients       | At least 1 selected (max 5)    |

## States

### Add Mode
- **Title:** "Add Auto-Share Rule"
- **Confirm Button:** "Add"
- All fields start empty/default

### Edit Mode
- **Title:** "Edit Rule"
- **Confirm Button:** "Save"
- Fields pre-populated with existing rule data
- Selected recipients restored from saved rule

### Loading State
When chats are loading:
```
┌──────────────────────────────────────────────────────────────┐
│                    ⟳ Loading chats...                        │
└──────────────────────────────────────────────────────────────┘
```

## Auto-Share Rules Section (Parent Screen)

### Empty State

When no rules exist:

```
┌────────────────────────────────────────────────────────────────┐
│                                                                │
│                          📍                                    │
│                                                                │
│               No auto-share rules yet                          │
│    Automatically share your ETA when navigating                │
│              to saved destinations                             │
│                                                                │
│                      [+ Add Rule]                              │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### Rule Card (Populated State)

```
┌────────────────────────────────────────────────────────────────┐
│  ┌────┐                                                        │
│  │ 🏠 │  Home                                        [Toggle]  │
│  └────┘  Mom, Partner                                          │
│                                                                │
│         ┌───────────┐ ┌─────────────┐ +2 more                 │
│         │ 123 Main  │ │ Main Street │                         │
│         └───────────┘ └─────────────┘                         │
│                                                                │
│                                     [Edit]  [Delete]           │
└────────────────────────────────────────────────────────────────┘
```

**Card Components:**
- **Icon Badge:** Location type icon in circular container
- **Title:** Destination name
- **Subtitle:** Comma-separated recipient names (truncated)
- **Keywords:** Up to 3 keyword chips + "+N more" indicator
- **Toggle:** Enable/disable rule without deleting
- **Actions:** Edit and Delete buttons (text style)

## Behavior

### Rule Triggering

When navigation starts:
1. App reads navigation notification from Google Maps/Waze
2. Destination text is normalized (lowercase, abbreviations standardized)
3. Each enabled rule's keywords are checked for matches
4. First matching rule triggers auto-share
5. Rate limiting: 5-minute cooldown between triggers for same rule

### Privacy Features

- **Consecutive Day Tracking:** Rules track how many consecutive days they've been triggered
- **Privacy Reminder:** After 5 consecutive days, app shows reminder about automatic sharing
- **Manual Override:** User can always disable rules or sharing entirely

## Technical Details

### Data Model

```kotlin
data class AutoShareRule(
    val id: Long,
    val destinationName: String,
    val keywords: List<String>,
    val locationType: LocationType,  // HOME, WORK, CUSTOM
    val recipients: List<AutoShareRecipient>,
    val enabled: Boolean,
    val lastTriggeredAt: Long?,
    val consecutiveTriggerDays: Int
)

data class AutoShareRecipient(
    val chatGuid: String,
    val displayName: String
)
```

### Keyword Normalization

The following abbreviations are normalized during matching:

| Full Form  | Abbreviated |
|------------|-------------|
| Street     | St          |
| Avenue     | Ave         |
| Boulevard  | Blvd        |
| Drive      | Dr          |
| Road       | Rd          |
| Lane       | Ln          |
| Court      | Ct          |
| Place      | Pl          |

Punctuation (`. , #`) is removed and whitespace is normalized.

## Related Files

- `ui/settings/eta/AutoShareComponents.kt` - UI components
- `ui/settings/eta/EtaSharingSettingsScreen.kt` - Parent screen
- `ui/settings/eta/EtaSharingSettingsViewModel.kt` - ViewModel
- `data/repository/AutoShareRuleRepository.kt` - Data layer
- `data/local/db/dao/AutoShareRuleDao.kt` - Database access
- `data/local/db/entity/AutoShareRuleEntity.kt` - Database entity
