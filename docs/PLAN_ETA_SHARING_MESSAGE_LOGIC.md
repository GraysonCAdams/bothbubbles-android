# ETA Sharing Message Logic Improvements

## Overview

Refactor the ETA sharing system to send smarter, less spammy updates that focus on meaningful changes rather than periodic intervals.

## Current Behavior

The `EtaSharingManager` currently sends messages based on:
1. **Initial message** - When sharing starts
2. **Periodic updates** - Every 15 minutes (hardcoded `PERIODIC_UPDATE_MS`)
3. **Significant change** - When ETA changes by 5+ minutes (hardcoded `SIGNIFICANT_CHANGE_MINUTES`)
4. **Arrival threshold** - When ETA drops below 2 minutes

**Problems:**
- Periodic updates are noisy and often redundant
- Settings sliders (`etaUpdateInterval`, `etaChangeThreshold`) exist but aren't used
- Messages don't always include destination
- No distinct "arriving soon" message at ~3 minutes
- `shouldSendUpdate()` returns Boolean, requiring caller to re-evaluate why

## Proposed Behavior

Send messages **only** when meaningful:

| Trigger | When | Message Example |
|---------|------|-----------------|
| **Start** | Sharing begins | "📍 On my way to Home! ETA: 25 min (arriving ~3:45 PM)" |
| **Destination Change** | Destination changes mid-trip | "📍 Change of plans! Now heading to Work. ETA: 20 min" |
| **Significant Change** | ETA changes by X+ minutes (configurable) | "📍 ETA Update: Now 35 min to Home (was 25 min)" |
| **Arriving Soon** | ETA drops to ≤3 minutes (first time) | "📍 Almost there! Arriving at Home in ~3 min" |
| **Arrived** | Navigation ends or ETA hits 0-1 min | "📍 I've arrived at Home!" |

**Remove:** Periodic timer-based updates entirely.

---

## Stopping/Ending Sharing

### Ways Sharing Can End

| Trigger | How Detected | Message Sent |
|---------|--------------|--------------|
| **Arrived** | ETA drops to ≤1 min | "📍 I've arrived at Home!" |
| **Navigation Ended (Arrived)** | Nav notification removed + ETA was ≤3 min | "📍 I've arrived at Home!" |
| **Navigation Cancelled** | Nav notification removed + ETA was >3 min | "📍 Stopped sharing location" |
| **User Stops Manually** | User taps "Stop" in app/notification | "📍 Stopped sharing location" |

### Logic for `onNavigationStopped()`

```kotlin
fun onNavigationStopped() {
    val session = _state.value.session ?: return
    val lastEta = session.lastEtaMinutes

    // If we were close to destination (≤3 min), assume we arrived
    // Otherwise, assume trip was cancelled
    val wasNearDestination = lastEta <= ARRIVING_SOON_THRESHOLD

    if (wasNearDestination) {
        sendArrivedMessage(session)
    } else {
        sendCancelledMessage(session)  // "Stopped sharing location"
    }

    stopSharing(sendFinalMessage = false)
}

private fun buildCancelledMessage(): String {
    return "📍 Stopped sharing location"
}
```

### Manual Stop (User-Initiated)

The user can stop sharing via:
1. **In-app banner** - "Stop" button on the EtaSharingBanner
2. **System notification** - "Stop Sharing" action button
3. **Android Auto** - "Stop Sharing" action on car display

All of these trigger `EtaSharingReceiver.ACTION_STOP_SHARING` → `stopSharing(sendFinalMessage = true)` → sends "Stopped sharing location" message.

---

## Implementation Plan

### Phase 1: Data & Models (Foundation)

#### 1.1 Modify `EtaModels.kt`

**Location:** `app/src/main/kotlin/com/bothbubbles/services/eta/EtaModels.kt`

