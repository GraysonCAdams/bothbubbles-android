# ETA Sharing UX/UI Improvements

## Overview

This document outlines the requirements and technical context for improving the ETA Navigation Sharing feature in BlueBubbles. The goal is to enhance the visual appeal of the in-app banner and extend functionality to system notifications, specifically targeting Android Auto users.

## 1. In-App Banner Redesign

### Current State

- **File**: `app/src/main/kotlin/com/bothbubbles/ui/chat/components/EtaSharingBanner.kt`
- **Design**:
  - Uses standard Material 3 `primaryContainer` (Offer mode) and `tertiaryContainer` (Active mode).
  - Simple `Row` layout with an Icon, Text, and Button.
  - Functional but basic.

### Design Requirements

- **Visual Appeal**: The banner needs to be stylistically more appealing.
  - Consider a more distinct "card" look, possibly floating with a shadow to separate it from the chat content.
  - Use of gradients or more vibrant colors to denote the "Navigation" state.
- **Animation**:
  - Implement a pulsing effect for the navigation icon when in "Active" mode to indicate live updating.
  - Smooth transitions between "Offer" and "Active" states.
- **Typography**: Improve hierarchy between the "Navigation Detected" label and the actual ETA time.
- **Interaction**: Ensure the "Share ETA" button is large enough for easy tapping (min 48dp touch target) as this is often used in a vehicle context.

## 2. "Start Sharing" Notification

### Requirement

Deliver a notification to share ETA moments after navigation starts.

### Technical Context

- **Service**: `NavigationListenerService.kt` currently detects navigation apps (Maps, Waze).
- **Trigger**: When `activeNavigationNotifications` detects a new navigation session.
- **Behavior**:
  1. Detect navigation start.
  2. Wait a brief period (e.g., 15-30 seconds) to ensure navigation is stable and not just a quick check.
  3. Post a notification: "Navigation Detected".
  4. **Action**: Provide a "Share ETA" action button.

### UX Challenge: Recipient Selection

- The background service does not inherently know _who_ to share with.
- **Proposed Solution**:
  - If a chat was recently active, suggest that contact: "Share ETA with [Name]".
  - If no recent context, the notification might need to open a quick selection dialog or the last opened chat.
  - _Note_: For the initial implementation, we might focus on the scenario where the user has a relevant active chat or a "Favorites" list.

## 3. Android Auto Integration

### Requirement

The "Start Sharing" notification must be interactable within Android Auto.

### Implementation Details

- **CarExtender**: Use `NotificationCompat.CarExtender` to ensure the notification appears correctly on the car head unit.
- **Actions**: The "Share ETA" action must be a `PendingIntent` that triggers a broadcast or service command to `EtaSharingManager`.
- **Feedback**: Tapping the action should provide immediate feedback (e.g., notification updates to "Sharing ETA with [Name]").

## Action Items for Developers

1. **Modify `NavigationListenerService.kt`**:
   - Add logic to post a "Prompt" notification upon navigation detection.
   - Implement `CarExtender` for Android Auto compatibility.
2. **Update `EtaSharingBanner.kt`**:
   - Apply new styling and animations.
3. **Update `EtaSharingManager.kt`**:
   - Handle the "Start Sharing" intent from the notification.
