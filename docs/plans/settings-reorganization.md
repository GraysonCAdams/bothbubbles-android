# Settings Page Reorganization Plan

## Current State Analysis

The current settings layout has several organizational issues:

### Issues Identified

1. **"Quick Actions" is a grab-bag** - Mixes administrative features (Archived, Blocked) with ML features (Categorization, Spam detection)

2. **Messaging section is overloaded** - Contains 6 items mixing:
   - Core connectivity (iMessage server, SMS/MMS)
   - Communication features (Notifications)
   - Secondary features (Templates, Auto-responder, ETA sharing)

3. **iMessage Features section is disconnected** - Private API toggle and typing indicators are separated from the main iMessage settings when they logically belong together

4. **Appearance & Behavior mixes concerns** - Contains:
   - Visual settings (app title, effects, sounds)
   - Functional settings (swipe actions, link previews, haptics)
   - Image quality (which is really about data/compression)

5. **Connection & Data is sparse** - Only 3 items with no clear theme

6. **No clear priority hierarchy** - Most frequently used settings aren't necessarily at the top

---

## Proposed Reorganization

### Design Principles

1. **Most important/frequent settings first** - Server connection and notifications
2. **Group by user intent** - What is the user trying to accomplish?
3. **Progressive disclosure** - Basic settings visible, advanced settings grouped
4. **Logical dependencies** - Related settings together (e.g., Private API with typing indicators)

---

### New Layout Structure

#### 1. Connection Status Header (Unchanged)
Keep the status badges showing iMessage/SMS connection state at the top.

---

#### 2. Server & Connectivity
*"How your app connects to send messages"*

| Setting | Subtitle |
|---------|----------|
| iMessage | BlueBubbles server settings |
| └─ Private API | Toggle + info (moved from separate section) |
| └─ Typing indicators | Dependent on Private API |
| SMS/MMS | Local SMS messaging options |
| Sync settings | Last synced: {time} |

**Rationale**: Connection is foundational - users need this working first. Moving Private API here groups all server-related features together.

---

#### 3. Notifications
*"How you get alerted about new messages"*

| Setting | Subtitle |
|---------|----------|
| Notifications | Sound, vibration, and display |
| Message sounds | Toggle with preview |
| └─ Sound theme | Only when sounds enabled |

**Rationale**: Notifications are the second most important setting after connectivity. Moving sounds here creates a cohesive "alerting" section.

---

#### 4. Appearance
*"Customize how the app looks"*

| Setting | Subtitle |
|---------|----------|
| Simple app title | Shows "Messages" vs "BothBubbles" |
| Message effects | Animations for screen and bubble effects |
| Swipe actions | Customize conversation swipe gestures |
| Haptic feedback | Toggle |
| └─ Sync haptics with sounds | Only when haptics enabled |

**Rationale**: Pure visual/interaction customization grouped together.

---

#### 5. Messaging
*"How you compose and send messages"*

| Setting | Subtitle |
|---------|----------|
| Quick reply templates | Saved responses and smart suggestions |
| Auto-responder | Greet first-time iMessage contacts |
| Image quality | Compression settings for photo attachments |
| Link previews | Toggle with performance note |

**Rationale**: Features that affect how you compose and send messages.

---

#### 6. Privacy & Safety
*"Control who can message you"*

| Setting | Subtitle |
|---------|----------|
| Blocked contacts | Manage blocked numbers |
| Spam protection | Automatic spam detection settings |

**Rationale**: Security-focused settings deserve their own category. Users looking to block someone or manage spam have a clear destination.

---

#### 7. Data & Storage
*"Manage your message data"*

| Setting | Subtitle |
|---------|----------|
| Archived | Badge with count |
| Export messages | Save conversations as HTML or PDF |
| Message categorization | Sort messages into categories with ML |

**Rationale**: Features related to organizing/managing message data. Archived moves here because it's about data organization, not a "quick action."

---

#### 8. Extras
*"Additional features"*