```kotlin
/**
 * Types of ETA messages that can be sent
 */
enum class EtaMessageType {
    INITIAL,            // "On my way to..."
    DESTINATION_CHANGE, // "Change of plans! Now heading to..."
    CHANGE,             // "ETA Update: Now X min..."
    ARRIVING_SOON,      // "Almost there!..."
    ARRIVED             // "I've arrived..."
}

data class EtaSharingSession(
    val recipientGuid: String,
    val recipientDisplayName: String,
    val destination: String?,
    val startedAt: Long = System.currentTimeMillis(),
    val lastSentTime: Long = 0,
    val lastEtaMinutes: Int = 0,
    val updateCount: Int = 0,
    val lastMessageType: EtaMessageType = EtaMessageType.INITIAL  // NEW
)
```

#### 1.2 Update `SettingsDataStore.kt` (Optional)

- Mark `etaUpdateInterval` as deprecated (no longer used)
- Ensure `etaChangeThreshold` is exposed and accessible

---

### Phase 2: UI Updates (User Facing)

#### 2.1 Modify `EtaSharingSettingsScreen.kt`

**Location:** `app/src/main/kotlin/com/bothbubbles/ui/settings/eta/EtaSharingSettingsScreen.kt`

**Remove** the "Update Frequency" slider:
```kotlin
// DELETE this entire section:
SettingsSection(title = "Update Frequency") {
    IntervalSlider(
        label = "Send updates every ${uiState.updateIntervalMinutes} minutes",
        ...
    )
}
```

**Update** the "Change Sensitivity" slider:
```kotlin
SettingsSection(title = "Update Sensitivity") {
    IntervalSlider(
        label = "Notify when ETA changes by ${uiState.changeThresholdMinutes}+ minutes",
        description = "Updates are sent when your arrival time changes significantly, " +
            "when you're about to arrive (~3 min away), or when you've arrived.",
        value = uiState.changeThresholdMinutes,
        onValueChange = onChangeThresholdChanged,
        valueRange = 2f..15f,
        steps = 12
    )
}
```

---

### Phase 3: Core Logic Implementation (The Meat)

#### 3.1 Refactor `EtaSharingManager.kt`

**Location:** `app/src/main/kotlin/com/bothbubbles/services/eta/EtaSharingManager.kt`

##### Constants

```kotlin
companion object {
    private const val TAG = "EtaSharingManager"

    // Thresholds
    const val ARRIVING_SOON_THRESHOLD = 3   // Send "arriving soon" at 3 min
    const val ARRIVED_THRESHOLD = 1         // Consider "arrived" at 0-1 min
    const val DEFAULT_CHANGE_THRESHOLD = 5  // Default if not configured

    // Terminal state protection
    const val TERMINAL_STATE_COOLDOWN_MS = 30 * 60 * 1000L  // 30 min
}
```

##### Core Logic: `determineUpdateType()`

Replace `shouldSendUpdate(): Boolean` with `determineUpdateType(): EtaMessageType?`:

```kotlin
/**
 * Determine what type of update message to send, if any.
 * Returns null if no update should be sent.
 */
private suspend fun determineUpdateType(
    session: EtaSharingSession,
    eta: ParsedEtaData
): EtaMessageType? {
    val newEtaMinutes = eta.etaMinutes
    val etaDelta = abs(newEtaMinutes - session.lastEtaMinutes)
    val changeThreshold = settingsDataStore.etaChangeThreshold.first()

    // Priority 1: Check for arrival (terminal state)
    if (newEtaMinutes <= ARRIVED_THRESHOLD && session.lastMessageType != EtaMessageType.ARRIVED) {
        Log.d(TAG, "Update type: ARRIVED (ETA: $newEtaMinutes min)")
        return EtaMessageType.ARRIVED
    }

    // Priority 2: Check for destination change
    if (eta.destination != null && session.destination != null &&
        !isSameDestination(eta.destination, session.destination)
    ) {
        Log.d(TAG, "Update type: DESTINATION_CHANGE (${session.destination} → ${eta.destination})")
        return EtaMessageType.DESTINATION_CHANGE
    }

    // Priority 3: Check for "arriving soon" (only send once)
    if (newEtaMinutes <= ARRIVING_SOON_THRESHOLD &&
        session.lastEtaMinutes > ARRIVING_SOON_THRESHOLD &&
        session.lastMessageType != EtaMessageType.ARRIVING_SOON
    ) {
        Log.d(TAG, "Update type: ARRIVING_SOON (ETA dropped to $newEtaMinutes min)")
        return EtaMessageType.ARRIVING_SOON
    }

    // Priority 4: Significant ETA change (user-configurable threshold)
    if (etaDelta >= changeThreshold) {
        Log.d(TAG, "Update type: CHANGE (delta: $etaDelta min, threshold: $changeThreshold)")
        return EtaMessageType.CHANGE
    }

    // No meaningful change
    return null
}
```

