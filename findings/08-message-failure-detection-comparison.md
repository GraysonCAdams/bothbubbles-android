# Message Failure Detection: BothBubbles vs BlueBubbles Reference App

A comprehensive analysis of how both apps determine if an iMessage has failed to send.

## Executive Summary

| Aspect | BothBubbles (Kotlin) | BlueBubbles (Flutter) |
|--------|---------------------|----------------------|
| Error Detection Primary | Socket event + API response | API response + GUID mutation |
| Error Code Granularity | Binary (0 = ok, 1 = error) | Numeric codes (0, 4, 22, 1000, 1001, 1002) |
| Error Message Storage | Separate `sms_error_message` field | Embedded in GUID (`error-{message}`) |
| Socket Events | Dedicated `message-send-error` event | No dedicated event (uses `updated-message`) |
| State Machine | `PendingMessageStateMachine` | Direct state transitions |
| Retry Mechanism | WorkManager + fallback to SMS | In-app retry + delete original |

---

## 1. Message Entity Error Tracking

### BothBubbles (Kotlin)

**File:** `core/model/src/main/kotlin/com/bothbubbles/core/model/entity/MessageEntity.kt`

```kotlin
@ColumnInfo(name = "error")
val error: Int = 0,

@ColumnInfo(name = "sms_error_message")
val smsErrorMessage: String? = null,

// Computed property
val isSent: Boolean
    get() = error == 0 && !guid.startsWith("temp-")
```

**Error Values:**
- `error = 0`: No error (success)
- `error = 1`: Generic send failure

### BlueBubbles (Flutter)

**File:** `references/bluebubbles-old/lib/database/io/message.dart`

```dart
final RxInt _error = RxInt(0);
int get error => _error.value;

// Indicator enum for UI state
Indicator get indicatorToShow {
  if (!isFromMe!) return Indicator.NONE;
  if (dateRead != null) return Indicator.READ;
  if (isDelivered) return Indicator.DELIVERED;
  if (dateDelivered != null) return Indicator.DELIVERED;
  if (dateCreated != null) return Indicator.SENT;
  return Indicator.NONE;
}
```

**Error Values (from `constants.dart`):**
```dart
enum MessageError { NO_ERROR, TIMEOUT, NO_CONNECTION, BAD_REQUEST, SERVER_ERROR }
// Mapped to: 0, 4, 1000, 1001, 1002
// Plus server-side error code 22 = "Recipient not registered with iMessage"
```

### Analysis

| Feature | BothBubbles | BlueBubbles |
|---------|-------------|-------------|
| Error granularity | **Binary** (0/1) | **Multiple codes** (0, 4, 22, 1000-1002) |
| Error message storage | Dedicated field | Embedded in GUID |
| SMS-specific errors | Separate `smsErrorMessage` | Same as iMessage |

**BothBubbles Superiority:** Cleaner separation with dedicated `smsErrorMessage` field.

**BlueBubbles Superiority:** More granular error codes enable specific user feedback (e.g., error 22 = "Recipient not registered with iMessage").

---

## 2. Error Detection Flow

### BothBubbles (Kotlin)

Uses a **dedicated socket event** for fast failure notification:

**Socket Event Definition** (`SocketService.kt`):
```kotlin
data class MessageSendError(val tempGuid: String, val errorMessage: String) : SocketEvent()
```

**Socket Event Parser** (`SocketEventParser.kt:95-109`):
```kotlin
val onMessageSendError = Emitter.Listener { args ->
    val data = args.firstOrNull() as? JSONObject ?: return@Listener
    val tempGuid = data.optString("tempGuid", data.optString("guid", ""))
    val errorMessage = data.optString("error", data.optString("message", "Send failed"))

    if (tempGuid.isNotBlank()) {
        events.tryEmit(SocketEvent.MessageSendError(tempGuid, errorMessage))
    }
}
```

**Handler Chain:**
```
SocketEvent.MessageSendError
  → MessageEventHandler.handleMessageSendError()
  → MessageRepository.markMessageAsFailed()
  → messageDao.updateMessageError(guid, 1, errorMessage)
  → UiRefreshEvent.MessageSendFailed emitted
```

### BlueBubbles (Flutter)

Uses **API response handling** with GUID mutation:

