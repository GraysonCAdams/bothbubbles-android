# Settings Page Reorganization Plan

## Objective
Reorganize the Settings page layout to improve logical grouping and discoverability of settings. The current layout mixes connection settings, features, and management tools. The new layout will group items by function (Connection, Appearance, Features, Privacy, Data).

## Proposed Layout Structure

### 1. Connection & Account
*Focus: Core connectivity and server configuration.*
*   **Header**: Connection Status (Existing)
*   **iMessage**: BlueBubbles server settings (Moved from *Messaging Settings*)
*   **SMS/MMS**: Local SMS messaging options (Moved from *Messaging Settings*)
*   **Sync settings**: Last synced status and options (Moved from *Connection & Data*)

### 2. Appearance & Notifications
*Focus: Visual customization, sounds, and alerts.*
*   **Notifications**: Sound, vibration, and display (Moved from *Messaging Settings*)
*   **Simple app title**: Toggle for app title style
*   **Swipe actions**: Customize conversation swipe gestures
*   **Message effects**: Animations for screen and bubble effects
*   **Message sounds**: Toggle and Sound theme picker
*   **Haptic feedback**: Vibration settings and Audio-haptic sync

### 3. Chat Features
*Focus: Enhancements to the messaging experience.*
*   **Private API**: Enable Private API & Send typing indicators (Merged from *iMessage Features*)
*   **Quick reply templates**: Saved responses (Moved from *Messaging Settings*)
*   **Auto-responder**: Greet first-time contacts (Moved from *Messaging Settings*)
*   **ETA sharing**: Share arrival time (Moved from *Messaging Settings*)
*   **Link previews**: Show rich previews for URLs (Moved from *Connection & Data*)
*   **Image quality**: Compression settings for attachments (Moved from *Appearance & behavior*)

### 4. Privacy & Organization
*Focus: Managing contacts, messages, and security.*
*   **Archived**: View archived conversations (Moved from *Quick Actions*)
*   **Blocked contacts**: Manage blocked numbers (Moved from *Quick Actions*)
*   **Spam protection**: Automatic spam detection (Moved from *Quick Actions*)
*   **Message categorization**: ML-based message sorting (Moved from *Quick Actions*)

### 5. Data & Backup
*Focus: Data management.*
*   **Export messages**: Save conversations as HTML or PDF (Moved from *Connection & Data*)

### 6. About
*   **About**: App version and info (Existing)

## Implementation Steps
1.  **Remove "Quick Actions" Card**: Distribute its items to the new *Privacy & Organization* section.
2.  **Refactor "Messaging Section"**:
    *   Move `iMessage` and `SMS/MMS` to the top *Connection & Account* section.
    *   Move `Notifications` to *Appearance & Notifications*.
    *   Move `Templates`, `Auto-responder`, `ETA Sharing` to *Chat Features*.
3.  **Refactor "iMessage Features"**:
    *   Move `Private API` and `Typing Indicators` to *Chat Features*.
4.  **Refactor "Appearance & Behavior"**:
    *   Add `Notifications` to the top of this card.
    *   Move `Image quality` to *Chat Features*.
5.  **Refactor "Connection & Data"**:
    *   Move `Sync settings` to *Connection & Account*.
    *   Move `Link previews` to *Chat Features*.
    *   Rename section to *Data & Backup* (containing only `Export` for now, or maybe merge Export into Privacy/Org if it's too small? No, keep separate for clarity).
6.  **Update Section Titles**: Ensure all new sections have appropriate headers.

## Notes
*   The `MessagingSectionHeader` (Status) should remain at the very top as it provides critical at-a-glance info.
*   Ensure `SettingsViewModel` and state are updated if any logic depends on the old grouping (unlikely, mostly UI).
