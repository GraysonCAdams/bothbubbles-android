# Settings Layout Reorganization Plan

## Goals
- Match the mental model users have when visiting Settings: check status first, then adjust how messages behave, then change how the app looks, and finally handle data/support tasks.
- Reduce cognitive load by clustering items that share intent, dependencies, or frequency.
- Keep mission-critical troubleshooting actions (connection state, spam control) near the top, and progressive-disclosure items (export, about) at the bottom.

## Current Pain Points
- Quick Actions, channel setup, and automation tools are intermixed in [app/src/main/kotlin/com/bothbubbles/ui/settings/SettingsScreen.kt](app/src/main/kotlin/com/bothbubbles/ui/settings/SettingsScreen.kt), so users jump between unrelated cards to finish a single workflow.
- Private API toggles live far away from the iMessage server row they depend on, making causality unclear.
- Notifications, sound, and haptic controls are split between two sections, even though they are typically adjusted together.
- Automation features (templates, auto-responder, ETA sharing) are buried under "Messaging" despite being more similar to productivity shortcuts than channel setup.

## Proposed Section Order & Contents

### 1. Overview & Inbox Health
Purpose: provide at-a-glance state + one-tap cleanup tools immediately.
Items:
- Messaging status header (iMessage + SMS badges)
- Archived, Blocked contacts, Spam protection, Message categorization (formerly Quick Actions)

### 2. Messaging Channels
Purpose: connect/configure how messages enter/leave the app before editing behaviors.
Items:
- Notifications (global)
- iMessage server settings (keep subtitle for server guidance)
- Private API toggle + dependent switches (typing indicators, etc.) so the dependency chain is visually obvious
- SMS/MMS settings

### 3. Automation & Shortcuts
Purpose: tools that automatically reply or speed up sending.
Items:
- Quick reply templates
- Auto-responder
- ETA sharing
- Future automation entries (placeholders for reminders, schedules, etc.)

### 4. Interaction & Feedback
Purpose: controls that change how the app responds as you compose/interact.
Items:
- Swipe actions
- Message effects
- Message sounds + Sound theme picker (conditional)
- Haptic feedback + Audio/haptic sync toggle

### 5. Appearance & Media Quality
Purpose: visual polish and media handling after interaction settings.
Items:
- Simple app title toggle
- Image quality (attachments)
- Any future theming/display options (dark mode, font scale, etc.)

### 6. Data & Privacy
Purpose: storage, sync, and export tasks grouped for users doing maintenance.
Items:
- Sync settings (retain last synced subtitle)
- Link previews toggle
- Export messages

### 7. Support & About
Purpose: keep low-frequency info at the bottom.
Items:
- About card (version, licenses, help links)
- Potential future diagnostics/support links

## Implementation Notes
- Reuse existing `SettingsSectionTitle` components but rename headings per section above to avoid ambiguity.
- Treat each section as a discrete `LazyColumn` item containing one `SettingsCard` to preserve current spacing.
- When moving Private API toggles, keep snackbar logic intact; only relocate the card under the new "Messaging Channels" card.
- Ensure `MessagingSectionHeader` stays sticky at top of the column (if desired) by turning it into a `stickyHeader` item so the status is visible as users scroll through subsequent sections.
- Consider adding `SettingsSectionTitle` descriptions for sections with mixed content (e.g., Automation) to reinforce the organizing principle.