##### Message Builders

```kotlin
private fun buildInitialMessage(eta: ParsedEtaData): String {
    val dest = eta.destination ?: "destination"
    return buildString {
        append("📍 On my way to $dest!")
        append("\nETA: ${formatEtaTime(eta.etaMinutes)}")
        eta.arrivalTimeText?.let { append(" (arriving ~$it)") }
    }
}

private fun buildDestinationChangeMessage(eta: ParsedEtaData): String {
    val dest = eta.destination ?: "new destination"
    return buildString {
        append("📍 Change of plans!")
        append("\nNow heading to $dest")
        append("\nETA: ${formatEtaTime(eta.etaMinutes)}")
        eta.arrivalTimeText?.let { append(" (arriving ~$it)") }
    }
}

private fun buildChangeMessage(eta: ParsedEtaData, previousEta: Int): String {
    val dest = eta.destination ?: "destination"
    return buildString {
        append("📍 ETA Update")
        append("\nNow ${formatEtaTime(eta.etaMinutes)} to $dest")
        if (previousEta > 0) {
            append(" (was ${formatEtaTime(previousEta)})")
        }
    }
}

private fun buildArrivingSoonMessage(eta: ParsedEtaData): String {
    val dest = eta.destination?.let { " at $it" } ?: ""
    return "📍 Almost there! Arriving$dest in ~${eta.etaMinutes} min"
}

private fun buildArrivedMessage(destination: String?): String {
    val dest = destination?.let { " at $it" } ?: ""
    return "📍 I've arrived$dest!"
}
```

##### Main Update Handler

```kotlin
/**
 * Handle a new ETA update from navigation
 */
fun onEtaUpdate(etaData: ParsedEtaData) {
    val currentState = _state.value
    _state.value = currentState.copy(
        currentEta = etaData,
        isNavigationActive = true
    )
    _isNavigationActive.value = true

    val session = currentState.session ?: return
    if (!currentState.isSharing) return

    // Terminal state check
    if (isInTerminalState(etaData)) {
        Log.d(TAG, "Ignoring update - in terminal state")
        return
    }

    // Determine what type of message to send (if any)
    scope.launch {
        val messageType = determineUpdateType(session, etaData.etaMinutes)

        if (messageType != null) {
            sendMessageForType(session, etaData, messageType)
        }
    }
}

private suspend fun sendMessageForType(
    session: EtaSharingSession,
    eta: ParsedEtaData,
    messageType: EtaMessageType
) {
    val message = when (messageType) {
        EtaMessageType.INITIAL -> buildInitialMessage(eta)
        EtaMessageType.DESTINATION_CHANGE -> buildDestinationChangeMessage(eta)
        EtaMessageType.CHANGE -> buildChangeMessage(eta, session.lastEtaMinutes)
        EtaMessageType.ARRIVING_SOON -> buildArrivingSoonMessage(eta)
        EtaMessageType.ARRIVED -> buildArrivedMessage(eta.destination ?: session.destination)
    }

    Log.d(TAG, "Sending $messageType message: $message")

    pendingMessageRepository.queueMessage(
        chatGuid = session.recipientGuid,
        text = message
    ).onSuccess {
        // Update session state
        val updatedSession = session.copy(
            lastSentTime = System.currentTimeMillis(),
            lastEtaMinutes = eta.etaMinutes,
            updateCount = session.updateCount + 1,
            destination = eta.destination ?: session.destination,
            lastMessageType = messageType  // Track what we sent
        )
        _state.value = _state.value.copy(session = updatedSession)

        // Handle terminal state for ARRIVED
        if (messageType == EtaMessageType.ARRIVED) {
            enterTerminalState(eta.destination)
            stopSharing(sendFinalMessage = false)
        }
    }.onFailure { e ->
        Log.e(TAG, "Failed to send $messageType message", e)
    }
}
```

