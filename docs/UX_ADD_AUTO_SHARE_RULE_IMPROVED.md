# Improved Add Auto-Share Rule Screen UX

This document describes the improved user experience for creating and editing auto-share rules within ETA Sharing settings. This design focuses on a contact-centric approach and a cleaner address input interface.

## Overview

The Add Auto-Share Rule dialog allows users to configure automatic ETA sharing based on navigation destination matching. When a user starts navigation in Google Maps or Waze, the app matches the destination against configured rules and automatically shares ETA updates with the specified contacts.

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
│  Rule Details                                                  │
│  ┌──────────────────────┐ ┌──────────────────────────────────┐ │
│  │ [ Icon ] ▾           │ │ Name (e.g. Home)                 │ │
│  └──────────────────────┘ └──────────────────────────────────┘ │
│                                                                │
│  Trigger Addresses                                             │
│  (Matches navigation destination)                              │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ 📍 123 Main St                                       [✕] │ │
│  │ 📍 Main Street                                       [✕] │ │
│  ├──────────────────────────────────────────────────────────┤ │
│  │ + Add address or phrase...                               │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│  Share with Contacts (2/5)                                     │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ 🔍 Search contacts...                                    │ │
│  └──────────────────────────────────────────────────────────┘ │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ 👤 Mom                                                   │ │
│  │    +1 555-0123                                       [☑] │ │
│  ├──────────────────────────────────────────────────────────┤ │
│  │ 👤 Partner                                               │ │
│  │    partner@example.com                               [☑] │ │
│  ├──────────────────────────────────────────────────────────┤ │
│  │ 👤 Dad                                                   │ │
│  │    +1 555-0199                                       [☐] │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│                              [Cancel]           [Save]         │
└────────────────────────────────────────────────────────────────┘
```

## Form Fields

### 1. Rule Details (Name & Icon)

**Layout:** Row containing an Icon Picker dropdown and the Name field.

**A. Icon Picker**

- **Component:** Dropdown menu or Modal Bottom Sheet trigger
- **Purpose:** Visual identifier for the rule type
- **Options:**
  - 🏠 Home
  - 💼 Work
  - 🎓 School
  - 🏋️ Gym
  - 📍 Custom (Default)
- **Behavior:** Selecting an option updates the icon. If the Name field is empty, selecting "Home" or "Work" auto-fills the Name field.

**B. Destination Name**

- **Component:** `OutlinedTextField`
- **Placeholder:** "e.g., Home, Work"
- **Validation:** Required, Unique

### 2. Trigger Addresses (Keywords)

**Purpose:** Define which navigation destinations trigger this rule.

**Component:** Vertical list of existing triggers + Add button/field.

- **Visual Style:** Clean vertical list where each address takes a full row for better readability.
- **Input:**
  - Clicking "+ Add address..." opens a simple dialog or focuses an inline text field.
  - Supports pasting full addresses.
- **Matching Logic:**
  - Case-insensitive matching
  - Street abbreviation normalization (Street → St, Avenue → Ave, etc.)
  - Contains matching (keyword found within destination)
  - Word-based similarity (60% word overlap threshold)

### 3. Recipients (Contacts Only)

**Changes:**

- **Source:** System Contacts / BlueBubbles Contacts (NOT recent chats).
- **Filter:** Only shows contacts with valid handles (phone numbers or emails).

**Component:**

- **Search Bar:** Sticky at top of list. Filters by name, phone, or email.
- **List:** `LazyColumn` of Contact items.

**Contact Item Layout:**

```
[ Avatar ]  [ Name           ]    [ Checkbox ]
            [ Phone/Email    ]
```

- **Selection:**
  - Multi-select (Max 5).
  - Tapping anywhere on the row toggles selection.
  - Selected items float to the top of the list or are highlighted clearly.

## Validation

The "Save" button is **disabled** until:

1. Destination Name is set.
2. At least 1 Trigger Address is added.
3. At least 1 Contact is selected.

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

## Technical Details

### Data Model

```kotlin
data class AutoShareRule(
    val id: Long,
    val destinationName: String,
    val iconKey: String,             // e.g., "home", "work", "custom"
    val triggerAddresses: List<String>,
    val recipients: List<AutoShareContact>,
    val enabled: Boolean,
    val lastTriggeredAt: Long?,
    val consecutiveTriggerDays: Int
)

data class AutoShareContact(
    val address: String,      // Phone number or Email
    val displayName: String,
    val avatarPath: String?
)
```

### Keyword Normalization

The following abbreviations are normalized during matching:

| Full Form | Abbreviated |
| --------- | ----------- |
| Street    | St          |
| Avenue    | Ave         |
| Boulevard | Blvd        |
| Drive     | Dr          |
| Road      | Rd          |
| Lane      | Ln          |
| Court     | Ct          |
| Place     | Pl          |

Punctuation (`. , #`) is removed and whitespace is normalized.
