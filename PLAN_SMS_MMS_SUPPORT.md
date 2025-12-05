# Plan: Android SMS/MMS Support for BlueBubbles

## Current Status

### Google Messages UI Migration (In Progress)
The app is being refactored to mirror the Google Messages UI exactly while maintaining BlueBubbles backend functionality.

**Completed:**
- [x] v1/v2 folder structure established (v1=app code, v2=design system)
- [x] Design tokens created (`lib/v2/theme/tokens/`)
- [x] Conversation list header - Google Messages style
- [x] Conversation list tiles - exact spec matching
- [x] FAB styling - "Start chat" with collapse on scroll
- [x] Android 16 (API 36) emulator setup

**In Progress:**
- [ ] Message view bubble styling
- [ ] Message input field styling
- [ ] Settings page redesign

**Pending:**
- [ ] Full SMS/MMS integration (see below)
- [ ] Contact avatars with colored rings
- [ ] Search UI improvements

---

## Overview

This plan outlines the implementation of native SMS/MMS support on Android, enabling BlueBubbles to become the default messaging app while prioritizing iMessage (via the Mac server) and falling back to SMS/MMS when iMessage is unavailable.

---

## Architecture Decision

### Message Routing Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│                    User Sends Message                            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              Check: Is BlueBubbles Server Connected?             │
└─────────────────────────────────────────────────────────────────┘
                    │                       │
                   YES                      NO
                    │                       │
                    ▼                       ▼
┌──────────────────────────────┐  ┌────────────────────────────────┐
│  Check: Is recipient         │  │  Send via SMS/MMS directly     │
│  iMessage-capable?           │  │  (Android native)              │
│  (via server lookup)         │  └────────────────────────────────┘
└──────────────────────────────┘
          │              │
         YES            NO
          │              │
          ▼              ▼
┌─────────────────┐  ┌────────────────────────────────────────────┐
│ Send via iMessage│  │ Send via SMS/MMS (through server or local)│
│ (Mac server)    │  └────────────────────────────────────────────┘
└─────────────────┘
```

### Fallback Modes

1. **Server-Assisted SMS** (when server connected but recipient not on iMessage)
   - Server sends SMS via Mac's text forwarding if configured
   - Falls back to local Android SMS if server SMS fails

2. **Local SMS/MMS** (when server disconnected or unavailable)
   - Direct Android SMS/MMS API usage
   - Full offline capability for non-iMessage contacts

---

## Phase 1: Android Permissions & Default SMS App Setup

### 1.1 Update AndroidManifest.xml

**File:** `android/app/src/main/AndroidManifest.xml`

Add required SMS/MMS permissions:
```xml
<!-- SMS Permissions -->
<uses-permission android:name="android.permission.SEND_SMS"/>
<uses-permission android:name="android.permission.RECEIVE_SMS"/>
<uses-permission android:name="android.permission.READ_SMS"/>
<uses-permission android:name="android.permission.RECEIVE_MMS"/>
<uses-permission android:name="android.permission.RECEIVE_WAP_PUSH"/>

<!-- MMS Permissions -->
<uses-permission android:name="android.permission.WRITE_SMS"/>
<uses-permission android:name="android.permission.READ_PHONE_STATE"/>
<uses-permission android:name="android.permission.READ_PHONE_NUMBERS"/>
```

Add required components for default SMS app:
```xml
<!-- SMS Receiver -->
<receiver android:name=".services.sms.SmsReceiver"
    android:permission="android.permission.BROADCAST_SMS"
    android:exported="true">
    <intent-filter>
        <action android:name="android.provider.Telephony.SMS_RECEIVED"/>
    </intent-filter>
</receiver>

<!-- MMS Receiver -->
<receiver android:name=".services.sms.MmsReceiver"
    android:permission="android.permission.BROADCAST_WAP_PUSH"
    android:exported="true">
    <intent-filter>
        <action android:name="android.provider.Telephony.WAP_PUSH_RECEIVED"/>
        <data android:mimeType="application/vnd.wap.mms-message"/>
    </intent-filter>
</receiver>

<!-- SMS Deliver Receiver (required for default SMS) -->
<receiver android:name=".services.sms.SmsDeliverReceiver"
    android:permission="android.permission.BROADCAST_SMS"
    android:exported="true">
    <intent-filter>
        <action android:name="android.provider.Telephony.SMS_DELIVER"/>
    </intent-filter>
