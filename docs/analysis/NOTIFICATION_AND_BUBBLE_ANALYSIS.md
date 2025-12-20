# Notification & Chat Bubble System Analysis

**Date**: December 20, 2024
**Purpose**: SWE review document for debugging auto-opening chat bubbles

---

## Executive Summary

This document provides a comprehensive analysis of how BothBubbles handles notifications and Android Conversation Bubbles. The user reports that **chat bubbles keep opening automatically when notifications arrive**. Based on code analysis, the app explicitly sets `setAutoExpandBubble(false)`, meaning this behavior is likely caused by **Android system settings**, not application code.

### Key Finding

The app correctly configures bubbles to NOT auto-expand:
```kotlin
// BubbleMetadataHelper.kt:255-257
.setAutoExpandBubble(false)       // ← Bubbles should NOT auto-expand
.setSuppressNotification(false)   // ← Notification still shows alongside bubble
```

**Most likely cause**: The user has Android system bubble settings configured to auto-expand bubbles for this app or for all apps.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Message Arrival → Notification Flow](#2-message-arrival--notification-flow)
3. [Bubble Configuration Details](#3-bubble-configuration-details)
4. [Notification Suppression Logic](#4-notification-suppression-logic)
5. [Active Conversation Tracking](#5-active-conversation-tracking)
6. [Key Files Reference](#6-key-files-reference)
7. [Potential Causes of Auto-Opening Bubbles](#7-potential-causes-of-auto-opening-bubbles)
8. [Debugging Recommendations](#8-debugging-recommendations)

---

## 1. Architecture Overview

### Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         MESSAGE ENTRY POINTS                              │
├──────────────────┬──────────────────┬────────────────────────────────────┤
│  Socket.IO Push  │   FCM Push       │   Local SMS/MMS                    │
│  (Real-time)     │   (Background)   │   (Android Telephony)              │
└────────┬─────────┴────────┬─────────┴──────────────┬─────────────────────┘
         │                  │                        │
         ▼                  ▼                        ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                      MESSAGE HANDLERS                                     │
├──────────────────────────────────────────────────────────────────────────┤
│  MessageEventHandler    FcmMessageHandler    SmsBroadcastReceiver        │
│  (socket/handlers/)     (fcm/)               (sms/)                      │
└────────────────────────────────┬─────────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    NOTIFICATION DECISION TREE                             │
├──────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────────────────┐     │
│  │ Skip notification if ANY of:                                     │     │
│  │  • Message is from me (isFromMe = true)                         │     │
│  │  • Already notified (MessageDeduplicator)                       │     │
│  │  • User viewing this chat (ActiveConversationManager)           │     │
│  │  • Notifications disabled for chat (chat.notificationsEnabled)  │     │
│  │  • Chat is snoozed (chat.isSnoozed)                            │     │
│  │  • Message detected as spam (SpamRepository)                    │     │
│  │  • No POST_NOTIFICATIONS permission (Android 13+)               │     │
│  └─────────────────────────────────────────────────────────────────┘     │
└────────────────────────────────┬─────────────────────────────────────────┘
                                 │
                                 ▼ (if all checks pass)
┌──────────────────────────────────────────────────────────────────────────┐
│                    NOTIFICATION CREATION                                  │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  NotificationService.showMessageNotification(MessageNotificationParams)  │
│           │                                                              │
│           ▼                                                              │
│  NotificationBuilder.buildMessageNotification()                          │
│           │                                                              │
│           ├─► Create MessagingStyle notification                         │
│           │                                                              │
│           ├─► BubbleMetadataHelper.shouldShowBubble()                    │
│           │        │                                                     │
│           │        └─► Check filter mode: "all", "selected",             │
│           │            "favorites", "none"                               │
│           │                                                              │
│           ├─► BubbleMetadataHelper.createBubbleMetadata()                │
│           │        │                                                     │
│           │        └─► setAutoExpandBubble(false) ← KEY SETTING          │
│           │            setSuppressNotification(false)                    │
│           │            setDesiredHeight(600)                             │
│           │                                                              │
│           └─► NotificationManager.notify()                               │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### Key Classes

| Class | File | Responsibility |
|-------|------|----------------|
| `NotificationService` | [NotificationService.kt](../../app/src/main/kotlin/com/bothbubbles/services/notifications/NotificationService.kt) | Main entry point for showing notifications |
| `NotificationBuilder` | [NotificationBuilder.kt](../../app/src/main/kotlin/com/bothbubbles/services/notifications/NotificationBuilder.kt) | Constructs notification objects |
| `BubbleMetadataHelper` | [BubbleMetadataHelper.kt](../../app/src/main/kotlin/com/bothbubbles/services/notifications/BubbleMetadataHelper.kt) | Creates bubble metadata and shortcuts |
| `NotificationChannelManager` | [NotificationChannelManager.kt](../../app/src/main/kotlin/com/bothbubbles/services/notifications/NotificationChannelManager.kt) | Creates notification channels |
| `BubbleActivity` | [BubbleActivity.kt](../../app/src/main/kotlin/com/bothbubbles/ui/bubble/BubbleActivity.kt) | Activity displayed inside bubbles |
| `ActiveConversationManager` | [ActiveConversationManager.kt](../../app/src/main/kotlin/com/bothbubbles/services/ActiveConversationManager.kt) | Tracks which chat user is viewing |

---

## 2. Message Arrival → Notification Flow

### 2.1 Socket.IO Path (Real-time)

```
SocketService receives "new-message" event
    │
    ▼
SocketEventHandler.handleEvent()
    │ (Line 160 of SocketEventHandler.kt)
    ▼
MessageEventHandler.handleNewMessage()
    │ (Line 84 of MessageEventHandler.kt)
    │
    ├─► Save message to database
    │
    ├─► Check suppression conditions (Lines 99-135):
    │   • isFromMe → SKIP
    │   • MessageDeduplicator.shouldNotifyForMessage() → if false, SKIP
    │   • ActiveConversationManager.isConversationActive() → if true, SKIP
    │   • chat.notificationsEnabled == false → SKIP
    │   • chat.isSnoozed → SKIP
    │   • SpamRepository.evaluateAndMarkSpam() → if spam, SKIP
    │
    ├─► Resolve sender info (Lines 152-199)
    │
    └─► NotificationService.showMessageNotification() (Line 201-217)
```

### 2.2 FCM Path (Background Push)

```
FirebaseMessagingService receives push
    │
    ▼
FcmMessageHandler.handleMessage()
    │ (Line 82 of FcmMessageHandler.kt)
    ▼
FcmMessageHandler.handleNewMessage()
    │ (Line 113)
    │
    ├─► Parse FCM JSON payload
    │
    ├─► Same suppression checks as Socket.IO path (Lines 169-208)
    │
    ├─► Resolve sender name (Lines 210-227)
    │
    └─► NotificationService.showMessageNotification() (Line 248-263)
```

### 2.3 Local SMS/MMS Path

```
Android Telephony broadcasts SMS_DELIVER_ACTION
    │
    ▼
SmsBroadcastReceiver.handleSmsDeliver()
    │ (Line 112 of SmsBroadcastReceiver.kt)
    ▼
SmsBroadcastReceiver.processIncomingSms()
    │ (Line 143)
    │
    ├─► Create/get chat, save message
    │
    ├─► Same suppression checks (Lines 218-242)
    │
    └─► NotificationService.showMessageNotification() (Line 253-264)
```

---

## 3. Bubble Configuration Details

### 3.1 BubbleMetadata Creation

**File**: [BubbleMetadataHelper.kt:250-258](../../app/src/main/kotlin/com/bothbubbles/services/notifications/BubbleMetadataHelper.kt#L250-L258)

```kotlin
val metadata = NotificationCompat.BubbleMetadata.Builder(
    bubblePendingIntent,  // Intent to BubbleActivity
    bubbleIcon            // Adaptive icon with avatar
)
    .setDesiredHeight(600)         // Bubble window height: 600dp
    .setAutoExpandBubble(false)    // ← CRITICAL: Does NOT auto-expand
    .setSuppressNotification(false) // ← Notification still shows
    .build()
```

#### What These Settings Mean

| Setting | Value | Effect |
|---------|-------|--------|
| `setAutoExpandBubble` | `false` | Bubble appears in collapsed state (floating icon) when notification arrives. User must tap to expand. |
| `setSuppressNotification` | `false` | Both notification AND bubble appear. The notification shade shows the message, and the bubble icon appears. |
| `setDesiredHeight` | `600` | When expanded, bubble window is 600dp tall. |

### 3.2 Notification Channel Configuration

**File**: [NotificationChannelManager.kt:60-74](../../app/src/main/kotlin/com/bothbubbles/services/notifications/NotificationChannelManager.kt#L60-L74)

```kotlin
val messagesChannel = NotificationChannel(
    CHANNEL_MESSAGES,
    "Messages",
    NotificationManager.IMPORTANCE_HIGH  // ← Required for bubbles
).apply {
    enableVibration(true)
    enableLights(true)
    setShowBadge(true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        setAllowBubbles(true)  // ← Enables bubbles at channel level
    }
}
```

### 3.3 BubbleActivity Manifest Configuration

**File**: [AndroidManifest.xml:194-201](../../app/src/main/AndroidManifest.xml#L194-L201)

```xml
<activity
    android:name=".ui.bubble.BubbleActivity"
    android:exported="false"
    android:allowEmbedded="true"        <!-- Required for bubbles -->
    android:documentLaunchMode="always" <!-- Each bubble is separate task -->
    android:resizeableActivity="true"   <!-- Bubbles are resizable -->
    android:windowSoftInputMode="adjustResize"
    android:theme="@style/Theme.BothBubbles" />
```

### 3.4 Bubble Filter Modes

**File**: [SettingsDataStore.kt](../../app/src/main/kotlin/com/bothbubbles/data/local/prefs/SettingsDataStore.kt)

Users can configure which conversations show as bubbles:

| Mode | Behavior |
|------|----------|
| `"all"` (default) | Bubbles for all conversations |
| `"selected"` | Only for conversations in `selectedBubbleChats` set |
| `"favorites"` | Only for starred Android contacts |
| `"none"` | Bubbles disabled entirely |

**File**: [BubbleMetadataHelper.kt:164-173](../../app/src/main/kotlin/com/bothbubbles/services/notifications/BubbleMetadataHelper.kt#L164-L173)

```kotlin
fun shouldShowBubble(chatGuid: String, senderAddress: String?): Boolean {
    return when (cachedBubbleFilterMode) {
        "none" -> false
        "all" -> true
        "selected" -> cachedSelectedBubbleChats.contains(chatGuid)
        "favorites" -> true  // Let system handle favorite lookup
        else -> true
    }
}
```

### 3.5 Shortcut Requirement for Bubbles

Bubbles require a dynamic shortcut with `setIsConversation()` and `setLongLived(true)`. Without this, Android will not display the bubble.

**File**: [BubbleMetadataHelper.kt:138-148](../../app/src/main/kotlin/com/bothbubbles/services/notifications/BubbleMetadataHelper.kt#L138-L148)

```kotlin
val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
    .setShortLabel(chatTitle)
    .setLongLabel(chatTitle)
    .setIcon(avatarIcon)
    .setIntent(intent)
    .setLongLived(true)        // Persists across reboots
    .setIsConversation()       // Marks as conversation for bubbles
    .setLocusId(locusId)       // Links to notification
    .setPerson(person)         // Required for conversation shortcuts
    .build()

ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
```

---

## 4. Notification Suppression Logic

### 4.1 Suppression Decision Tree

When a message arrives, these checks determine if a notification should be shown:

```
┌─────────────────────────────────────────────────────────────────────┐
│ CHECK: Is message from me?                                          │
│ Location: MessageEventHandler.kt:99                                 │
│ Skip if: isFromMe = true                                           │
└───────────────────────────────────────┬─────────────────────────────┘
                                        │ (not from me)
                                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│ CHECK: Already notified for this message?                           │
│ Location: MessageEventHandler.kt:101-104                            │
│ Skip if: MessageDeduplicator.shouldNotifyForMessage() = false       │
│ Why: Prevents duplicate notifications when message arrives via      │
│      both FCM and Socket.IO                                         │
└───────────────────────────────────────┬─────────────────────────────┘
                                        │ (not a duplicate)
                                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│ CHECK: Is user viewing this chat?                                   │
│ Location: MessageEventHandler.kt:107                                │
│ Skip if: ActiveConversationManager.isConversationActive() = true    │
│ Why: No notification needed if user is already in the chat          │
└───────────────────────────────────────┬─────────────────────────────┘
                                        │ (not viewing)
                                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│ CHECK: Are notifications enabled for this chat?                     │
│ Location: MessageEventHandler.kt:119-122                            │
│ Skip if: chat.notificationsEnabled == false                         │
│ Why: User has muted this specific conversation                      │
└───────────────────────────────────────┬─────────────────────────────┘
                                        │ (enabled)
                                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│ CHECK: Is chat snoozed?                                             │
│ Location: MessageEventHandler.kt:125-128                            │
│ Skip if: chat.isSnoozed == true                                     │
│ Why: User has temporarily snoozed this conversation                 │
└───────────────────────────────────────┬─────────────────────────────┘
                                        │ (not snoozed)
                                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│ CHECK: Is message spam?                                             │
│ Location: MessageEventHandler.kt:131-135                            │
│ Skip if: SpamRepository.evaluateAndMarkSpam() = spam                │
│ Why: Don't notify for suspected spam                                │
└───────────────────────────────────────┬─────────────────────────────┘
                                        │ (not spam)
                                        ▼
                              ✅ SHOW NOTIFICATION
```

### 4.2 Deduplication Mechanism

**File**: MessageDeduplicator.kt

When a message can arrive via multiple paths (Socket.IO + FCM), the deduplicator prevents duplicate notifications:

```
Message 1 arrives via FCM (guid="abc")
    │
    ▼
shouldNotifyForMessage("abc")
    ├─► Check in-memory cache (LinkedHashSet, max 200) → MISS
    ├─► Check Room database (SeenMessageDao, 24h retention) → MISS
    └─► Returns TRUE → markAsHandled("abc") → NOTIFY

Message 2 arrives via Socket.IO (guid="abc")
    │
    ▼
shouldNotifyForMessage("abc")
    ├─► Check in-memory cache → HIT
    └─► Returns FALSE → SKIP notification
```

---

## 5. Active Conversation Tracking

### 5.1 How It Works

**File**: [ActiveConversationManager.kt](../../app/src/main/kotlin/com/bothbubbles/services/ActiveConversationManager.kt)

```kotlin
@Singleton
class ActiveConversationManager {
    // Atomic state to prevent race conditions
    @Volatile
    private var activeConversation: ActiveConversationState? = null

    data class ActiveConversationState(
        val chatGuid: String,
        val mergedGuids: Set<String>  // Includes iMessage + SMS variants
    )

    fun setActiveConversation(chatGuid: String, mergedGuids: Set<String>) {
        // Single atomic write
        activeConversation = ActiveConversationState(chatGuid, mergedGuids + chatGuid)
    }

    fun isConversationActive(chatGuid: String): Boolean {
        val current = activeConversation ?: return false
        return current.chatGuid == chatGuid || current.mergedGuids.contains(chatGuid)
    }
}
```

### 5.2 Lifecycle Integration

The active conversation is automatically cleared when the app goes to background:

```kotlin
// ActiveConversationManager.kt:39-42
override fun onStop(owner: LifecycleOwner) {
    // App went to background - clear active conversation so notifications show
    clearActiveConversation()
}
```

### 5.3 Callers

| Location | Action |
|----------|--------|
| `ChatViewModel.initialize()` | Sets active when entering chat (with merged GUIDs for unified iMessage/SMS) |
| `ChatViewModel.onChatLeave()` | Clears when leaving chat |
| `BubbleActivity.onCreate()` / `onResume()` | Sets active when bubble is visible |
| `BubbleActivity.onPause()` / `onDestroy()` | Clears when bubble is hidden |
| `ActiveConversationManager.onStop()` | Auto-clears when app backgrounds |

---

## 6. Key Files Reference

### Notification System

| File | Lines | Purpose |
|------|-------|---------|
| [NotificationService.kt](../../app/src/main/kotlin/com/bothbubbles/services/notifications/NotificationService.kt) | 30-53 | Entry point for showing notifications |
| [NotificationBuilder.kt](../../app/src/main/kotlin/com/bothbubbles/services/notifications/NotificationBuilder.kt) | 136-388 | Builds message notifications |
| [NotificationBuilder.kt](../../app/src/main/kotlin/com/bothbubbles/services/notifications/NotificationBuilder.kt) | 300-328 | Creates bubble metadata for notification |
| [BubbleMetadataHelper.kt](../../app/src/main/kotlin/com/bothbubbles/services/notifications/BubbleMetadataHelper.kt) | 190-262 | Creates BubbleMetadata object |
| [BubbleMetadataHelper.kt](../../app/src/main/kotlin/com/bothbubbles/services/notifications/BubbleMetadataHelper.kt) | 164-173 | Bubble filtering logic |
| [NotificationChannelManager.kt](../../app/src/main/kotlin/com/bothbubbles/services/notifications/NotificationChannelManager.kt) | 60-74 | Messages channel with bubble support |

### Message Handlers

| File | Lines | Purpose |
|------|-------|---------|
| [MessageEventHandler.kt](../../app/src/main/kotlin/com/bothbubbles/services/socket/handlers/MessageEventHandler.kt) | 84-233 | Socket.IO message handling |
| [FcmMessageHandler.kt](../../app/src/main/kotlin/com/bothbubbles/services/fcm/FcmMessageHandler.kt) | 82-270 | FCM push handling |
| [SmsBroadcastReceiver.kt](../../app/src/main/kotlin/com/bothbubbles/services/sms/SmsBroadcastReceiver.kt) | 112-268 | Local SMS/MMS handling |

### Bubble Activity

| File | Lines | Purpose |
|------|-------|---------|
| [BubbleActivity.kt](../../app/src/main/kotlin/com/bothbubbles/ui/bubble/BubbleActivity.kt) | 72-99 | Activity onCreate, sets active conversation |
| [BubbleActivity.kt](../../app/src/main/kotlin/com/bothbubbles/ui/bubble/BubbleActivity.kt) | 101-113 | onResume/onPause lifecycle |
| [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) | 194-201 | Bubble activity declaration |

### Active Conversation

| File | Lines | Purpose |
|------|-------|---------|
| [ActiveConversationManager.kt](../../app/src/main/kotlin/com/bothbubbles/services/ActiveConversationManager.kt) | 66-72 | Set active conversation |
| [ActiveConversationManager.kt](../../app/src/main/kotlin/com/bothbubbles/services/ActiveConversationManager.kt) | 78-82 | Clear active conversation |
| [ActiveConversationManager.kt](../../app/src/main/kotlin/com/bothbubbles/services/ActiveConversationManager.kt) | 90-93 | Check if conversation is active |

---

## 7. Potential Causes of Auto-Opening Bubbles

Since the app explicitly sets `setAutoExpandBubble(false)`, the auto-opening behavior is likely caused by external factors:

### 7.1 Most Likely: Android System Settings

Android has system-level bubble settings that can override app behavior:

**Location**: Settings → Apps → [BothBubbles] → Notifications → Bubbles

| System Setting | Effect |
|----------------|--------|
| "All conversations can bubble" | Bubbles enabled for all chats |
| "Selected conversations can bubble" | Only selected chats show bubbles |
| "Nothing can bubble" | Bubbles disabled |

**⚠️ Important**: Some Android versions (especially Samsung OneUI) have an additional toggle for "Auto-expand bubbles" at the system level that can force all bubbles to auto-expand regardless of app settings.

**To check**: Settings → Apps → [BothBubbles] → Notifications → Bubbles → Look for "Auto-expand" toggle

### 7.2 Possible: OEM Skin Differences

Different Android OEM skins handle bubbles differently:

| OEM | Behavior Notes |
|-----|----------------|
| **Samsung OneUI** | Has additional bubble controls in notification settings. May have auto-expand toggle. |
| **Pixel** | Follows Android default behavior. Respects `setAutoExpandBubble(false)`. |
| **Xiaomi MIUI** | Bubbles may behave differently; some versions auto-expand by default. |
| **OnePlus OxygenOS** | Generally follows Android behavior. |

### 7.3 Possible: Android Version Bug

There have been known bugs in certain Android versions where `setAutoExpandBubble(false)` is ignored:

- Android 11 (API 30): Some early versions had bubble behavior bugs
- Android 12 (API 31): Initial release had issues with bubble state persistence

### 7.4 Unlikely but Possible: Race Condition in Active Conversation

If `ActiveConversationManager.setActiveConversation()` is called at the wrong time, it could cause:
- Notification suppression to fail → notification shows
- Followed immediately by bubble opening

**Check for this**: Add logging to `setActiveConversation()` and `clearActiveConversation()` to verify timing.

### 7.5 Not the Cause: App Code

The app code explicitly does the right thing:

```kotlin
// BubbleMetadataHelper.kt:256
.setAutoExpandBubble(false)
```

This is correctly set for all notifications. The app is not calling `setAutoExpandBubble(true)` anywhere.

---

## 8. Debugging Recommendations

### 8.1 Verify Android System Settings

**Step 1**: Check bubble settings
```
Settings → Apps → BothBubbles → Notifications → Bubbles
```

**Step 2**: If using Samsung/Xiaomi, look for additional "Auto-expand" toggles in:
- Notification settings for the app
- System-wide notification settings
- "Advanced features" or "Special features" menus

### 8.2 Add Debug Logging

Add logging to track notification and bubble lifecycle:

**In NotificationBuilder.kt around line 354:**
```kotlin
if (bubbleMetadata != null) {
    notificationBuilder.setBubbleMetadata(bubbleMetadata)
    Timber.tag("BubbleDebug").d(
        "Attached bubble (autoExpand=false) for chat: $chatGuid"
    )
}
```

**In BubbleActivity.kt:**
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Timber.tag("BubbleDebug").d("BubbleActivity.onCreate() called for: $chatGuid")
    // ... rest of code
}
```

### 8.3 Test on Multiple Devices

If possible, test on:
1. Pixel device (stock Android)
2. Samsung device (OneUI)
3. Other OEM devices

Compare behavior to identify if it's OEM-specific.

### 8.4 Check NotificationManager.bubblePreference

The app checks system bubble preference in `NotificationSettingsViewModel.kt:47-52`:

```kotlin
return when (notificationManager.bubblePreference) {
    NotificationManager.BUBBLE_PREFERENCE_ALL -> SystemBubblesState.ENABLED
    NotificationManager.BUBBLE_PREFERENCE_SELECTED -> SystemBubblesState.ENABLED
    NotificationManager.BUBBLE_PREFERENCE_NONE -> SystemBubblesState.DISABLED
    else -> SystemBubblesState.ENABLED
}
```

Log this value on startup to verify system state.

### 8.5 Verify Shortcut Behavior

Bubbles require shortcuts. If shortcuts are being recreated frequently, it could cause unexpected behavior. Check logs for:
```
ShortcutManagerCompat.pushDynamicShortcut()
```

---

## Appendix A: Notification Parameters

**File**: [Notifier.kt:23-39](../../app/src/main/kotlin/com/bothbubbles/services/notifications/Notifier.kt#L23-L39)

```kotlin
data class MessageNotificationParams(
    val chatGuid: String,
    val chatTitle: String,
    val messageText: String,
    val messageGuid: String,
    val senderName: String?,
    val senderAddress: String?,
    val isGroup: Boolean,
    val avatarUri: String?,
    val linkPreviewTitle: String? = null,
    val linkPreviewDomain: String? = null,
    val participantNames: List<String> = emptyList(),
    val participantAvatarPaths: List<String?> = emptyList(),
    val subject: String? = null,
    val attachmentUri: android.net.Uri? = null,
    val attachmentMimeType: String? = null
)
```

---

## Appendix B: Complete Bubble Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            NOTIFICATION CREATED                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  NotificationBuilder.buildMessageNotification()                             │
│      │                                                                      │
│      ├─► Create shortcut (required for bubbles)                             │
│      │   └─► BubbleMetadataHelper.createConversationShortcut()              │
│      │       ├─► ShortcutInfoCompat.Builder()                               │
│      │       │   ├─► .setLongLived(true)                                    │
│      │       │   ├─► .setIsConversation()                                   │
│      │       │   └─► .setLocusId(chatGuid)                                  │
│      │       └─► ShortcutManagerCompat.pushDynamicShortcut()                │
│      │                                                                      │
│      ├─► Check bubble eligibility                                           │
│      │   └─► BubbleMetadataHelper.shouldShowBubble()                        │
│      │       └─► Check filter mode (all/selected/favorites/none)            │
│      │                                                                      │
│      ├─► Create bubble metadata (if eligible)                               │
│      │   └─► BubbleMetadataHelper.createBubbleMetadata()                    │
│      │       ├─► Create PendingIntent for BubbleActivity                    │
│      │       ├─► Generate adaptive icon                                     │
│      │       └─► NotificationCompat.BubbleMetadata.Builder()                │
│      │           ├─► .setDesiredHeight(600)                                 │
│      │           ├─► .setAutoExpandBubble(false)  ← KEY                     │
│      │           └─► .setSuppressNotification(false)                        │
│      │                                                                      │
│      ├─► Build notification                                                 │
│      │   └─► NotificationCompat.Builder()                                   │
│      │       ├─► .setStyle(MessagingStyle)                                  │
│      │       ├─► .setShortcutId(shortcutId)                                 │
│      │       ├─► .setLocusId(chatGuid)                                      │
│      │       └─► .setBubbleMetadata(metadata)                               │
│      │                                                                      │
│      └─► Return notification                                                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           NOTIFICATION POSTED                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  NotificationService.showMessageNotification()                              │
│      └─► NotificationManager.notify(chatGuid.hashCode(), notification)      │
│                                                                             │
│  Result (based on app settings):                                            │
│      • Notification appears in notification shade                           │
│      • Bubble icon appears (collapsed, NOT expanded)                        │
│      • User must TAP bubble to expand it                                    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼ (user taps bubble)
┌─────────────────────────────────────────────────────────────────────────────┐
│                           BUBBLE ACTIVITY OPENS                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  BubbleActivity.onCreate()                                                  │
│      ├─► Extract chatGuid from intent                                       │
│      ├─► Register with ActiveConversationManager                            │
│      │   └─► activeConversationManager.setActiveConversation(chatGuid)      │
│      │       (This suppresses future notifications for this chat)           │
│      └─► Display ChatScreen(isBubbleMode = true)                            │
│                                                                             │
│  BubbleActivity.onPause() / onDestroy()                                     │
│      └─► activeConversationManager.clearActiveConversation()                │
│          (This re-enables notifications for this chat)                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Summary

**The app is correctly configured to NOT auto-expand bubbles.** The `setAutoExpandBubble(false)` setting is properly set in `BubbleMetadataHelper.kt:256`.

If bubbles are auto-opening when notifications arrive, the most likely causes are:

1. **Android system bubble settings** overriding app settings
2. **OEM-specific behavior** (Samsung, Xiaomi, etc.)
3. **Android version bugs** in bubble implementation

**Next steps for debugging:**
1. Check Android system settings for bubble auto-expand toggles
2. Test on a Pixel device to isolate OEM issues
3. Add debug logging to track notification and bubble lifecycle
4. Report to user that this appears to be a system setting issue, not an app bug
