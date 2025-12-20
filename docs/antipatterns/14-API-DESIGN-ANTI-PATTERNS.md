# API Design Anti-Patterns

**Scope:** Parameters, return values, naming, interfaces, contracts

---

## High Severity Issues

### 1. Too Many Parameters (15 Parameters!)

**Location:** `services/notifications/Notifier.kt` (Lines 35-51)

**Issue:**
```kotlin
fun showMessageNotification(
    chatGuid: String,
    chatTitle: String,
    messageText: String,
    messageGuid: String,
    senderName: String?,
    senderAddress: String? = null,
    isGroup: Boolean = false,
    avatarUri: String? = null,
    linkPreviewTitle: String? = null,
    linkPreviewDomain: String? = null,
    participantNames: List<String> = emptyList(),
    participantAvatarPaths: List<String?> = emptyList(),
    subject: String? = null,
    attachmentUri: android.net.Uri? = null,
    attachmentMimeType: String? = null
)
```

**Problem:** 15 parameters exceeds recommended 5-6 max.

**Fix:**
```kotlin
data class MessageNotificationParams(
    val chatGuid: String,
    val chatTitle: String,
    val messageText: String,
    // ... all fields
)

fun showMessageNotification(params: MessageNotificationParams)
```

---

### 2. Magic Number Default (-1 for "Not Set")

**Location:** `services/messaging/MessageSender.kt` (Lines 36-47)

**Issue:**
```kotlin
suspend fun sendUnified(
    chatGuid: String,
    text: String,
    subscriptionId: Int = -1,  // Magic number!
    // ...
)
```

**Problem:** `-1` means "not set" but this isn't obvious from the API.

**Fix:**
```kotlin
subscriptionId: Int? = null,  // null = use default
```

---

### 3. Return -1 for "Not Found"

**Locations:**
- `util/text/TextNormalization.kt` (Line 128)
- `services/sync/SyncBackoffStrategy.kt` (Line 136)

**Issue:**
```kotlin
private fun findNormalizedMatchStart(...): Int {
    // ...
    return -1  // "Not found"
}

fun getTimeSinceLastSuccess(): Long {
    return if (lastSuccess > 0) {
        System.currentTimeMillis() - lastSuccess
    } else {
        -1  // "Never synced"
    }
}
```

**Fix:**
```kotlin
private fun findNormalizedMatchStart(...): Int? {
    // ...
    return null
}

fun getTimeSinceLastSuccess(): Long? {
    val lastSuccess = lastSuccessTime.get()
    return if (lastSuccess > 0) System.currentTimeMillis() - lastSuccess else null
}
```

---

### 4. Duplicate Methods with Different Names

**Location:** `data/repository/ChatRepository.kt` (Lines 38-52)

**Issue:**
```kotlin
fun observeAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()
fun getAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()  // DUPLICATE!

suspend fun getChat(guid: String): ChatEntity? = chatDao.getChatByGuid(guid)
suspend fun getChatByGuid(guid: String): ChatEntity? = chatDao.getChatByGuid(guid)  // DUPLICATE!
```

**Problem:** Two methods doing identical work creates API confusion.

**Fix:** Keep only one with consistent naming.

---

### 5. UI Type Leaked to Data Layer

**Location:** `data/repository/ChatRepository.kt` (Lines 96-126)

**Issue:**
```kotlin
suspend fun getFilteredGroupChatGuids(
    filter: com.bothbubbles.ui.conversations.ConversationFilter,  // UI TYPE!
    categoryFilter: String?,
    limit: Int,
    offset: Int
): List<String>
```

**Problem:** Data layer imports from UI layer, breaking clean architecture.

**Fix:**
```kotlin
// Create domain model in data layer
enum class ChatFilterType {
    ALL, UNREAD, SPAM, UNKNOWN_SENDERS, KNOWN_SENDERS
}

// Map at UI layer boundary
```

---

### 6. Fat Interface - Too Many Responsibilities

**Location:** `services/notifications/Notifier.kt` (Lines 14-114)

**Issue:** `Notifier` interface has 11 methods handling:
- Message notifications
- System notifications
- FaceTime notifications
- App badge updates

