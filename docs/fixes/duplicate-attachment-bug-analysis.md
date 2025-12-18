# Duplicate Attachment Bug Analysis

**Date**: 2024-12-17
**Issue**: When sending a photo, user sees broken attachment + duplicate image after server confirmation

## Observed Behavior

1. User sends photo to "Squad Goals" chat at 5:09pm
2. Immediately after send: broken/missing attachment thumbnail appears
3. Shortly after: the same image appears again (correctly) once server confirms
4. Result: Two message bubbles for one sent photo

## Database Evidence

Two messages exist for the same send:
```
ID    | GUID                                      | date_created
57485 | temp-69f3a9dc-ccae-453d-92e7-2c727464725a | 1766012953568 (temp)
57486 | 04DDB019-6069-4854-88B2-4A63DEE33A51      | 1766012970879 (server)
```

Two attachment sets:
```
Temp attachment:
  guid: temp-69f3a9dc-ccae-453d-92e7-2c727464725a-att-0
  localPath: file:///data/user/0/com.bothbubbles.messaging/files/pending_attachments/temp-...-att-0.jpg
  webUrl: NULL  ← No fallback!
  transferState: UPLOADED

Server attachment:
  guid: 7A223BB4-2F5F-445B-89AD-25B03D9BC1C3
  localPath: /data/user/0/.../attachments/7A223BB4-...jpg
  webUrl: https://msg.graysons.network/api/v1/attachment/.../download
  transferState: DOWNLOADING (progress=1.0)
```

---

## Root Cause 1: Race Condition in `replaceGuid`

### Location
- `IMessageSenderStrategy.kt:294-305`
- `MessageDao.kt:524-534`

### Flow

1. User sends photo → temp message `temp-{uuid}` created in DB
2. WorkManager uploads attachment, API returns server GUID
3. **RACE CONDITION**: Socket event arrives with same server GUID at nearly the same time

### Race Timeline

```
T0: HTTP response received with serverGuid="04DDB019..."
T1: Socket event received with same serverGuid="04DDB019..."

Thread A (HTTP):                         Thread B (Socket):
├─ replaceGuid() called                  │
├─ getMessageByGuid("04DDB019...")       ├─ handleIncomingMessage()
│  → returns NULL                        ├─ getMessageByGuid("04DDB019...")
│                                        │  → returns NULL
│                                        ├─ insertMessage() → SUCCESS
├─ replaceGuidDirect(temp, server)       │
│  → FAILS! UNIQUE constraint            │
│    (serverGuid now exists)             │
├─ Exception caught silently             │
└─ Temp message NOT deleted              └─ Server message inserted
```

### Code Path

```kotlin
// IMessageSenderStrategy.kt:294-305
lastResponse?.let { serverMessage ->
    try {
        val tempAttachments = attachmentDao.getAttachmentsForMessage(tempGuid)
        val preservedLocalPaths = tempAttachments.mapNotNull { att ->
            att.localPath?.takeUnless { it.contains("/pending_attachments/") }
        }
        messageDao.replaceGuid(tempGuid, serverMessage.guid)
        syncOutboundAttachments(serverMessage, preservedLocalPaths)
    } catch (e: Exception) {
        Timber.w("Non-critical error: ${e.message}")  // ← SILENT FAILURE
    }
    return SendResult.Success(serverMessage.toEntity(options.chatGuid))
}
```

```kotlin
// MessageDao.kt:524-534
@Transaction
suspend fun replaceGuid(tempGuid: String, newGuid: String) {
    val existingMessage = getMessageByGuid(newGuid)
    if (existingMessage != null) {
        // Socket already inserted - delete temp
        deleteMessage(tempGuid)
    } else {
        // Normal case - update temp guid to server guid
        replaceGuidDirect(tempGuid, newGuid)  // ← Can fail with UNIQUE constraint!
    }
}
```

### Why It Fails

The `replaceGuid` transaction checks if server message exists, but between the check and the update:
1. Socket thread inserts the server message
2. `replaceGuidDirect` tries to UPDATE temp message's guid to a guid that NOW exists
3. UNIQUE constraint violation on `messages.guid`
4. Exception bubbles up, caught by outer try-catch as "Non-critical error"
5. Temp message is NOT deleted

---

## Root Cause 2: Temp Attachment Has No webUrl Fallback

### Why Broken Image Appears

1. Temp attachment is created with `localPath` pointing to pending_attachments folder
2. After send completes, pending attachment files are cleaned up (normal behavior)
3. UI tries to render temp attachment:
   - Checks `localPath` → file doesn't exist
   - Falls back to `webUrl` → **NULL** (temp attachments never get web URLs)
   - Shows broken image icon

### Relevant Code

```kotlin
// ImageAttachment.kt (regular) - no file existence check
val imageUrl = attachment.localPath ?: attachment.webUrl

// BorderlessImageAttachment.kt - has file existence check but same problem
if (file != null && !file.exists()) {
    attachment.webUrl  // ← Still NULL for temp attachments!
} else {
    path
}
```

---

## Summary Table

| Symptom | Cause |
|---------|-------|
| Broken attachment immediately after send | Temp attachment's local file cleaned up, no webUrl fallback |
| Same image appeared again once received | Server message also in DB (race condition), has working webUrl |
| Two bubbles for one sent photo | `replaceGuid` failed silently due to UNIQUE constraint race |

---

## Recommended Fixes

