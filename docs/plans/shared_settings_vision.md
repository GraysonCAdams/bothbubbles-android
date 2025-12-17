# Shared Vision: Settings Page Reorganization

## Executive Summary
This plan synthesizes insights from the Gemini, Codex, and internal reorganization proposals. The goal is to create a layout that prioritizes **connectivity** and **usability**, grouping settings by **user intent** rather than technical implementation.

## Core Philosophy
1.  **Status First**: Connection health is the most critical information.
2.  **Logical Grouping**: Settings are grouped by what they *do* (Connect, Alert, Look, Behave, Protect), not just what they *are*.
3.  **Proximity of Dependencies**: Features like "Private API" and "Typing Indicators" are placed directly with their parent "iMessage" settings.
4.  **Progressive Disclosure**: High-frequency settings (Notifications) are prominent; maintenance tasks (Export) are lower down.

## Proposed Layout Structure

### 1. Header: Connection Status
*Unchanged. Provides immediate visual feedback on iMessage and SMS connection state.*

### 2. Connection & Server
*Focus: The "pipes" that make the app work.*
*   **iMessage**: BlueBubbles server settings.
    *   *Action*: Move **Private API** toggle and **Typing Indicators** here (either nested or immediately below). This resolves the "disconnected features" pain point.
*   **SMS/MMS**: Local messaging options.
*   **Sync Settings**: Last synced status and manual sync controls.

### 3. Notifications & Alerts
*Focus: How the app gets your attention.*
*   **Notifications**: Global settings for sound, vibration, and display.
*   **Message Sounds**: Toggle for in-app sounds.
    *   **Sound Theme**: Selector (visible when sounds enabled).

### 4. Appearance & Interaction
*Focus: Visual customization and tactile feedback.*
*   **Simple App Title**: Toggle ("Messages" vs "BothBubbles").
*   **Message Effects**: Screen and bubble animation settings.
*   **Swipe Actions**: Customizing list gestures.
*   **Haptic Feedback**: Vibration settings.
    *   **Audio-Haptic Sync**: Toggle (visible when haptics enabled).

### 5. Messaging Features
*Focus: Enhancements to the composing and sending experience.*
*   **Quick Reply Templates**: Saved responses.
*   **Auto-Responder**: Automated greetings.
*   **ETA Sharing**: Live location sharing.
*   **Link Previews**: Rich URL previews.
*   **Image Quality**: Attachment compression settings.

### 6. Privacy & Organization
*Focus: Managing the inbox and security.*
*   **Blocked Contacts**: Management of blocked numbers.
*   **Spam Protection**: Auto-detection settings.
*   **Message Categorization**: ML-based sorting.
*   **Archived**: Access to archived conversations (Moved from "Quick Actions" to a logical home).

### 7. Data & Backup
*Focus: Long-term data management.*
*   **Export Messages**: Tools for saving conversation history.

### 8. About
*Focus: App information.*
*   **About**: Version, licenses, and links.

## Comparison & Rationale

| Section | Rationale for Shared Vision |
| :--- | :--- |
| **Connection** | Combines Server, SMS, and Sync (from Gemini/Reorg) to keep all "plumbing" in one place. Moves Private API here (from Codex/Reorg) to fix dependency confusion. |
| **Notifications** | Separated from "Messaging" (Codex) and "Appearance" (Gemini) into its own high-priority section (Reorg), as it's a top user priority. |
| **Appearance** | Groups visual and tactile settings (Swipe, Haptics, Effects). Keeps Image Quality separate (in Messaging) as it affects content, not UI. |
| **Messaging** | Aggregates automation (Templates, Auto-responder) and content settings (Links, Images). |
| **Privacy/Org** | Combines "Inbox Health" concepts (Codex) with Privacy (Reorg). Moves "Archived" here as it's an organizational tool. |

## Implementation Checklist

1.  **Refactor `SettingsScreen.kt`**:
    *   [x] Create new `SettingsSectionTitle` headers for the new categories.
    *   [x] Reorder `SettingsCard` items to match the structure above.
    *   [x] Relocate `PrivateApi` and `TypingIndicators` logic to the `Connection & Server` block.
    *   [x] Move `Notifications` logic to its own block.
2.  **Update `SettingsPanel.kt`**:
    *   [x] Ensure the tablet/desktop panel view mirrors this new hierarchy (uses shared `SettingsContent`).
3.  **Cleanup**:
    *   [x] Remove the "Quick Actions" card concept entirely.
    *   [x] Ensure conditional visibility (e.g., Sound Theme) is preserved in new locations.