**Fix:** Split by responsibility:
```kotlin
interface MessageNotifier {
    fun showMessageNotification(params: MessageNotificationParams)
    fun cancelNotification(chatGuid: String)
}

interface SystemNotifier {
    fun showSyncCompleteNotification(messageCount: Int)
    fun showServerUpdateNotification(version: String)
}

interface CallNotifier {
    fun showFaceTimeNotification(...)
    fun dismissFaceTimeNotification(...)
}
```

---

## Medium Severity Issues

### 7. Boolean Parameters

**Location:** `services/messaging/sender/SmsSenderStrategy.kt` (Lines 77-78)

**Issue:**
```kotlin
private suspend fun sendMms(options: SendOptions, isGroup: Boolean): SendResult
```

**Problem:** `sendMms(options, true)` - what does `true` mean?

**Fix:**
```kotlin
enum class ChatType { SINGLE, GROUP }
private suspend fun sendMms(options: SendOptions, chatType: ChatType): SendResult
```

---

### 8. Magic String Constants

**Location:** `data/repository/ChatRepository.kt` (Lines 515-517)

**Issue:**
```kotlin
suspend fun updatePreferredSendMode(chatGuid: String, mode: String?, manuallySet: Boolean)
// mode can be "imessage", "sms", or null - undocumented magic strings
```

**Fix:**
```kotlin
enum class SendMode { IMESSAGE, SMS, AUTO }
suspend fun updatePreferredSendMode(chatGuid: String, mode: SendMode?, manuallySet: Boolean)
```

---

### 9. Callback Mixed with Result

**Location:** `data/repository/AttachmentRepository.kt` (Lines 120-176)

**Issue:**
```kotlin
suspend fun downloadAttachment(
    attachmentGuid: String,
    onProgress: ((Float) -> Unit)? = null  // Callback
): Result<File>  // Plus Result wrapper
```

**Problem:** Mixes callback pattern with Result return type.

**Fix:**
```kotlin
// Option 1: Use Flow for progress
suspend fun downloadAttachment(attachmentGuid: String): Flow<DownloadProgress>

// Option 2: Separate progress StateFlow
val downloadProgress: StateFlow<Map<String, Float>>
suspend fun downloadAttachment(attachmentGuid: String): Result<File>
```

---

### 10. Inconsistent Result Wrapping

**Location:** `data/repository/ChatRepository.kt` (Lines 268-493)

**Issue:**
```kotlin
suspend fun updateDisplayName(chatGuid: String, displayName: String?) {
    chatDao.updateDisplayName(chatGuid, displayName)
}  // No Result wrapper

suspend fun setPinned(guid: String, isPinned: Boolean): Result<Unit> = runCatching {
    chatDao.updatePinStatus(guid, isPinned, pinIndex)
}  // Wrapped in Result
```

**Problem:** Inconsistent - some mutations return Result, others Unit.

**Fix:** Standardize all mutations to return `Result<Unit>`.

---

### 11. Unused Parameter in Method

**Location:** `data/repository/ChatRepository.kt` (Lines 504-506)

**Issue:**
```kotlin
suspend fun updateLastMessage(chatGuid: String, text: String?, date: Long) {
    chatDao.updateLatestMessageDate(chatGuid, date)
    // `text` parameter is NEVER USED!
}
```

**Fix:** Either use the parameter or remove it.

---

## Summary Table

| Issue | Severity | Category | File |
|-------|----------|----------|------|
| 15 parameters | HIGH | Parameter | Notifier.kt |
| Magic -1 default | HIGH | Parameter | MessageSender.kt |
| Return -1 for not found | HIGH | Return | TextNormalization.kt |
| Duplicate methods | HIGH | Naming | ChatRepository.kt |
| UI type in data layer | HIGH | Layering | ChatRepository.kt |
| Fat interface | HIGH | Interface | Notifier.kt |
| Boolean parameter | MEDIUM | Parameter | SmsSenderStrategy.kt |
| Magic strings | MEDIUM | Parameter | ChatRepository.kt |
| Callback + Result | MEDIUM | API Style | AttachmentRepository.kt |
| Inconsistent Result | MEDIUM | Consistency | ChatRepository.kt |
| Unused parameter | MEDIUM | Contract | ChatRepository.kt |