---

### Phase 4: Cleanup & Verification

#### 4.1 Remove Dead Code

- Delete `PERIODIC_UPDATE_MS` constant
- Remove any `Timer` or `Handler` logic for periodic intervals
- Remove (or deprecate) `etaUpdateInterval` from settings if not needed elsewhere

#### 4.2 Test Scenarios

| Scenario | ETA Sequence | Expected Messages |
|----------|--------------|-------------------|
| Normal trip | 20 → 15 → 10 → 5 → 3 → 1 → 0 | Initial, Change (at 15), Arriving Soon (at 3), Arrived |
| Traffic delay | 5 → 15 | Initial, Change ("Now 15 min (was 5 min)") |
| Small fluctuation | 10 → 9 → 8 → 7 | Initial only (no change meets threshold) |
| Rapid arrival | 4 → 2 → 0 | Initial, Arriving Soon (at 2), Arrived |
| Destination change | Home (20 min) → Work (15 min) | Initial, Destination Change ("Now heading to Work") |
| User cancels trip early | 20 → 18 → [nav cancelled] | Initial, "Stopped sharing location" |
| User cancels near arrival | 5 → 3 → [nav cancelled] | Initial, Arriving Soon, "I've arrived" (assumed arrived) |
| User stops manually | Any ETA → [user taps Stop] | Whatever was sent + "Stopped sharing location" |
| Restart nav (same dest) | Trip ends, starts again within 30 min | No duplicate "Arrived" (terminal state) |

---

## Message Flow Diagram

```
Navigation Starts
       │
       ▼
┌─────────────────────┐
│ startSharing()      │
│ Send INITIAL msg    │ ──► "📍 On my way to Home! ETA: 25 min"
└─────────────────────┘
       │
       ▼
┌─────────────────────┐
│ onEtaUpdate()       │◄─── Navigation app updates ETA
│ called repeatedly   │
└─────────────────────┘
       │
       ▼
┌─────────────────────┐
│ determineUpdateType │
└─────────────────────┘
       │
       ├─► ARRIVED (ETA ≤ 1)?
       │   └──► "📍 I've arrived at Home!" → Enter terminal state, stop sharing
       │
       ├─► ARRIVING_SOON (ETA ≤ 3, first time)?
       │   └──► "📍 Almost there! Arriving at Home in ~3 min"
       │
       ├─► CHANGE (delta ≥ threshold)?
       │   └──► "📍 ETA Update: Now 35 min to Home (was 25 min)"
       │
       └─► null (no significant change)
           └──► Do nothing (no spam!)
```

---

## Files Changed Summary

| File | Phase | Changes |
|------|-------|---------|
| `EtaModels.kt` | 1 | Add `EtaMessageType` enum, add `lastMessageType` to session |
| `SettingsDataStore.kt` | 1 | (Optional) Deprecate `etaUpdateInterval` |
| `EtaSharingSettingsScreen.kt` | 2 | Remove frequency slider, update sensitivity description |
| `EtaSharingManager.kt` | 3 | Replace `shouldSendUpdate` with `determineUpdateType`, add message builders, update main loop |

**Estimated:** ~200-250 lines changed