</receiver>

<!-- MMS Deliver Receiver (required for default SMS) -->
<receiver android:name=".services.sms.MmsDeliverReceiver"
    android:permission="android.permission.BROADCAST_WAP_PUSH"
    android:exported="true">
    <intent-filter>
        <action android:name="android.provider.Telephony.WAP_PUSH_DELIVER"/>
        <data android:mimeType="application/vnd.wap.mms-message"/>
    </intent-filter>
</receiver>

<!-- Headless SMS Service (required for default SMS) -->
<service android:name=".services.sms.HeadlessSmsSendService"
    android:permission="android.permission.SEND_RESPOND_VIA_MESSAGE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.RESPOND_VIA_MESSAGE"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <data android:scheme="sms"/>
        <data android:scheme="smsto"/>
        <data android:scheme="mms"/>
        <data android:scheme="mmsto"/>
    </intent-filter>
</service>

<!-- SMS Compose Activity Handler -->
<activity android:name=".activities.ComposeSmsActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.SEND"/>
        <action android:name="android.intent.action.SENDTO"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <category android:name="android.intent.category.BROWSABLE"/>
        <data android:scheme="sms"/>
        <data android:scheme="smsto"/>
        <data android:scheme="mms"/>
        <data android:scheme="mmsto"/>
    </intent-filter>
</activity>
```

### 1.2 Create Default SMS App Request Handler

**New File:** `android/app/src/main/kotlin/com/bluebubbles/messaging/services/sms/DefaultSmsAppHandler.kt`

```kotlin
class DefaultSmsAppHandler {
    fun requestDefaultSmsApp(context: Context, result: MethodChannel.Result)
    fun isDefaultSmsApp(context: Context): Boolean
    fun checkDefaultSmsStatus(context: Context, result: MethodChannel.Result)
}
```

### 1.3 Update Dart Permission Handling

**File:** `lib/services/backend/settings/settings_service.dart`

Add SMS permission request logic and default SMS app status tracking.

---

## Phase 2: Android Native SMS/MMS Implementation

### 2.1 SMS Sending Handler

**New File:** `android/app/src/main/kotlin/com/bluebubbles/messaging/services/sms/SendSmsHandler.kt`

```kotlin
class SendSmsHandler : MethodCallHandler.Handler {
    companion object {
        const val METHOD = "send-sms"
    }

    fun sendSms(
        address: String,
        text: String,
        tempGuid: String,
        result: MethodChannel.Result
    )

    fun sendMms(
        addresses: List<String>,
        text: String?,
        attachments: List<Uri>,
        subject: String?,
        tempGuid: String,
        result: MethodChannel.Result
    )
}
```

Key implementation details:
- Use `SmsManager.sendTextMessage()` for SMS
- Use `SmsManager.sendMultipartTextMessage()` for long SMS
- Implement MMS via `MmsManager` or content provider approach
- Track delivery status via `PendingIntent` and `BroadcastReceiver`
- Return temp GUID for message tracking

### 2.2 SMS/MMS Receive Handlers

**New File:** `android/app/src/main/kotlin/com/bluebubbles/messaging/services/sms/SmsReceiver.kt`

```kotlin
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Parse SMS PDU
        // Create message object
        // Notify Dart via MethodChannel
        // Show notification if app not foreground
    }
}
```

**New File:** `android/app/src/main/kotlin/com/bluebubbles/messaging/services/sms/MmsReceiver.kt`

```kotlin
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Parse MMS content
        // Download attachments
        // Create message object with attachments
        // Notify Dart via MethodChannel
    }
}
```

### 2.3 SMS Database Sync Handler

**New File:** `android/app/src/main/kotlin/com/bluebubbles/messaging/services/sms/SmsContentProviderHandler.kt`

```kotlin
class SmsContentProviderHandler {
    // Read existing SMS/MMS from Android content provider
    fun getAllSmsConversations(): List<SmsConversation>
    fun getSmsForThread(threadId: Long): List<SmsMessage>
    fun getMmsForThread(threadId: Long): List<MmsMessage>

    // Write SMS to content provider (required for default SMS app)
    fun writeSmsToProvider(message: SmsMessage)
    fun writeMmsToProvider(message: MmsMessage)

