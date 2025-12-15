# Bug Investigation: Empty Bubble on Attachment Send

## Reported Symptoms

1. **Send attachment** → small, empty blue bubble appears immediately (no content)
2. **~5 seconds** → media appears after the empty bubble (bubble persists)
3. **~15 seconds** → image thumbnail breaks
4. **Restart app** → empty bubble persists, thumbnail still broken
5. **Navigate away and back** → thumbnail renders correctly

**Key observation**: BlueBubbles server only shows the attachment (no empty message), so this is client-side only.

---

## Root Cause Analysis

### The Race Condition

The bug stems from a race condition between the **HTTP response** and **Socket.IO event** when sending attachments.

#### Normal Flow (No Race)

```
1. User sends attachment
2. Create temp message (guid: temp-xxx, hasAttachments: true)
3. Create temp attachment (guid: temp-xxx-att-0, localPath: content://...)
4. Upload to server
5. HTTP response returns with serverGuid
6. replaceGuid(temp-xxx, serverGuid) - updates message GUID
7. syncOutboundAttachments() - preserves localPath from temp attachment
8. Result: Single message with working attachment
```

#### Race Condition Flow (Socket Wins)

```
1. User sends attachment
2. Create temp message (guid: temp-xxx, hasAttachments: true)
3. Create temp attachment (guid: temp-xxx-att-0, localPath: content://...)
4. Upload to server
5. *** SOCKET EVENT ARRIVES FIRST ***
   - handleIncomingMessage(serverGuid)
   - Checks if serverGuid exists → NO
   - Inserts NEW message with serverGuid
   - syncIncomingAttachments() creates attachments WITHOUT localPath
6. HTTP response finally processes
   - replaceGuid(temp-xxx, serverGuid)
   - Sees serverGuid already exists (from socket)
   - DELETES temp-xxx message
   - CASCADE DELETES temp-xxx-att-0 attachment!
7. syncOutboundAttachments(tempMessageGuid: temp-xxx)
   - getAttachmentsForMessage(temp-xxx) returns EMPTY (just got cascade deleted!)
   - tempLocalPaths is empty - nothing to preserve
   - Inserts attachments with localPath = null
8. Result: Attachment has no localPath, thumbnail relies on webUrl
```

### Why Each Symptom Occurs

| Symptom | Explanation |
|---------|-------------|
| Empty bubble appears | Socket creates message before attachments are synced, OR brief moment where message exists but attachment query hasn't completed |
| Bubble persists | The temp message or orphaned message record remains in database |
| Thumbnail breaks after ~15s | The `content://` URI has temporary Android permission that expires |
| Thumbnail works after navigation | Fresh database query uses `webUrl` fallback instead of stale `content://` |
| Persists after restart | Database has the problematic state persisted |

---

## Code Paths Involved

### Message Creation
- [MessageSendingService.kt:559-595](app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendingService.kt#L559-L595)
  - Creates temp message with `guid = temp-xxx`
  - Creates temp attachments with `localPath = uri.toString()`

### Socket Message Handling
- [IncomingMessageHandler.kt:49-86](app/src/main/kotlin/com/bothbubbles/services/messaging/IncomingMessageHandler.kt#L49-L86)
  - Checks for existing message by `serverGuid` (doesn't know about `temp-xxx`)
  - Creates attachments via `syncIncomingAttachments()` WITHOUT localPath preservation

### GUID Replacement
- [MessageDao.kt:462-473](app/src/main/kotlin/com/bothbubbles/data/local/db/dao/MessageDao.kt#L462-L473)
  - If `serverGuid` exists, DELETES `temp-xxx` instead of updating
  - CASCADE deletes any attachments with `message_guid = temp-xxx`

### Attachment Sync (After Send)
- [MessageSendingService.kt:972-1018](app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendingService.kt#L972-L1018)
  - Tries to get temp attachments to preserve localPath
  - But they're already CASCADE deleted if race condition occurred!

### Foreign Key Cascade
- [AttachmentEntity.kt:19-24](app/src/main/kotlin/com/bothbubbles/data/local/db/entity/AttachmentEntity.kt#L19-L24)
  - `onDelete = ForeignKey.CASCADE` on `message_guid`

---

## Database Verification

To confirm the issue, query the database after reproducing:

```bash
# Pull database from device
adb shell "run-as com.bothbubbles cat /data/data/com.bothbubbles/databases/bothbubbles.db" > /tmp/bothbubbles.db

# Check recent messages
sqlite3 /tmp/bothbubbles.db "
SELECT guid, text, has_attachments, date_created
FROM messages
WHERE is_from_me = 1
ORDER BY date_created DESC
LIMIT 10;
"

# Check attachments for recent messages
sqlite3 /tmp/bothbubbles.db "
SELECT a.guid, a.message_guid, a.local_path, a.web_url, a.transfer_state
FROM attachments a
JOIN messages m ON a.message_guid = m.guid
WHERE m.is_from_me = 1
ORDER BY m.date_created DESC
LIMIT 10;
"

# Look for orphaned temp messages
sqlite3 /tmp/bothbubbles.db "
SELECT * FROM messages WHERE guid LIKE 'temp-%';
"
```

### What to Look For

1. **Duplicate messages?** - Same chat, same timestamp, different GUIDs
2. **Missing localPath?** - Attachments with `local_path = NULL` but should have content:// URI
3. **Orphaned temp messages?** - Messages with `guid LIKE 'temp-%'` that weren't cleaned up
4. **Mismatched hasAttachments?** - Message has `has_attachments = 1` but no attachment records

---

## Potential Fixes

### Option 1: Prevent Race via Deduplication

Modify `handleIncomingMessage` to check for temp messages by matching on:
- Same `chatGuid`
- Same `dateCreated` (within tolerance)
- `isFromMe = true`

```kotlin
// Before inserting, check if we have a pending temp message
val tempMessage = messageDao.findTempMessageForOutbound(chatGuid, messageDto.dateCreated)
if (tempMessage != null) {
    // This is our sent message coming back - update it instead of creating new
    messageDao.replaceGuid(tempMessage.guid, messageDto.guid)
    return tempMessage.copy(guid = messageDto.guid)
}
```

### Option 2: Preserve localPath Before Cascade

Move localPath preservation to BEFORE `replaceGuid`:

```kotlin
// Get temp attachments BEFORE replaceGuid might cascade delete them
val tempAttachments = attachmentDao.getAttachmentsForMessage(tempGuid)
val tempLocalPaths = tempAttachments.mapNotNull { it.localPath }

// Now safe to replace
messageDao.replaceGuid(tempGuid, serverMessage.guid)

// Sync with preserved paths
syncOutboundAttachments(serverMessage, preservedLocalPaths = tempLocalPaths)
```

### Option 3: Copy File to Permanent Location

Instead of storing `content://` URIs (which expire), copy the file to app storage during send:

```kotlin
val permanentPath = copyToAppStorage(uri)
val tempAttachment = AttachmentEntity(
    localPath = permanentPath,  // Permanent file path, not content:// URI
    ...
)
```

---

## Related Files

- `MessageSendingService.kt` - Send flow, temp message creation
- `IncomingMessageHandler.kt` - Socket message handling
- `MessageDao.kt` - `replaceGuid()` logic
- `AttachmentDao.kt` - Attachment queries
- `AttachmentEntity.kt` - Foreign key definition
- `AttachmentContent.kt` - UI rendering, uses `localPath ?: webUrl`
