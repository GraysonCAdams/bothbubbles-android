# ETA Sharing Implementation Plan

## Status: Phase 1 & 2 Complete (Backend & Settings)

- [x] Service Layer (`NavigationListenerService`, `NavigationEtaParser`)
- [x] Logic Layer (`EtaSharingManager`, Debouncing)
- [x] Settings UI (`EtaSharingSettingsScreen`)
- [x] Manifest Permissions

## Phase 3: UI Wiring & UX Refinement (Current Focus)

### 1. ChatViewModel Integration

We need to expose the ETA state to the UI and handle the user interaction.

- **File:** `app/src/main/java/com/bluebubbles/messaging/ui/screens/chat/ChatViewModel.kt`
- **Tasks:**
  - Observe `EtaSharingManager.state` (or similar flow).
  - Expose `isEtaSharingAvailable` (Boolean) -> True if Nav Active AND Not currently sharing with _this_ chat.
  - Expose `isSharingEtaWithThisChat` (Boolean) -> True if currently sharing with this chat.
  - Function `startEtaSharing()` -> Calls `EtaSharingManager.startSession(chatGuid)`.
  - Function `stopEtaSharing()` -> Calls `EtaSharingManager.stopSession()`.

### 2. The "Quick Share" Banner (Safety Improvement)

Instead of burying it in the attachment picker, we add a high-visibility banner.

- **File:** `app/src/main/java/com/bluebubbles/messaging/ui/screens/chat/ChatScreen.kt`
- **Tasks:**
  - Insert a new Composable `EtaSharingBanner` below the `ChatHeader`.
  - **Condition:** Visible only if `viewModel.isEtaSharingAvailable` is true.
  - **Content:** Row with "📍 Navigation Detected" text and a "Share ETA" button.
  - **Styling:** Use a distinct color (e.g., Blue or Green) to differentiate from errors.

### 3. In-Chat Status Indicator

Users need to know if they are currently broadcasting.

- **File:** `app/src/main/java/com/bluebubbles/messaging/ui/screens/chat/ChatHeader.kt`
- **Tasks:**
  - Add a small pulsing icon or text "Sharing ETA" in the header if `viewModel.isSharingEtaWithThisChat` is true.
  - Clicking this indicator should show a dialog: "Stop sharing ETA?".

## Phase 4: Robustness & Testing

### 1. The Mock Injector (Critical for Dev)

Since we can't drive to test, we mock the parser input.

- **File:** `app/src/main/java/com/bluebubbles/messaging/services/eta/NavigationListenerService.kt`
- **Tasks:**
  - Add a static method `simulateNotification(title: String, text: String)` that calls `processNotification` directly, bypassing the system callback.
  - Add a "Test" button in `EtaSharingSettingsScreen` that calls this method with sample data:
    - Title: "15 min to Home"
    - Text: "10:45 ETA · 5 mi"

### 2. Background Sending Check

- **Task:** Verify `EtaSharingManager` uses `ActionHandler` correctly.
- **Validation:** Lock phone, trigger mock update. Ensure message appears in DB and is sent.

## Phase 5: Final Polish

- [ ] Add "Stop Sharing" button to the persistent Android notification.