    // Delete operations
    fun deleteThread(threadId: Long)
    fun deleteMessage(messageId: Long)
}
```

### 2.4 Update Method Channel Handler

**File:** `android/app/src/main/kotlin/com/bluebubbles/messaging/services/backend_ui_interop/MethodCallHandler.kt`

Add new SMS method handlers:
```kotlin
// Add to when(call.method) block:
"send-sms" -> SendSmsHandler.sendSms(call, result, context)
"send-mms" -> SendSmsHandler.sendMms(call, result, context)
"get-sms-threads" -> SmsContentProviderHandler.getThreads(call, result, context)
"get-sms-messages" -> SmsContentProviderHandler.getMessages(call, result, context)
"sync-sms-database" -> SmsContentProviderHandler.syncAll(call, result, context)
"is-default-sms-app" -> DefaultSmsAppHandler.check(result, context)
"request-default-sms-app" -> DefaultSmsAppHandler.request(call, result, context)
"write-sms-to-provider" -> SmsContentProviderHandler.write(call, result, context)
```

---

## Phase 3: Dart Layer Integration

### 3.1 Create SMS Service

**New File:** `lib/services/backend/sms/sms_service.dart`

```dart
class SmsService extends GetxService {
  static SmsService get instance => Get.find<SmsService>();

  final RxBool isDefaultSmsApp = false.obs;
  final RxBool smsEnabled = false.obs;

  // Platform channel for SMS operations
  static const MethodChannel _channel = MethodChannel('com.bluebubbles.messaging');

  Future<void> init();
  Future<bool> requestDefaultSmsApp();
  Future<bool> checkDefaultSmsStatus();

  Future<Message> sendSms({
    required String address,
    required String text,
    String? tempGuid,
  });

  Future<Message> sendMms({
    required List<String> addresses,
    String? text,
    List<PlatformFile>? attachments,
    String? subject,
    String? tempGuid,
  });

  Future<List<Chat>> syncSmsConversations();
  Future<List<Message>> syncSmsMessages(Chat chat);

  // Receive handler (called from MethodChannelService)
  void handleIncomingSms(Map<String, dynamic> data);
  void handleIncomingMms(Map<String, dynamic> data);
}
```

### 3.2 Update Method Channel Service

**File:** `lib/services/backend/java_dart_interop/method_channel_service.dart`

Add handlers for incoming SMS/MMS:
```dart
case "incoming-sms":
  smsService.handleIncomingSms(call.arguments);
  break;
case "incoming-mms":
  smsService.handleIncomingMms(call.arguments);
  break;
case "sms-delivery-status":
  smsService.handleDeliveryStatus(call.arguments);
  break;
```

### 3.3 Update Message Model

**File:** `lib/database/io/message.dart`

Add SMS-specific fields:
```dart
// Add new fields
int? smsId;  // Android SMS content provider ID
int? threadId;  // Android thread ID
String? smsType;  // "sms" or "mms"
int? smsStatus;  // Delivery status from Android

// Add helper getters
bool get isSmsMessage => smsType != null;
bool get isMmsMessage => smsType == "mms";
```

### 3.4 Update Chat Model

**File:** `lib/database/io/chat.dart`

Fix the `isSMS` property:
```dart
// Change from:
bool get isSMS => false;

// To:
bool get isSMS => handles.any((h) => h.service == "SMS") ||
                  guid.startsWith("SMS;");

// Add new properties
int? threadId;  // Android SMS thread ID
bool get isLocalSms => threadId != null;  // True if this is a local SMS conversation
```

### 3.5 Update Handle Model

**File:** `lib/database/io/handle.dart`

Add service type detection:
```dart
bool get isSmsHandle => service == "SMS";
bool get isIMessageHandle => service == "iMessage";
```

### 3.6 Update Action Handler - Message Routing Logic

**File:** `lib/services/backend/action_handler.dart`

Implement the core routing logic:
```dart
Future<void> sendMessage(Chat chat, Message message, Message? reply, String? effect) async {
  // Determine send method
  final sendMethod = await _determineSendMethod(chat, message);

  switch (sendMethod) {
    case SendMethod.iMessage:
      // Existing iMessage logic via server
      await _sendViaServer(chat, message, reply, effect);
      break;

    case SendMethod.serverSms:
      // Send SMS through Mac's text forwarding
      await _sendViaServerSms(chat, message);
      break;

    case SendMethod.localSms:
      // Send directly via Android SMS
      await _sendViaLocalSms(chat, message);
      break;

    case SendMethod.localMms:
      // Send directly via Android MMS
      await _sendViaLocalMms(chat, message);
      break;
  }
}