| Setting | Subtitle |
|---------|----------|
| ETA sharing | Share arrival time while navigating |

**Rationale**: Features that don't fit core messaging but add value. This section can grow as more features are added.

---

#### 9. About (Unchanged)
*Keep at bottom*

| Setting | Subtitle |
|---------|----------|
| About | Version, licenses, and help |

---

## Visual Comparison

### Before (Current)
```
Quick Actions Card:
  ├─ Archived
  ├─ Blocked contacts
  ├─ Spam protection
  └─ Message categorization

Messaging Header (status badges)

Messaging Features Card:
  ├─ Notifications
  ├─ iMessage
  ├─ SMS/MMS
  ├─ Quick reply templates
  ├─ Auto-responder
  └─ ETA sharing

iMessage Features:
  ├─ Enable Private API (toggle)
  └─ Send typing indicators

Appearance & Behavior:
  ├─ Simple app title
  ├─ Swipe actions
  ├─ Message effects
  ├─ Image quality
  ├─ Message sounds
  │   └─ Sound theme
  ├─ Haptic feedback
  └─   └─ Sync haptics with sounds

Connection & Data:
  ├─ Sync settings
  ├─ Export messages
  └─ Link previews

About
```

### After (Proposed)
```
Connection Status Header (badges)

Server & Connectivity:
  ├─ iMessage
  │   ├─ Private API (toggle)
  │   └─ Typing indicators
  ├─ SMS/MMS
  └─ Sync settings

Notifications:
  ├─ Notifications
  └─ Message sounds
      └─ Sound theme

Appearance:
  ├─ Simple app title
  ├─ Message effects
  ├─ Swipe actions
  └─ Haptic feedback
      └─ Sync haptics with sounds

Messaging:
  ├─ Quick reply templates
  ├─ Auto-responder
  ├─ Image quality
  └─ Link previews

Privacy & Safety:
  ├─ Blocked contacts
  └─ Spam protection

Data & Storage:
  ├─ Archived
  ├─ Export messages
  └─ Message categorization

Extras:
  └─ ETA sharing

About
```

---

## Implementation Steps

### Step 1: Update SettingsScreen.kt
- [ ] Reorganize the LazyColumn items into new sections
- [ ] Move Private API toggle into iMessage section (nested or adjacent)
- [ ] Move typing indicators with Private API
- [ ] Create new section headers with appropriate titles

### Step 2: Update SettingsPanel.kt
- [ ] Mirror the same reorganization for the panel view
- [ ] Ensure navigation order matches new structure

### Step 3: Update section header component
- [ ] Add optional subtitle support for section headers
- [ ] Style section headers consistently

### Step 4: Test navigation
- [ ] Verify all settings still navigate to correct pages
- [ ] Test back navigation from nested items
- [ ] Verify conditional visibility still works (sounds → theme, haptics → sync)

### Step 5: Consider UI enhancements (optional)
- [ ] Add section descriptions/subtitles
- [ ] Consider collapsible sections for power users
- [ ] Add search functionality for settings

---

## Open Questions

1. **Should Private API be a sub-item of iMessage or a separate toggle?**
   - Option A: Nested inside iMessage settings page
   - Option B: Visible toggle in main list, grouped with iMessage
   - Recommendation: Option B for visibility, since it's a commonly toggled feature

2. **Should Message categorization be in Data & Storage or Extras?**
   - It's ML-powered but affects data organization
   - Current placement: Data & Storage (affects how messages are organized)

3. **Should we add visual section dividers or just headers?**
   - Current: Mix of cards and headers
   - Recommendation: Consistent section headers with subtle dividers

---

## Success Criteria

- [ ] Related settings are grouped together
- [ ] Most important settings (connection, notifications) are near the top
- [ ] Users can find settings by intent ("I want to block someone" → Privacy & Safety)
- [ ] No breaking changes to existing functionality
- [ ] Settings panel and full screen maintain parity
