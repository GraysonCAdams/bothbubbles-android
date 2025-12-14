# BothBubbles Android Auto - UX/UI Documentation

## Overview

BothBubbles provides a native Android Auto messaging experience using the **Jetpack Car App Library v1.7.0**. The app integrates with vehicle infotainment systems to deliver iMessage and SMS functionality with a driver-safety-first design philosophy.

**App Category:** Messaging
**Template Style:** TabTemplate (root), ListTemplate, MessageTemplate

---

## Navigation Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   MessagingRootScreen                       │
│  ┌─────────────────────┐  ┌─────────────────────────────┐  │
│  │     Chats Tab       │  │       Compose Tab           │  │
│  │  (ic_menu_agenda)   │  │     (ic_menu_edit)          │  │
│  └─────────────────────┘  └─────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
            │                              │
            ▼                              ▼
┌───────────────────────┐      ┌───────────────────────┐
│ ConversationDetailScreen │    │   VoiceReplyScreen    │
│  (Message History)     │      │  (Compose to contact) │
└───────────────────────┘      └───────────────────────┘
            │
            ▼
┌───────────────────────┐
│   VoiceReplyScreen    │
│  (Reply to thread)    │
└───────────────────────┘
```

**Tap Count Optimization:**
- View & reply to existing conversation: 3 taps (Chat → Conversation → Reply)
- Send new message: 2 taps (Compose Tab → Contact)

---

## Screen Details

### 1. Root Screen (MessagingRootScreen)

The entry point uses a **TabTemplate** with two tabs for improved navigation efficiency.

#### Chats Tab
- **Icon:** `ic_menu_agenda`
- **Content:** List of all active conversations sorted by priority
- **Purpose:** Access existing conversation threads

#### Compose Tab
- **Icon:** `ic_menu_edit`
- **Content:** List of recent contacts (20 max)
- **Purpose:** Start a new message to any contact

**Tab Benefits:**
- Single-tap access to compose (vs. action button in header)
- Preserves scroll position when switching tabs
- Aligns with Material Design 3 patterns

---

### 2. Conversation List (Chats Tab)

Displays all active conversations with rich metadata.

#### Sorting Priority
1. Unread + Pinned conversations
2. Unread conversations
3. Pinned conversations
4. All others by latest message date

#### Row Display
```
┌──────────────────────────────────────────────────────────┐
│  [Avatar]  Contact Name                                  │
│            "Latest message preview (50 chars max)..."    │
└──────────────────────────────────────────────────────────┘
```

**Unread Indicator:** `● Contact Name` (bullet prefix)

#### Message Preview Formatting
| Scenario | Preview Format |
|----------|----------------|
| Own message | `You: Message text...` |
| Other person (1:1) | `Message text...` |
| Group chat (other sender) | `John: Message text...` |
| Attachment only | `📎 Attachment` |
| Link with preview | `[Title] domain.com` |

#### Avatar System (Priority Order)
1. **Custom Chat Avatar** - User-set group photo
2. **Contact Photo** - From device contacts (1:1 chats)
3. **Group Collage** - Up to 4 participant photos combined
4. **Generated Avatar** - Colored circle with initials

**Avatar Sizes:** 128px for list, 64px for detail views

#### Pagination
- **Items per page:** 15 conversations
- **Load more:** "Load More" button at end of list
- **Scroll position:** Preserved during avatar loading

---

### 3. Conversation Detail Screen

Shows message history for a selected conversation.

#### Header
- **Back Button:** Returns to conversation list
- **Title:** Contact/group name

#### Message Display
```
┌──────────────────────────────────────────────────────────┐
│  Sender Name - 2:30 PM                                   │
│  "The actual message content displayed here"             │
├──────────────────────────────────────────────────────────┤
│  You - 2:28 PM                                           │
│  "Your sent message displayed here"                      │
└──────────────────────────────────────────────────────────┘
```

**Order:** Chronological (oldest first for natural reading)

#### Actions
| Action | Icon | Behavior |
|--------|------|----------|
| **Reply** | `ic_menu_send` | Opens VoiceReplyScreen |
| **Mark as Read** | `ic_menu_view` | Marks conversation read, refreshes list |

#### Pagination
- **Items per page:** 15 messages
- **Load older:** "Load Older Messages..." row at top
- **Auto-sync:** If no messages found, triggers priority sync

---

### 4. Voice Reply Screen

Voice-first message composition for driver safety.

#### Display
```
┌──────────────────────────────────────────────────────────┐
│  Your message:                                           │
│  "Dictated text appears here as you speak"               │
│                                                          │
│  [🎤 Voice Input]              [Send Message]            │
└──────────────────────────────────────────────────────────┘
```

#### Voice Input Flow
1. Tap voice button → Opens device speech recognizer
2. Speak message → Text appears in preview
3. Review/edit via voice → Tap voice again
4. Tap Send → Message sent, returns to detail screen

#### Error Handling
- **No speech recognizer:** Shows informative message, voice button disabled
- **Recognition failed:** Allows retry
- **Send failed:** Shows toast with error

**Parked-Only:** Voice input uses `ParkedOnlyOnClickListener` for safety

---

### 5. Compose Tab / New Message

Quick access to start new conversations.

#### Contact List
- **Source:** Recent 20 active conversations
- **Display:** Contact name + group indicator
- **Avatar:** Same system as conversation list

#### Group Chat Indicator
- Shows "Group" label for multi-participant chats
- Group name displayed if available

#### Flow
1. Select contact → Opens VoiceReplyScreen
2. Dictate message → Tap Send
3. Returns to Chats tab with updated list

---

## Notification Integration

Android Auto notifications provide hands-free messaging from the notification shade.

### Notification Display
```
┌──────────────────────────────────────────────────────────┐
│  [Avatar]  Contact Name                                  │
│            "New message preview..."                      │
│                                                          │
│  [Reply]  [Mark as Read]                                 │
└──────────────────────────────────────────────────────────┘
```

### Actions

#### Reply Action
- **Type:** RemoteInput with voice
- **Quick Replies:** Up to 3 pre-defined templates shown as chips
- **Smart Reply:** Contextual suggestions (disabled for OTP codes)
- **Semantic Action:** `SEMANTIC_ACTION_REPLY` for Auto compatibility

#### Mark as Read Action
- **Type:** Background action (no UI)
- **Semantic Action:** `SEMANTIC_ACTION_MARK_AS_READ`
- **Behavior:** Dismisses notification, updates unread count

#### Copy Code Action (Conditional)
- **Trigger:** Verification code detected in message
- **Behavior:** Copies code to clipboard
- **Note:** Smart reply disabled when OTP detected

### Quick Reply Templates
Templates are configured in app settings and appear as tap-to-send chips:
- "On my way!"
- "Be there in 5"
- "Can't talk right now"

---

## Visual Design

### Color Scheme
- **Primary Action:** Blue tint (#2196F3)
- **Background:** System-provided (varies by vehicle)
- **Text:** System-provided for readability

### Icons Used
| Icon | Usage |
|------|-------|
| `ic_menu_agenda` | Chats tab |
| `ic_menu_edit` | Compose tab, new message |
| `ic_menu_send` | Reply action |
| `ic_menu_view` | Mark as read |
| `ic_btn_speak_now` | Voice input button |
| `ic_launcher` | App icon, fallback avatar |

### Typography
- **Primary Text:** Contact names, message content
- **Secondary Text:** Timestamps, preview text
- **All text:** System fonts for consistency with vehicle UI

---

## Feature Support Matrix

| Feature | Status | Notes |
|---------|--------|-------|
| View conversations | ✅ | With avatars, unread indicators |
| Conversation pagination | ✅ | 15 items per page |
| Message history | ✅ | Paginated with load older |
| Voice reply | ✅ | Full speech-to-text support |
| Notification reply | ✅ | With quick templates |
| Mark as read | ✅ | Screen action + notification |
| New message compose | ✅ | Recent contacts + voice |
| Group chat support | ✅ | Multi-participant display |
| Contact avatars | ✅ | Photos, generated, collages |
| Verification codes | ✅ | Auto-detect + copy action |
| Quick reply templates | ✅ | Configurable in app |
| iMessage delivery | ✅ | Via BlueBubbles server |
| SMS/MMS delivery | ✅ | Local or server-routed |
| Search | ❌ | Not implemented |
| Reactions/tapbacks | ❌ | Text summary only |
| Image/video display | ❌ | Android Auto constraint |
| Attachments | ❌ | Shown as "📎 Attachment" |
| Message effects | ❌ | Not displayed |
| Typing indicators | ❌ | Not displayed |

---

## Performance Optimizations

### Batch Queries
- Latest messages fetched for all chats in single query
- Participants fetched in bulk, not per-conversation
- Handles (contacts) fetched by ID batch

### Caching Strategy
| Cache | Purpose |
|-------|---------|
| Sender names | Avoid repeated DB lookups |
| Participant names | Group chat display names |
| Avatar bitmaps | Prevent re-loading images |
| Conversation list | Quick tab switches |

### Avatar Loading
1. Initial render with placeholder icons
2. Background coroutine loads contact photos
3. Results cached per chat GUID
4. Screen invalidated to show loaded avatars

### Memory Management
- Coroutine scopes bound to screen lifecycle
- Automatic cleanup on screen destroy
- Volatile fields for thread-safe state access

---

## Driver Safety Features

### Parked-Only Operations
- Voice input requires parked state (Android Auto enforced)
- Uses `ParkedOnlyOnClickListener` for voice button

### Minimal Text Entry
- Voice-first design philosophy
- No keyboard input available
- Quick reply templates for common responses

### Limited Information Density
- Short message previews (50 chars)
- Simple list layouts
- Large touch targets for safe interaction

### Navigation Depth
- Maximum 3 levels deep
- Clear back navigation at each level
- Easy return to root

---

## Error States

### No Conversations
```
"No conversations"
"Start a conversation from the Compose tab"
```

### No Messages
Triggers automatic sync, shows loading state, retries after 2 seconds.

### Voice Input Unavailable
```
"Voice input is not available on this device"
```
Voice button visually disabled.

### Send Failed
Toast notification with error description, message remains in compose field.

### No Network
Standard Android Auto connectivity handling, queues messages for retry.

---

## Integration Points

### BlueBubbles Server
- iMessage delivery via server API
- Real-time message sync via Socket.IO
- Attachment metadata (not full media)

### Local SMS/MMS
- Direct send when configured as default SMS app
- Standard Android SMS intents

### Device Contacts
- Contact photos from ContentResolver
- Phone number formatting
- Display name resolution

### Notifications
- MessagingStyle notifications for Auto compatibility
- Semantic actions for proper Auto integration
- Conversation shortcuts for direct launch

---

## Technical Details

### Manifest Declaration
```xml
<service
    android:name=".services.auto.BothBubblesCarAppService"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.car.app.CarAppService" />
        <category android:name="androidx.car.app.category.MESSAGING" />
    </intent-filter>
</service>
```

### Capabilities (automotive_app_desc.xml)
```xml
<automotiveApp>
    <uses name="notification" />
    <uses name="sms" />
    <uses name="template" />
</automotiveApp>
```

### Key Files
```
services/auto/
├── BothBubblesCarAppService.kt    # Entry point + session
├── MessagingRootScreen.kt         # Root with tabs
├── ConversationDetailScreen.kt    # Message history
├── VoiceReplyScreen.kt            # Voice compose
├── ComposeMessageScreen.kt        # New message contacts
├── ConversationListContent.kt     # Chats tab builder
└── ComposeContactsContent.kt      # Compose tab builder
```

---

## Future Considerations

### Potential Enhancements
- In-app search for conversations
- Read receipts display
- Typing indicator support
- Rich attachment previews (when Auto supports)
- Reaction quick-add

### Android Auto Roadmap Dependencies
- Media message support
- Richer templates
- Improved voice input APIs