Future<SendMethod> _determineSendMethod(Chat chat, Message message) async {
  // 1. If chat is already a local SMS chat, use local SMS
  if (chat.isLocalSms) {
    return message.hasAttachments
        ? SendMethod.localMms
        : SendMethod.localSms;
  }

  // 2. Check if server is connected
  if (!socket.isConnected) {
    // No server connection, use local SMS
    return message.hasAttachments
        ? SendMethod.localMms
        : SendMethod.localSms;
  }

  // 3. Check if recipient supports iMessage
  if (chat.isIMessage) {
    return SendMethod.iMessage;
  }

  // 4. If server can send SMS (text forwarding enabled), use that
  final serverInfo = await http.serverInfo();
  if (serverInfo.data?["data"]?["text_forwarding"] == true) {
    return SendMethod.serverSms;
  }

  // 5. Fall back to local SMS
  return message.hasAttachments
      ? SendMethod.localMms
      : SendMethod.localSms;
}

enum SendMethod {
  iMessage,
  serverSms,
  localSms,
  localMms,
}
```

---

## Phase 4: UI Updates

### 4.1 Settings Page - SMS Configuration

**File:** `lib/app/layouts/settings/pages/`

**New File:** `lib/app/layouts/settings/pages/sms_settings.dart`

```dart
class SmsSettingsPage extends StatelessWidget {
  // Toggle: Enable SMS fallback
  // Button: Request default SMS app
  // Status: Current default SMS app status
  // Toggle: Show SMS delivery reports
  // Toggle: Auto-download MMS
  // Info: Current carrier/SIM info
}
```

### 4.2 Chat List - SMS Indicator

**File:** `lib/app/layouts/chat_list/`

Update chat tiles to show SMS vs iMessage indicator (green vs blue bubble icon).

### 4.3 Conversation View - Send Button State

**File:** `lib/app/layouts/conversation_view/`

Update send button to indicate:
- Blue: Sending via iMessage
- Green: Sending via SMS
- Show warning when falling back to SMS

### 4.4 Message Bubbles - SMS Styling

**File:** `lib/app/components/message_view/`

- Use green bubbles for SMS messages
- Show "SMS" label for outgoing SMS
- Show delivery status for SMS (Sent, Delivered, Failed)

### 4.5 New Chat Flow - Recipient Type Detection

**File:** `lib/app/layouts/conversation_view/widgets/new_chat/`

When creating a new chat:
1. Check if recipient is in existing iMessage chats
2. Query server for iMessage availability
3. Show indicator of how message will be sent
4. Allow user to force SMS even if iMessage available

---

## Phase 5: Initial SMS Sync & Migration

### 5.1 First-Time SMS Import

**New File:** `lib/services/backend/sms/sms_sync_service.dart`

```dart
class SmsSyncService {
  // Import all existing SMS from Android
  Future<void> importAllSms({
    Function(int current, int total)? onProgress,
  });

  // Merge SMS chats with existing iMessage chats
  Future<void> mergeConversations();