**Network Error Handler** (`network_error_handler.dart`):
```dart
Message handleSendError(dynamic error, Message m) {
  if (error is Response) {
    // Encode error in GUID: "temp-xxx" → "error-{message}"
    m.guid = m.guid!.replaceAll("temp", "error-${error.data['error']['message']}");
    m.error = error.statusCode ?? MessageError.BAD_REQUEST.code;
  } else if (error is DioException) {
    String _error;
    if (error.type == DioExceptionType.connectionTimeout) {
      _error = "Connect timeout occured! Check your connection.";
    } else if (error.type == DioExceptionType.sendTimeout) {
      _error = "Send timeout occured!";
    } else if (error.type == DioExceptionType.receiveTimeout) {
      _error = "Receive data timeout occured!";
    } else {
      _error = error.error.toString();
    }
    m.guid = m.guid!.replaceAll("temp", "error-$_error");
    m.error = error.response?.statusCode ?? MessageError.BAD_REQUEST.code;
  } else {
    m.guid = m.guid!.replaceAll("temp", "error-Connection timeout");
    m.error = MessageError.BAD_REQUEST.code;
  }
  return m;
}
```

### Analysis

| Feature | BothBubbles | BlueBubbles |
|---------|-------------|-------------|
| Primary mechanism | Socket push event | API response + GUID mutation |
| Speed of detection | **Faster** (real-time socket) | Slower (waits for HTTP response) |
| Error message persistence | Database column | Encoded in GUID |
| Simplicity | Cleaner separation | GUID becomes dual-purpose |

**BothBubbles Superiority:**
- Dedicated `message-send-error` socket event provides **faster failure detection**
- Server can notify client immediately without waiting for HTTP response timeout
- Error message stored in proper database column, not encoded in GUID

**BlueBubbles Superiority:**
- GUID encoding is self-documenting (can see error in logs just from GUID)
- Handles timeout errors with specific user-friendly messages
- No need for additional socket event infrastructure

---

## 3. Failure Detection Conditions

### BothBubbles

A message is considered **FAILED** when:

1. `message.error != 0` (error code set)
2. `message.guid.startsWith("temp-")` AND `error == 1` (pending + error)

**From `MessageTransformationUtils.kt:97`:**
```kotlin
isSent = !guid.startsWith("temp-") && error == 0,
hasError = error != 0,
```

### BlueBubbles

A message is considered **FAILED** when ANY of:

1. `message.error > 0`
2. `message.guid.startsWith("error-")`
3. Specific error code 22 (recipient not on iMessage)

**From `message_holder.dart:716-720`:**
```dart
if (message.error > 0 || message.guid!.startsWith("error-")) {
  int errorCode = message.error;
  String errorText = "An unknown internal error occurred.";
  if (errorCode == 22) {
    errorText = "The recipient is not registered with iMessage!";
  } else if (message.guid!.startsWith("error-")) {
    errorText = message.guid!.split('-')[1];
  }
}
```

### Analysis

**BlueBubbles Superiority:** Error code 22 handling provides specific feedback for a common failure case (non-iMessage recipient).

**BothBubbles Gap:** No special handling for error code 22. All failures display generic error message.

---

## 4. State Machine & Pending Messages

### BothBubbles

Uses a **formal state machine** for pending messages:

**File:** `PendingMessageStateMachine.kt`

```kotlin
enum class PendingSyncStatus {
    PENDING,   // Queued, waiting for network
    SENDING,   // Currently being sent
    SENT,      // Successfully sent
    FAILED     // Failed after max retries
}

suspend fun markFailed(messageId: Long, errorMessage: String): TransitionResult {
    return when (currentStatus) {
        PendingSyncStatus.SENDING.name -> {
            // Valid: SENDING → FAILED
            pendingMessageDao.updateStatusWithError(
                messageId,
                PendingSyncStatus.FAILED.name,
                errorMessage,
                System.currentTimeMillis()
            )
            TransitionResult.Success(PendingSyncStatus.FAILED)
        }
        PendingSyncStatus.FAILED.name -> {
            // Already failed - update error message
            TransitionResult.Success(PendingSyncStatus.FAILED)
        }
        else -> TransitionResult.InvalidTransition(currentStatus, "FAILED")
    }
}
```

### BlueBubbles

