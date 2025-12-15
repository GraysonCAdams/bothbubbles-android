# Refactor Plan: ChatViewModel Connection Delegate

**Status:** ✅ COMPLETED

**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatViewModel.kt`
**Goal:** Extract server connection, iMessage availability, and SMS fallback logic.

## Design Philosophy: The Delegate Pattern

- **State Ownership:** The Delegate owns the "Connection State" (is iMessage available? is server connected?).
- **Scoped Logic:** All logic for checking availability and handling fallbacks lives here.

## Implementation Summary

The refactoring was completed using the existing `ChatSendModeManager` delegate, which already contained all the connection/send-mode logic. The work involved:

1. **Injecting the delegate:** Added `ChatSendModeManager` to `ChatViewModel`'s constructor
2. **Wiring initialization:** Initialize delegate in a delayed coroutine block (500ms) to ensure chat data is loaded first
3. **Observing state:** Added `observeSendModeManagerState()` to forward delegate's StateFlows to `_uiState`
4. **Removing duplicates:** Removed ~300 lines of duplicate code from ChatViewModel:
   - Constants: `AVAILABILITY_CHECK_COOLDOWN`, `SERVER_STABILITY_PERIOD_MS`, `FLIP_FLOP_WINDOW_MS`, `FLIP_FLOP_THRESHOLD`
   - State variables: `serverConnectedSince`, `connectionStateChanges`, `previousConnectionState`, `hasEverConnected`, `lastAvailabilityCheck`, `smsFallbackJob`, `iMessageAvailabilityCheckJob`
   - Methods: `checkAndMaybeExitFallback()`, `observeConnectionState()`, `scheduleIMessageModeCheck()`, `checkIMessageAvailability()`, `isServerStable()`
5. **Delegating methods:** Public methods now forward to `ChatSendModeManager`:
   - `setSendMode()` → `sendModeManager.setSendMode()`
   - `tryToggleSendMode()` → `sendModeManager.tryToggleSendMode()`
   - `canToggleSendMode()` → `sendModeManager.canToggleSendModeNow()`
   - `markRevealAnimationShown()` → `sendModeManager.markRevealAnimationShown()`
   - `updateTutorialState()` → `sendModeManager.updateTutorialState()`
   - `onTutorialToggleSuccess()` → `sendModeManager.onTutorialToggleSuccess()`

## Files Changed

- `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatViewModel.kt` - Integrated delegate, removed duplicate code
- `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatSendModeManager.kt` - Existing delegate (no changes needed)

## Verification

- [x] Build compiles successfully
- [ ] App correctly switches between SMS and iMessage based on server status (manual test needed)
- [ ] No "flip-flopping" of send modes (manual test needed)

## Original Plan (for reference)

### 1. Create the Delegate Class

**Note:** This step was already completed - `ChatSendModeManager` existed with all required functionality.

### 2. Move Logic from ChatViewModel

Move the following from `ChatViewModel.kt`:

- **Fields:** `initialSendMode`, `_uiState.currentSendMode` logic (partially).
- **Logic:**
  - `AVAILABILITY_CHECK_COOLDOWN` logic.
  - `SERVER_STABILITY_PERIOD_MS` logic.
  - `FLIP_FLOP` detection logic.
  - `SocketEvent.Connect/Disconnect` handling.

### 3. Integrate into ChatViewModel

1.  Inject `ChatConnectionDelegate`.
2.  Remove moved fields/methods.
3.  Use the delegate to determine initial send mode and handle connection events.