### Fix 1: Handle Race in `MessageDao.kt` (Encapsulate Logic)

Instead of handling the exception in the service layer, handle it in the DAO where the race occurs. Wrap the `replaceGuidDirect` call in a try-catch block.

```kotlin
// In MessageDao.kt

@Transaction
suspend fun replaceGuid(tempGuid: String, newGuid: String) {
    val existingMessage = getMessageByGuid(newGuid)
    if (existingMessage != null) {
        deleteMessage(tempGuid)
    } else {
        try {
            replaceGuidDirect(tempGuid, newGuid)
        } catch (e: Exception) {
            // Race condition hit: Socket inserted message while we were checking.
            // The UNIQUE constraint failed, so we know the server message exists.
            // Safe to delete temp.
            deleteMessage(tempGuid)
        }
    }
}
```

### Fix 2: Ensure Attachments Sync in `IMessageSenderStrategy.kt`

The most critical missing piece is that **`syncOutboundAttachments` is skipped** when the exception is caught. This causes the server message to try downloading the attachment (which takes time/data) instead of using the local file we just uploaded.

We must ensure `syncOutboundAttachments` runs even if the race condition occurred.

```kotlin
// In IMessageSenderStrategy.kt

lastResponse?.let { serverMessage ->
    // 1. Capture local paths BEFORE any DB operations
    val tempAttachments = attachmentDao.getAttachmentsForMessage(tempGuid)
    val preservedLocalPaths = tempAttachments.mapNotNull { att ->
        att.localPath?.takeUnless { it.contains("/pending_attachments/") }
    }

    // 2. Perform the replacement (now safe if we apply the DAO fix above)
    try {
        messageDao.replaceGuid(tempGuid, serverMessage.guid)
    } catch (e: Exception) {
        // Fallback if DAO fix isn't applied or something else fails
        Timber.e(e, "Failed to replace GUID")
        messageDao.deleteMessage(tempGuid)
    }

    // 3. ALWAYS sync attachments, linking the local file to the server message
    // This prevents the "DOWNLOADING" state and duplicate bubbles
    syncOutboundAttachments(serverMessage, preservedLocalPaths)

    return SendResult.Success(serverMessage.toEntity(options.chatGuid))
}
```

### Fix 3: Clean Up Orphaned Temp Messages on Sync

Add a cleanup pass that:
1. Finds messages with `temp-` prefix guids
2. Checks if a corresponding server message exists (by matching chat, timestamp, content)
3. Deletes the temp if duplicate found

### Fix 4: Add webUrl to Temp Attachments

When `syncOutboundAttachments` runs successfully, also update or delete orphaned temp attachments that share the same message context.

---

## Fix 5: Cleanup Orphaned Temp Messages (Retroactive Fix)

For users who already have orphaned temp messages in their database, we need a cleanup mechanism.

### Proposed Cleanup Logic

Run on app startup or as a one-time migration:

```kotlin
suspend fun cleanupOrphanedTempMessages() {
    // Find all messages with temp- prefix that are older than 5 minutes
    // (legitimate temp messages should be replaced within seconds)
    val staleThreshold = System.currentTimeMillis() - (5 * 60 * 1000)

    val orphanedTemps = messageDao.getOrphanedTempMessages(staleThreshold)

    for (tempMessage in orphanedTemps) {
        // Check if a "real" message exists with same chat + similar timestamp
        val possibleDuplicate = messageDao.findMatchingMessage(
            chatGuid = tempMessage.chatGuid,
            text = tempMessage.text,
            isFromMe = true,
            dateCreated = tempMessage.dateCreated,
            toleranceMs = 30_000  // 30 second window
        )

        if (possibleDuplicate != null && !possibleDuplicate.guid.startsWith("temp-")) {
            // Found the real message - safe to delete temp
            Timber.i("Cleaning up orphaned temp message: ${tempMessage.guid}")
            messageDao.deleteMessage(tempMessage.guid)
        }
    }
}
```

### Required DAO Query

```kotlin
@Query("""
    SELECT * FROM messages
    WHERE guid LIKE 'temp-%'
    AND is_from_me = 1
    AND date_created < :beforeTimestamp
""")
suspend fun getOrphanedTempMessages(beforeTimestamp: Long): List<MessageEntity>
```

### When to Run

1. **App startup** - in `BothBubblesApp.onCreate()` or a startup worker
2. **After sync completes** - in `BackgroundSyncWorker`
3. **One-time migration** - as a Room migration step

### Also Clean Up Orphaned Temp Attachments

Attachments with `temp-` prefix guids whose parent message no longer exists:

```kotlin
@Query("""
    DELETE FROM attachments
    WHERE guid LIKE 'temp-%'
    AND message_guid NOT IN (SELECT guid FROM messages)
""")
suspend fun deleteOrphanedTempAttachments(): Int
```

---

## Files Involved

- `app/src/main/kotlin/com/bothbubbles/services/messaging/sender/IMessageSenderStrategy.kt`
- `app/src/main/kotlin/com/bothbubbles/data/local/db/dao/MessageDao.kt`
- `app/src/main/kotlin/com/bothbubbles/data/repository/PendingMessageRepository.kt`
- `app/src/main/kotlin/com/bothbubbles/services/messaging/IncomingMessageHandler.kt`
- `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/ImageAttachment.kt`
- `app/src/main/kotlin/com/bothbubbles/BothBubblesApp.kt` (for startup cleanup)