Uses **direct state transitions** without formal state machine:

```dart
// Send flow (action_handler.dart)
http.sendMessage(...).then((response) async {
  // SUCCESS: Replace temp message
  final newMessage = Message.fromMap(response.data['data']);
  await matchMessageWithExisting(c, m.guid!, newMessage);
}).catchError((error) async {
  // FAILURE: Mutate GUID and error code
  m = handleSendError(error, m);
  await Message.replaceMessage(tempGuid, m);
});
```

### Analysis

| Feature | BothBubbles | BlueBubbles |
|---------|-------------|-------------|
| State management | Formal state machine | Direct transitions |
| Invalid transitions | Explicitly handled | No guards |
| Debugging | Clear state history | Implicit in GUID |

**BothBubbles Superiority:**
- Formal state machine prevents invalid state transitions
- Clear separation of PENDING → SENDING → SENT/FAILED
- Better auditing and debugging capabilities

**BlueBubbles Approach:** Simpler implementation, but prone to race conditions.

---

## 5. Retry Mechanism

### BothBubbles

**File:** `MessageSendingService.kt`

```kotlin
// Retry same message
override suspend fun retryMessage(messageGuid: String): Result<MessageEntity> = safeCall {
    val message = messageDao.getMessageByGuid(messageGuid)
        ?: throw MessageError.SendFailed(messageGuid, "Message not found")

    // Reset error status
    messageDao.updateErrorStatus(messageGuid, 0)

    // Re-send via original delivery mode
    sendUnified(
        chatGuid = message.chatGuid,
        text = message.text ?: "",
        deliveryMode = when (message.messageSource) {
            MessageSource.LOCAL_SMS.name -> MessageDeliveryMode.LOCAL_SMS
            MessageSource.LOCAL_MMS.name -> MessageDeliveryMode.LOCAL_MMS
            else -> MessageDeliveryMode.IMESSAGE
        }
    )
}

// Retry as SMS (fallback)
override suspend fun retryAsSms(messageGuid: String): Result<MessageEntity> = safeCall {
    val message = messageDao.getMessageByGuid(messageGuid)

    // Soft delete the original failed message
    messageDao.softDeleteMessage(messageGuid)

    // Enter fallback mode
    chatFallbackTracker.enterFallbackMode(message.chatGuid, FallbackReason.IMESSAGE_FAILED)

    // Re-send via SMS/MMS
    sendUnified(chatGuid = message.chatGuid, text = message.text,
                deliveryMode = MessageDeliveryMode.LOCAL_SMS)
}
```

### BlueBubbles

**File:** `message_holder.dart:739-760`

```dart
TextButton(
  child: Text("Retry"),
  onPressed: () async {
    // Remove original failed message
    service.removeMessage(message);
    Message.delete(message.guid!);

    // Re-prepare and send with new temp GUID
    final messages = await ah.prepMessage(chat, message, ...);
    // Sends create new temp-xxx message
  },
),
TextButton(
  child: Text("Delete"),
  onPressed: () async {
    Message.delete(message.guid!);
    service.removeMessage(message);
  },
),
```

### Analysis

| Feature | BothBubbles | BlueBubbles |
|---------|-------------|-------------|
| Retry same message | Resets error, re-sends | Deletes and creates new |
| SMS fallback | Built-in `retryAsSms()` | Not integrated |
| Message preservation | Keeps original GUID | Creates new temp GUID |
| Fallback tracking | `ChatFallbackTracker` | Manual |

**BothBubbles Superiority:**
- Integrated SMS fallback with `retryAsSms()` method
- `ChatFallbackTracker` for automatic iMessage→SMS fallback mode
- Preserves message GUID on retry (better continuity)
- WorkManager for reliable background retry

**BlueBubbles Approach:** Simpler delete-and-recreate, but loses message identity.

---

## 6. UI Representation

### BothBubbles

**File:** `MessageDeliveryIndicators.kt`

```kotlin
@Composable
internal fun DeliveryIndicator(
    isSent: Boolean,
    isDelivered: Boolean,
    isRead: Boolean,
    hasError: Boolean,
    isFromMe: Boolean,
    modifier: Modifier = Modifier
) {
    val icon = when {
        hasError -> Icons.Default.Error  // Red error icon
        isRead -> Icons.Default.DoneAll
        isDelivered -> Icons.Default.DoneAll
        isSent -> Icons.Default.Check
        else -> Icons.Default.Schedule  // Clock for pending
    }
    // ...
}
```

