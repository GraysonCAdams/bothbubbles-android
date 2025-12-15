# Chat Creation UX Flow

This document describes the complete user experience for creating new chats (single or group) in BothBubbles.

## Screens Overview

| Screen | File | Purpose |
|--------|------|---------|
| ChatCreatorScreen | `ui/chatcreator/ChatCreatorScreen.kt` | Primary entry point for new 1:1 or group conversations |
| GroupCreatorScreen | `ui/chatcreator/GroupCreatorScreen.kt` | Dedicated group participant picker (alternative entry) |
| GroupSetupScreen | `ui/chatcreator/GroupSetupScreen.kt` | Final step: set group name and photo |

## Navigation Routes

Defined in [Screen.kt](app/src/main/kotlin/com/bothbubbles/ui/navigation/Screen.kt):

```kotlin
Screen.ChatCreator(
    initialAddress: String? = null,         // Pre-fill a recipient
    initialAttachments: List<String> = []   // Pre-attach shared content
)

Screen.GroupCreator(
    preSelectedAddress: String? = null,      // Pre-select first participant
    preSelectedDisplayName: String? = null,
    preSelectedService: String? = null,
    preSelectedAvatarPath: String? = null
)

Screen.GroupSetup(
    participantsJson: String,   // JSON-encoded List<GroupParticipant>
    groupService: String        // "IMESSAGE" or "MMS"
)
```

---

## Entrypoints

### 1. Floating Action Button (Primary)

