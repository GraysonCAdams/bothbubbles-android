# Android Auto Improvements - Master Plan

## Overview

This document outlines the finalized implementation plan for the Android Auto experience in BlueBubbles. The focus is on driver safety, "glanceability," and closing feature gaps with the mobile app.

**Priorities:**

1.  **Safety:** Minimize distraction (larger text, clear icons, less reading).
2.  **Reliability:** Handle offline states gracefully.
3.  **Parity:** Bring core features (Audio, Reactions) to the car.

---

## Phase 1: Quick Wins (Safety & Clarity)

_Estimated Timeline: 1-2 Days_

### 1.1 Reaction Emoji Mapping

**Problem:** Reactions currently show as text (e.g., "Loved an image"), which takes time to read and parse while driving.
**Solution:** Parse reaction text and display the corresponding emoji at the start of the message preview.

**Technical Implementation:**

- **Location:** `MessageUtils.kt` or `AndroidAutoService.kt`
- **Logic:**
  \`\`\`kotlin
  fun parseReactionText(text: String?): String {
  if (text.isNullOrEmpty()) return ""
  val reactionMap = mapOf(
  "Loved" to "❤️",
  "Liked" to "👍",
  "Disliked" to "👎",
  "Laughed at" to "😂",
  "Emphasized" to "‼️",
  "Questioned" to "❓"
  )
      var parsed = text
      reactionMap.forEach { (verb, emoji) ->
          if (parsed.startsWith("$verb ")) {
              parsed = parsed.replaceFirst("$verb ", "$emoji ")
          }
      }
      return parsed
  }
  \`\`\`
- **UX:** "Loved an image" becomes "❤️ an image".

### 1.2 Connection Status Indicator

**Problem:** Drivers often lose signal. Sending a message while offline fails silently or hangs, causing frustration.
**Solution:** Visual indicator in the app header and a Toast warning on send attempts.

**Technical Implementation:**

- **Dependency:** Inject `SocketService` or `NetworkStateObserver` into `MessagingRootScreen`.
- **UI:** Update `Screen` title or `ActionStrip`.
  \`\`\`kotlin
  // In MessagingRootScreen.kt
  if (!isConnected) {
  templateBuilder.setTitle("BlueBubbles (Offline)")
  }
  \`\`\`
- **Action:** If user attempts to send reply while offline:
  \`\`\`kotlin
  CarToast.makeText(carContext, "Offline: Message queued", CarToast.LENGTH_LONG).show()
  \`\`\`

### 1.3 Auto Mark-as-Read

**Problem:** Reading a thread in the car leaves the notification active on the phone.
**Solution:** Automatically mark the chat as read when the user enters the `ConversationScreen`.

**Technical Implementation:**

- **Lifecycle:** Trigger in `init` block or `onGetTemplate` of `ConversationScreen`.
- **Logic:**
  \`\`\`kotlin
  if (chat.hasUnreadMessages) {
  // Fire and forget API call
  ChatRepository.markAsRead(chat.guid)
  // Update local cache immediately to clear dot
  chat.hasUnreadMessages = false
  }
  \`\`\`

---

## Phase 2: Core Experience (Usability)

_Estimated Timeline: 3-4 Days_

### 2.1 Privacy Mode (Passenger View)

**Problem:** Large dashboard screens display full message content, which is a privacy risk with passengers.
**Solution:** A toggle to hide message content, showing only "New Message" or "Contact Name".

**Technical Implementation:**

- **Settings:** Add `pref_auto_hide_content` to `SharedPreferences`.
- **UI Logic:**
  \`\`\`kotlin
  val previewText = if (prefs.getBoolean("pref_auto_hide_content", false)) {
  "New Message"
  } else {
  message.text
  }
  rowBuilder.addText(previewText)
  \`\`\`
- **Scope:** Applies only to the Android Auto UI, not phone notifications.

### 2.2 Audio Message Playback

**Problem:** Audio messages are currently unplayable placeholders.
**Solution:** Download and play audio clips using Android's `MediaPlayer` with proper Audio Focus.

**Technical Implementation:**

- **Detection:** Filter messages where `mimeType.startsWith("audio/")`.
- **UI:** Use a `Row` with a "Play" icon action.
- **Playback Logic:**
  1.  **Download:** Fetch attachment to temp file.
  2.  **Focus:** Request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`.
  3.  **Play:** Start `MediaPlayer`.
  4.  **Release:** Abandon focus on completion.

---

## Phase 3: Advanced Features

_Estimated Timeline: 1 Week_

### 3.1 Voice Search for Contacts

**Problem:** "New Message" list is limited to recent contacts. Cannot message others without picking up the phone.
**Solution:** Implement `SearchTemplate` to query the full contact database via voice.

**Technical Implementation:**

- **Template:** Switch to `SearchTemplate` on search action.
- **Query:** `ContactRepository.search(query)`.
- **Result:** Update `ItemList` with matching contacts. Tapping a result opens `ConversationScreen`.

### 3.2 TTS Readback ("Read Aloud")

**Problem:** Catching up on a conversation requires looking at the screen.
**Solution:** A "Read Aloud" button in the conversation view that reads the last N messages.

**Technical Implementation:**

- **TTS Engine:** Use Android's `TextToSpeech` API.
- **Action:** Add `Action` to `MessageTemplate` header.
- **Logic:** Concatenate last 5 messages (sender + text) and speak.

### 3.3 Infinite Scroll

**Problem:** Pagination buttons are unsafe.
**Solution:** Automatically load older messages when scrolling to the top/bottom.

**Technical Implementation:**

- **Listener:** Use `ItemVisibilityListener` (if available in library) or a "Load More" sentinel row.
- **State:** Append messages to the list and call `screenManager.push` or `invalidate` to refresh the template.

---

## Deprecated / Removed

- **Location Sharing:** Removed in favor of existing ETA sharing functionality.