**Message Status Enum:**
```kotlin
enum class MessageStatus {
    NONE,       // Not from me
    SENDING,    // temp- prefix
    SENT,       // error = 0, no delivery confirmation
    DELIVERED,  // dateDelivered set
    READ,       // dateRead set
    FAILED      // error != 0
}
```

### BlueBubbles

**File:** `message_holder.dart`

```dart
if (message.error > 0 || message.guid!.startsWith("error-")) {
  return IconButton(
    icon: Icon(
      iOS ? CupertinoIcons.exclamationmark_circle : Icons.error_outline,
      color: context.theme.colorScheme.error,
    ),
    onPressed: () {
      showDialog(
        context: context,
        builder: (context) => AlertDialog(
          title: Text("Message failed to send"),
          content: Text("Error ($errorCode): $errorText"),
          actions: [
            TextButton(child: Text("Retry"), onPressed: () => ...),
            TextButton(child: Text("Delete"), onPressed: () => ...),
          ],
        ),
      );
    },
  );
}
```

### Analysis

| Feature | BothBubbles | BlueBubbles |
|---------|-------------|-------------|
| Error display | Icon only | Icon + tap for details |
| Error code shown | No | Yes (in dialog) |
| Specific error messages | Generic "Failed" | Error 22 = "Recipient not on iMessage" |
| Retry action | Separate UI flow | Inline dialog |

**BlueBubbles Superiority:**
- Shows specific error code and message in dialog
- Special handling for error 22 (not registered with iMessage)
- Inline retry/delete actions in error dialog

**BothBubbles Improvement Opportunity:** Add error dialog with specific error codes and messages.

---

## 7. Summary: Strengths & Improvement Opportunities

### BothBubbles Strengths

1. **Dedicated socket event** (`message-send-error`) for faster failure detection
2. **Formal state machine** prevents invalid state transitions
3. **Integrated SMS fallback** with `retryAsSms()` and `ChatFallbackTracker`
4. **Clean error storage** in dedicated database columns
5. **WorkManager** for reliable background retry

### BlueBubbles Strengths

1. **Granular error codes** (22 = not registered, 1000 = no connection, etc.)
2. **Specific user-facing messages** for different failure types
3. **Error dialog with details** showing exact error code and message
4. **Self-documenting GUIDs** (`error-{message}` visible in logs)

### Improvement Opportunities for BothBubbles

1. **Add granular error codes:**
   ```kotlin
   sealed class MessageErrorCode(val code: Int) {
       object NoError : MessageErrorCode(0)
       object Timeout : MessageErrorCode(4)
       object RecipientNotOnIMessage : MessageErrorCode(22)  // Critical!
       object NoConnection : MessageErrorCode(1000)
       object BadRequest : MessageErrorCode(1001)
       object ServerError : MessageErrorCode(1002)
   }
   ```

2. **Special handling for error code 22:**
   - Parse server response for error code 22
   - Display "Recipient is not registered with iMessage"
   - Offer automatic SMS fallback for this specific error

3. **Enhanced error dialog in UI:**
   ```kotlin
   @Composable
   fun FailedMessageDialog(
       errorCode: Int,
       errorMessage: String,
       onRetry: () -> Unit,
       onRetryAsSms: () -> Unit,  // Offer if phone number available
       onDelete: () -> Unit
   )
   ```

4. **Log error details in GUID** (optional, for debugging):
   - Keep current approach as primary
   - Optionally append error hint to temp GUID for log visibility

---

## 8. Recommended Changes

### High Priority

1. **Parse error code 22 from server responses** and surface to UI
   - This is the most common "failure" that users can act on (switch to SMS)

2. **Add error detail dialog** when user taps failed message indicator
   - Show error code and human-readable message
   - Provide Retry, Retry as SMS, Delete options

### Medium Priority

3. **Expand `MessageError` sealed class** with specific error types
4. **Track error codes** from server in addition to boolean error flag

### Low Priority (Nice to Have)

5. **Timeout-specific error messages** (connect vs send vs receive timeout)
6. **Error analytics** to track common failure patterns