**Location:** ConversationsScreen (home screen)
**File:** [ConversationsScreen.kt:644-662](app/src/main/kotlin/com/bothbubbles/ui/conversations/ConversationsScreen.kt#L644-L662)

- Extended FAB labeled "Start chat" with message icon
- Animates to collapsed state when scrolling down
- Navigates to `Screen.ChatCreator()`

### 2. Share Intent (External App Sharing)

**Location:** SharePickerScreen → "New conversation" button
**File:** [SetupShareNavigation.kt:55-71](app/src/main/kotlin/com/bothbubbles/ui/navigation/SetupShareNavigation.kt#L55-L71)

- When sharing content from another app (images, text, links)
- User can select existing conversation OR tap "New conversation"
- Navigates to `Screen.ChatCreator(initialAttachments = sharedUris)`
- Shared text passed via `savedStateHandle`

### 3. Chat Details → Create Group

**Location:** ConversationDetailsScreen → ParticipantsSection
**Files:**
- [ChatNavigation.kt:176-183](app/src/main/kotlin/com/bothbubbles/ui/navigation/ChatNavigation.kt#L176-L183)
- [ConversationDetailsParticipants.kt:49-57](app/src/main/kotlin/com/bothbubbles/ui/chat/details/ConversationDetailsParticipants.kt#L49-L57)

- When viewing a 1:1 conversation's details
- "Create group" button pre-selects current contact
- Navigates to `Screen.GroupCreator(preSelectedAddress, preSelectedDisplayName, ...)`

---

## Flow Diagrams

### Single Recipient (1:1 Chat)

```
┌─────────────────────────────────────────────────────────────┐
│                     ChatCreatorScreen                        │
│  ┌─────────────────────────────────────────────────────────┐│
│  │  Title: "New chat"                          [← Back]    ││
│  └─────────────────────────────────────────────────────────┘│
│                                                             │
│  ┌─────────────────────────────────────────────────────────┐│
│  │  To: [Type name, phone number, or email____________]    ││
│  └─────────────────────────────────────────────────────────┘│
│                                                             │
│  ┌─ Recent ────────────────────────────────────────────────┐│
│  │  [Avatar] John Doe                                      ││
│  │           +1 (555) 123-4567                             ││
│  │  [Avatar] Jane Smith                                    ││
│  │           jane@email.com                                ││
│  └─────────────────────────────────────────────────────────┘│
│                                                             │
│  ┌─ All Contacts ──────────────────────────────────────────┐│
│  │  A                                                      ││
│  │    [Avatar] Alice Anderson                              ││
│  │    [Avatar] Andrew Brown                                ││
│  │  B                                                      ││
│  │    [Avatar] Bob Builder                                 ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼ Tap contact
                    ┌──────────────┐
                    │ Create/Find  │
                    │  1:1 Chat    │
                    └──────────────┘
                           │
                           ▼
                    ┌──────────────┐
                    │  ChatScreen  │
                    │ (conversation)│
                    └──────────────┘
```

### Multiple Recipients (Group Chat)

```
┌─────────────────────────────────────────────────────────────┐
│                     ChatCreatorScreen                        │
│  ┌─────────────────────────────────────────────────────────┐│
│  │  Title: "New chat"                    [Continue →]      ││
│  └─────────────────────────────────────────────────────────┘│
│                                                             │
│  ┌─ Selected Recipients ───────────────────────────────────┐│
│  │  [John ×] [Jane ×] [Bob ×]                              ││
│  └─────────────────────────────────────────────────────────┘│
│                                                             │
│  ┌─────────────────────────────────────────────────────────┐│
│  │  To: [Add another recipient..._____________________]    ││
│  └─────────────────────────────────────────────────────────┘│
│                                                             │
│  ┌─────────────────────────────────────────────────────────┐│
│  │  ╔═════════════════════════════════════════════════════╗││
│  │  ║  [+] Create group (3 people)                        ║││
│  │  ╚═════════════════════════════════════════════════════╝││
│  └─────────────────────────────────────────────────────────┘│
│                                                             │
│  [Contact list...]                                          │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼ Tap "Continue" or "Create group"
┌─────────────────────────────────────────────────────────────┐
│                     GroupSetupScreen                         │
│  ┌─────────────────────────────────────────────────────────┐│
│  │  Title: "New group"                          [Done]     ││
│  └─────────────────────────────────────────────────────────┘│
│                                                             │
│              ┌───────────────────┐                          │
│              │                   │                          │
│              │   [Group Avatar]  │  ← Tap to add photo      │
│              │        📷        │                          │
│              │                   │                          │
│              └───────────────────┘                          │
│                                                             │
│  ┌─────────────────────────────────────────────────────────┐│
│  │  Group name (optional)  [___________________] [×]       ││
│  └─────────────────────────────────────────────────────────┘│
│  "Everyone will see this group name" (iMessage)             │
│  "Only you can see this group name" (MMS)                   │
│                                                             │
│  ┌─ 3 participants ────────────────────────────────────────┐│
│  │  ┌───────────────────────────────────────┬──────────┐  ││
│  │  │ John Doe                              │ iMessage │  ││
│  │  └───────────────────────────────────────┴──────────┘  ││
│  │  ┌───────────────────────────────────────┬──────────┐  ││
│  │  │ Jane Smith                            │ iMessage │  ││
│  │  └───────────────────────────────────────┴──────────┘  ││
│  │  ┌───────────────────────────────────────┬──────────┐  ││
│  │  │ Bob Builder                           │   SMS    │  ││
│  │  └───────────────────────────────────────┴──────────┘  ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼ Tap "Done"
                    ┌──────────────┐
                    │  Create Group│
                    │   (API call) │
                    └──────────────┘
                           │
                           ▼
                    ┌──────────────┐
                    │  ChatScreen  │
                    │ (group chat) │
                    └──────────────┘
```

---

## Detailed Screen Behavior

### ChatCreatorScreen

**File:** [ChatCreatorScreen.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/ChatCreatorScreen.kt)

#### UI Elements

1. **Top App Bar**
   - Title: "New chat"
   - Back button (←)
   - "Continue" button (appears when 1+ recipients selected)

2. **Selected Recipients Row** (conditional)
   - Horizontal scrollable chips
   - Color-coded: blue for iMessage, gray for SMS
   - Each chip has remove (×) button

3. **Search/To Field**
   - Rounded surface with "To:" label
   - Placeholder: "Type name, phone number, or email"
   - Auto-focus on screen open
   - Backspace on empty field removes last recipient

4. **Create Group Button** (conditional)
   - Appears when 2+ recipients selected
   - Label: "Create group (N people)"
   - Primary container color

5. **Contact List**
   - **Recent** section (up to 4 contacts with recent conversations)
   - **All Contacts** header
   - **Favorites** (★) at top of alphabetical list
   - **Alphabetical sections** (A, B, C, ...)
   - **Fast scroller** on right edge

6. **Manual Address Entry** (conditional)
   - Appears when typed text is valid phone/email
   - Shows "Send to {address}" with service indicator
   - Checks iMessage availability via API

#### Interactions

| Action | Behavior |
|--------|----------|
| Tap contact (no selection) | Creates/opens 1:1 chat directly |
| Tap contact (has selection) | Toggles contact selection |
| Tap chip × | Removes recipient from selection |
| Press Done on keyboard | Adds current query as recipient (if valid address) |
| Backspace on empty field | Removes last selected recipient |
| Tap "Continue" (1 recipient) | Creates/opens 1:1 chat |
| Tap "Continue" (2+ recipients) | Navigates to GroupSetupScreen |
| Tap "Create group" button | Navigates to GroupSetupScreen |

### GroupCreatorScreen

**File:** [GroupCreatorScreen.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/GroupCreatorScreen.kt)

Alternative entry point for group creation (used from Chat Details).

#### Key Differences from ChatCreatorScreen

- Title: "New group" (instead of "New chat")
- Shows "Add:" label (instead of "To:")
- "Next" button (instead of "Continue")
- Shows group service indicator (iMessage/MMS) based on participant mix
- Can be pre-populated with a contact (from Chat Details)

### GroupSetupScreen

**File:** [GroupSetupScreen.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/GroupSetupScreen.kt)

#### UI Elements

1. **Top App Bar**
   - Title: "New group"
   - Back button
   - "Done" button (creates group)

2. **Group Avatar**
   - 120dp rounded square
   - Shows composite avatar by default
   - Tap to select custom photo
   - Camera icon overlay in corner
   - Note: "Only you can see this picture"

3. **Group Name Input**
   - Optional text field
   - Clear button (×)
   - Service-specific hint:
     - iMessage: "Everyone will see this group name"
     - MMS: "Only you can see this group name"

4. **Participants List**
   - Shows count: "N participants"
   - Color-coded rows:
     - Blue tint = iMessage participant
     - Gray tint = SMS participant
   - Service indicator on right

#### Group Creation Logic

- **iMessage Group:** All participants must be iMessage-capable
  - Created via BlueBubbles server API
  - Group name syncs to all participants

- **MMS Group:** Any participant is SMS-only
  - Created locally on device
  - Group name stored locally only
  - GUID format: `mms;-;addr1,addr2,addr3`

---

## Data Models

### ContactUiModel

```kotlin
data class ContactUiModel(
    val address: String,           // Raw phone/email
    val normalizedAddress: String, // For de-duplication
    val formattedAddress: String,  // Display format (+1 555-123-4567)
    val displayName: String,
    val service: String,           // "iMessage" or "SMS"
    val avatarPath: String?,
    val isFavorite: Boolean,
    val isRecent: Boolean
)
```

### SelectedRecipient

```kotlin
data class SelectedRecipient(
    val address: String,
    val displayName: String,
    val service: String,
    val avatarPath: String?,
    val isManualEntry: Boolean
)
```

### GroupParticipant

```kotlin
@Serializable
data class GroupParticipant(
    val address: String,
    val displayName: String,
    val service: String,
    val avatarPath: String?,
    val isManualEntry: Boolean
)
```

---

## ViewModel Architecture

### ChatCreatorViewModel

**File:** [ChatCreatorViewModel.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/ChatCreatorViewModel.kt)

Uses delegate pattern for separation of concerns:

| Delegate | Responsibility |
|----------|----------------|
| `ContactLoadDelegate` | Load contacts from phone + handles |
| `ContactSearchDelegate` | Search filtering, address validation |
| `RecipientSelectionDelegate` | Manage selected recipients state |
| `ChatCreationDelegate` | Create chats, handle navigation |

### GroupCreatorViewModel

**File:** [GroupCreatorViewModel.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/GroupCreatorViewModel.kt)

Simpler structure (no delegates):
- Loads phone contacts directly
- Checks iMessage availability for manual entries
- Determines group service type (iMessage vs MMS)
- Serializes participants for navigation

### GroupSetupViewModel

**File:** [GroupSetupViewModel.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/GroupSetupViewModel.kt)

- Parses participants from navigation args
- Manages group name and photo
- Creates group via API or locally

---

## Service Determination Logic

```
┌─────────────────────────────────────────────────────────────┐
│                  Group Service Type                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   ALL participants iMessage?  ─── YES ─→  iMessage Group    │
│              │                                              │
│             NO                                              │
│              │                                              │
│              ▼                                              │
│        MMS Group (SMS)                                      │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

iMessage availability is determined by:
1. Cached handles from BlueBubbles server
2. Real-time API check for manual address entries
3. Email addresses are always iMessage

---

## UI Components

### RecipientChip

Color-coded chip for selected recipients:
- iMessage: `primaryContainer` / `onPrimaryContainer`
- SMS: `secondaryContainer` / `onSecondaryContainer`

### ContactTile

Contact row with:
- Avatar (with selection checkmark overlay)
- Favorite star indicator (top-right)
- Name and formatted address

### ManualAddressTile

Entry option for manually typed addresses:
- Shows "Send to {address}"
- Loading spinner while checking iMessage
- Service badge (iMessage/SMS)

### AlphabetFastScroller

Right-edge scroller for quick navigation:
- Shows available letters (A-Z, #, ★)
- Vertical draggable interaction
- Animates to section on selection

---

## Navigation Flow Summary

```
                            ┌─────────────┐
                            │   FAB tap   │
                            │  (primary)  │
                            └──────┬──────┘
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        │                          │                          │
        ▼                          ▼                          ▼
┌──────────────┐         ┌──────────────┐          ┌──────────────┐
│ Share Intent │         │ Chat Details │          │              │
│ "New convo"  │         │"Create group"│          │              │
└──────┬───────┘         └──────┬───────┘          │              │
       │                        │                  │              │
       │                        ▼                  │              │
       │               ┌──────────────┐            │              │
       │               │GroupCreator  │            │              │
       │               │   Screen     │            │              │
       │               └──────┬───────┘            │              │
       │                      │                    │              │
       └──────────────────────┼────────────────────┘              │
                              │                                   │
                              ▼                                   │
                    ┌──────────────────┐                          │
                    │ ChatCreatorScreen │◄─────────────────────────┘
                    └─────────┬────────┘
                              │
          ┌───────────────────┼───────────────────┐
          │                   │                   │
    1 recipient          2+ recipients       Select existing
          │                   │                group chat
          │                   │                   │
          ▼                   ▼                   │
   ┌──────────────┐  ┌──────────────┐            │
   │Create/Find   │  │GroupSetup    │            │
   │  1:1 Chat    │  │   Screen     │            │
   └──────┬───────┘  └──────┬───────┘            │
          │                 │                    │
          │                 ▼                    │
          │          ┌──────────────┐            │
          │          │ Create Group │            │
          │          │  (API/local) │            │
          │          └──────┬───────┘            │
          │                 │                    │
          └─────────────────┼────────────────────┘
                            │
                            ▼
                    ┌──────────────┐
                    │  ChatScreen  │
                    │(conversation)│
                    └──────────────┘
```

---

## Related Files

### Screens
- [ChatCreatorScreen.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/ChatCreatorScreen.kt)
- [GroupCreatorScreen.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/GroupCreatorScreen.kt)
- [GroupSetupScreen.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/GroupSetupScreen.kt)

### ViewModels
- [ChatCreatorViewModel.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/ChatCreatorViewModel.kt)
- [GroupCreatorViewModel.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/GroupCreatorViewModel.kt)
- [GroupSetupViewModel.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/GroupSetupViewModel.kt)

### Delegates
- [ContactLoadDelegate.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/delegates/ContactLoadDelegate.kt)
- [ContactSearchDelegate.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/delegates/ContactSearchDelegate.kt)
- [RecipientSelectionDelegate.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/delegates/RecipientSelectionDelegate.kt)
- [ChatCreationDelegate.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/delegates/ChatCreationDelegate.kt)

### Components
- [ChatCreatorComponents.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/ChatCreatorComponents.kt)
- [ChatCreatorFastScroller.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/ChatCreatorFastScroller.kt)
- [GroupParticipantComponents.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/GroupParticipantComponents.kt)
- [GroupContactListComponents.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/GroupContactListComponents.kt)

### Navigation
- [Screen.kt](app/src/main/kotlin/com/bothbubbles/ui/navigation/Screen.kt)
- [ChatNavigation.kt](app/src/main/kotlin/com/bothbubbles/ui/navigation/ChatNavigation.kt)
- [SetupShareNavigation.kt](app/src/main/kotlin/com/bothbubbles/ui/navigation/SetupShareNavigation.kt)

### Models
- [ChatCreatorModels.kt](app/src/main/kotlin/com/bothbubbles/ui/chatcreator/ChatCreatorModels.kt)