  // Continuous sync for new messages
  Future<void> startBackgroundSync();
}
```

### 5.2 Conversation Merging Logic

When importing SMS, check if there's an existing iMessage conversation with the same phone number:
- If yes: Merge messages into existing chat, mark handle as "dual" (both iMessage and SMS capable)
- If no: Create new SMS-only chat

---

## Phase 6: Notifications & Background Processing

### 6.1 Update Notification Handling

**File:** `android/app/src/main/kotlin/com/bluebubbles/messaging/services/notifications/CreateIncomingMessageNotification.kt`

Add SMS-specific notification handling:
- Use green color for SMS notifications
- Different notification channel for SMS
- Reply action support for SMS

### 6.2 Background SMS Processing

**File:** `android/app/src/main/kotlin/com/bluebubbles/messaging/services/sms/`

Ensure SMS is received even when app is killed:
- SMS receivers are always active (system broadcasts)
- Process messages in receiver
- Store to content provider immediately
- Queue Dart notification when app next opens

---

## Phase 7: Testing & Edge Cases

### 7.1 Test Scenarios

1. **Send SMS when server connected**
   - Recipient has iMessage → Use iMessage
   - Recipient doesn't have iMessage → Use server SMS or local SMS

2. **Send SMS when server disconnected**
   - All messages go via local SMS/MMS

3. **Receive SMS**
   - App in foreground → Show in chat immediately
   - App in background → Show notification, store message
   - App killed → Store to provider, show notification

4. **MMS handling**
   - Send with single image
   - Send with multiple images
   - Send with video
   - Send with mixed media
   - Receive group MMS
   - Large attachment handling

5. **Default SMS app transitions**
   - Becoming default app (import existing SMS)
   - Losing default app status (warn user)

### 7.2 Error Handling

- No SIM card inserted
- Airplane mode
- No cellular signal
- SMS send failure (retry logic)
- MMS download failure
- Carrier MMS configuration issues

---

## Implementation Order

### Sprint 1: Foundation (Estimated: 2 weeks of work)
1. [ ] Phase 1.1: AndroidManifest.xml permissions and components
2. [ ] Phase 1.2: Default SMS app handler
3. [ ] Phase 2.1: Basic SMS sending (text only)
4. [ ] Phase 2.2: SMS receiving
5. [ ] Phase 3.1: SMS service in Dart
6. [ ] Phase 3.2: Method channel integration

### Sprint 2: Core Features (Estimated: 2 weeks of work)
1. [ ] Phase 2.3: SMS content provider integration
2. [ ] Phase 3.3-3.5: Model updates
3. [ ] Phase 3.6: Message routing logic
4. [ ] Phase 5.1: Initial SMS import
5. [ ] Phase 5.2: Conversation merging

### Sprint 3: MMS & Polish (Estimated: 2 weeks of work)
1. [ ] Phase 2.1: MMS sending
2. [ ] Phase 2.2: MMS receiving
3. [ ] Phase 4.1-4.5: All UI updates
4. [ ] Phase 6: Notifications and background

### Sprint 4: Testing & Launch (Estimated: 1 week of work)
1. [ ] Phase 7: All test scenarios
2. [ ] Bug fixes and edge cases
3. [ ] Documentation
4. [ ] Beta release

---

## Dependencies & Considerations

### Required Android APIs
- `SmsManager` - API 4+
- `MmsManager` - API 21+
- `Telephony.Sms` content provider
- `Telephony.Mms` content provider

### Carrier Compatibility
- MMS APN settings vary by carrier
- Some carriers require specific MMS configuration
- Consider using a library like `android-smsmms` for MMS handling

### User Privacy
- SMS permissions are sensitive
- Clearly explain why permissions are needed
- Provide option to use without SMS (iMessage only)

### Battery Impact
- SMS receivers are lightweight
- MMS downloads should be queued
- Minimize wake locks

---

## Files to Create

```
android/app/src/main/kotlin/com/bluebubbles/messaging/
├── services/
│   └── sms/
│       ├── DefaultSmsAppHandler.kt
│       ├── SendSmsHandler.kt
│       ├── SmsReceiver.kt
│       ├── SmsDeliverReceiver.kt
│       ├── MmsReceiver.kt
│       ├── MmsDeliverReceiver.kt
│       ├── HeadlessSmsSendService.kt
│       ├── SmsContentProviderHandler.kt
│       └── MmsDownloadService.kt
└── activities/
    └── ComposeSmsActivity.kt

lib/services/backend/sms/
├── sms_service.dart
└── sms_sync_service.dart

lib/app/layouts/settings/pages/
└── sms_settings.dart
```

## Files to Modify

```
android/app/src/main/AndroidManifest.xml
android/app/src/main/kotlin/.../MethodCallHandler.kt
lib/database/io/message.dart
lib/database/io/chat.dart
lib/database/io/handle.dart
lib/services/backend/action_handler.dart
lib/services/backend/java_dart_interop/method_channel_service.dart
lib/app/layouts/settings/settings_panel.dart (add SMS settings link)
lib/app/layouts/conversation_view/* (various UI updates)
lib/app/components/message_view/* (SMS bubble styling)
```

---

## Success Criteria

1. App can be set as default SMS app on Android
2. SMS messages send and receive reliably
3. MMS with attachments works correctly
4. Messages prioritize iMessage when available
5. Seamless fallback to SMS when iMessage unavailable
6. Existing SMS conversations imported correctly
7. Notifications work for SMS in all app states
8. Battery impact is minimal
9. User experience is intuitive (clear indication of message type)
