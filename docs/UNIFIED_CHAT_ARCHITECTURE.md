# Unified Chat Architecture

This document describes how BothBubbles unifies iMessage and SMS conversations into a single conversation list.

## Overview

The app uses a **single-entity architecture** where:
- `UnifiedChatEntity` represents a logical conversation (stored in `unified_chats` table)
- `ChatEntity` represents protocol-specific channels (iMessage, SMS, MMS) linked via `unified_chat_id`
- `MessageEntity` messages link to unified chats via `unified_chat_id` for pagination

This enables merging iMessage and SMS conversations for the same contact while maintaining message history from both protocols.

---

## Data Model

### UnifiedChatEntity (`unified_chats` table)

**Purpose:** Single source of truth for conversation-level state

**Key Fields:**
```
id                    - Discord-style snowflake (e.g., "17032896543214827")
normalizedAddress     - UNIQUE: Normalized phone/email for 1:1 chats
                        For groups: hash or group GUID
sourceId              - Preferred protocol channel (e.g., "iMessage;-;+1234567890")
                        Used to determine send mode and display info

# Cached Latest Message (populated on message arrival)
latestMessageDate     - Timestamp for sorting
latestMessageText     - Text preview shown in list
latestMessageGuid     - Message GUID
latestMessageIsFromMe - Sent vs received toggle
latestMessageHasAttachments - Triggers attachment preview
latestMessageSource   - IMESSAGE | LOCAL_SMS | LOCAL_MMS
latestMessageDateDelivered/Read - For status icons
latestMessageError    - Error code if failed

# Display
displayName           - Chat name
customAvatarPath      - User-set avatar
serverGroupPhotoPath  - iMessage group photo

# State
unreadCount           - Total unread messages
isPinned/pinIndex     - Pinning
isArchived/isSpam     - Filtering
muteType/snoozeUntil  - Notification settings
isGroup               - True for group chats (no merging)
isSmsFallback         - iMessage failed, using SMS
preferredSendMode     - "imessage" | "sms" | null (auto)
```

### ChatEntity (`chats` table)

**Purpose:** Protocol-specific channel (multiple can link to one unified chat)

```
id              - Row ID
guid            - Protocol identifier (e.g., "iMessage;-;+1234567890" or "sms;-;+1234567890")
unifiedChatId   - Foreign key to unified_chats.id (NULL for groups)
chatIdentifier  - Phone/email from server
displayName     - For group chats only
isGroup         - Prevents linking to unified chat
```

**Example Structure:**
```
UnifiedChatEntity (id="123")
├── normalizedAddress = "+14155551234"
├── sourceId = "iMessage;-;+14155551234"
│
└── Linked ChatEntities:
    ├── ChatEntity (guid="iMessage;-;+14155551234", unifiedChatId="123")
    └── ChatEntity (guid="sms;-;+14155551234", unifiedChatId="123")
```

### MessageEntity (`messages` table)

```
guid            - Message identifier
chatGuid        - Which protocol channel
unifiedChatId   - Which unified conversation (for pagination)
handleId        - Sender's handle ID
senderAddress   - Sender's phone/email (for group chats)
text            - Message content
dateCreated     - Sent timestamp
isFromMe        - Sent vs received
hasAttachments  - Attachment flag
messageSource   - IMESSAGE | LOCAL_SMS | LOCAL_MMS
```

---

## Unified Chat Creation Flow

When a message arrives:

```
IncomingMessageHandler.handleIncomingMessage()
  ↓
resolveUnifiedChatId(chatGuid, messageDto)
  ↓
  1. Get the chat entity by guid
  2. If isGroup=true → return NULL (no unification for groups)
  3. If already has unifiedChatId → return it
  4. Extract address from chatIdentifier or handle
  5. Normalize address (strip formatting, lowercase emails)
  6. Call unifiedChatDao.getOrCreate(normalizedAddress, sourceId=chatGuid)
  7. Link chat to unified chat via chatDao.setUnifiedChatId()
```

**Address Normalization:**
```kotlin
fun normalizeAddress(address: String): String {
    return if (address.contains("@")) {
        address.lowercase()  // Email: lowercase
    } else {
        address.replace(Regex("[^0-9+]"), "")  // Phone: digits and + only
    }
}
```

**Thread Safety:**
`getOrCreate()` uses a synchronized transaction with INSERT IGNORE to prevent races.

---

## Conversations List Loading

```
ConversationLoadingDelegate.loadInitialConversations()
  ↓
  1. Parallel queries:
     - unifiedChatRepository.getActiveChats(limit, offset)
     - chatRepository.getGroupChatsPaginated(limit, offset)
     - chatRepository.getNonGroupChatsPaginated(limit, offset)
  ↓
  2. Batch fetch related data (prevents N+1):
     - Get all chat GUIDs for unified chats
     - Get all chats by GUID
     - Get all participants grouped by chat
  ↓
  3. Convert to UI models:
     UnifiedGroupMappingDelegate.unifiedChatToUiModel()
```

### UI Model Conversion

```kotlin
unifiedChatToUiModel(
    unifiedChat: UnifiedChatEntity,
    chatGuids: List<String>,           // All protocol channels
    typingChats: Set<String>,
    chatsMap: Map<String, ChatEntity>,
    participantsMap: Map<String, List<HandleEntity>>
)
```

Steps:
1. Get cached chats from pre-fetched map
2. Use cached latest message data from unified_chats table
3. Select "primary chat" (matching sourceId) for display info
4. **Merge participants from ALL linked chats** ← PROBLEM AREA
5. Build display name with fallback chain
6. Fetch attachments if latest message has them
7. Determine message type for preview

---

## Latest Message Caching

When a message arrives, the unified chat's latest message cache is updated:

```kotlin
unifiedChatDao.updateLatestMessageIfNewer(
    id = unifiedChatId,
    date = message.dateCreated,
    text = message.text,
    guid = message.guid,
    isFromMe = false,
    hasAttachments = message.hasAttachments,
    source = message.messageSource,
    dateDelivered = message.dateDelivered,
    dateRead = message.dateRead,
    error = message.error
)
```

**Important:** Only updates if `date >= currentDate` (newer or equal).

---

## Known Issues and Problem Areas

### Issue 1: Missing Message Previews

**Symptom:** Conversation shows no preview text, or shows old deleted message.

**Cause:** The latest message cache is NOT recalculated when a message is deleted. The UI still shows the cached (now deleted) message.

**Current State:**
- `updateLatestMessageIfNewer()` only fires on new messages
- No mechanism exists to recalculate when latest message is deleted
- If `latestMessageText` is null/empty, `generatePreviewText()` returns empty string

**Files:**
- [UnifiedChatDao.kt](../app/src/main/kotlin/com/bothbubbles/data/local/db/dao/UnifiedChatDao.kt) - `updateLatestMessageIfNewer()`
- [IncomingMessageHandler.kt](../app/src/main/kotlin/com/bothbubbles/services/messaging/IncomingMessageHandler.kt) - cache update

### Issue 2: Address Normalization Inconsistency

**Symptom:** Same contact appears as two separate conversations.

**Cause:** Address normalization differs between code paths:
- SMS: `"(415) 555-1234"` → `"4155551234"` (no leading +)
- iMessage: `"+14155551234"` → `"+14155551234"` (with +)

**Result:** Different normalized addresses → separate unified chats.

**Files:**
- [IncomingMessageHandler.kt](../app/src/main/kotlin/com/bothbubbles/services/messaging/IncomingMessageHandler.kt) - `normalizeAddress()`
- [UnifiedChatRepository.kt](../app/src/main/kotlin/com/bothbubbles/data/repository/UnifiedChatRepository.kt)

### Issue 3: Participant Merging in Groups

**Symptom:** Group chat shows wrong participants, or 1:1 messages appear in group.

**Cause:** `UnifiedGroupMappingDelegate` merges participants from ALL linked chats:
```kotlin
val groupParticipants = chatGuids.flatMap { chatGuid ->
    participantsMap[chatGuid] ?: emptyList()
}.distinctBy { it.id }
```

If a group chat is incorrectly linked to an individual chat (via bad normalization), their participant lists merge.

**Files:**
- [UnifiedGroupMappingDelegate.kt](../app/src/main/kotlin/com/bothbubbles/ui/conversations/delegates/UnifiedGroupMappingDelegate.kt)

### Issue 4: Group Chat Sender Not Identified

**Symptom:** Group chat shows message but doesn't identify who sent it.

**Cause:** `lastMessageSenderName` is never populated in `ConversationUiModel`. The code to extract the sender from the latest message's handle is missing.

**Files:**
- [UnifiedGroupMappingDelegate.kt](../app/src/main/kotlin/com/bothbubbles/ui/conversations/delegates/UnifiedGroupMappingDelegate.kt)

### Issue 5: sourceId Race Condition

**Symptom:** Messages sent to contact use wrong protocol.

**Cause:** When creating a unified chat, `sourceId` is set to whichever chat arrives first. If SMS arrives before iMessage, `sourceId="sms;-;+..."`.

**Current Mitigation:** `ChatFallbackTracker` updates sourceId when iMessage becomes available.

**Files:**
- [ChatFallbackTracker.kt](../app/src/main/kotlin/com/bothbubbles/services/messaging/ChatFallbackTracker.kt)
- [UnifiedChatDao.kt](../app/src/main/kotlin/com/bothbubbles/data/local/db/dao/UnifiedChatDao.kt) - `getOrCreate()`

### Issue 6: UnifiedGroupMappingDelegate Always Sets isGroup=false

**Symptom:** All conversations from unified chats appear as 1:1 even when they're group chats.

**Cause:** Line 164 in `UnifiedGroupMappingDelegate.unifiedChatToUiModel()`:
```kotlin
isGroup = false,  // Hardcoded!
```

This is intentional because unified chats are only for 1:1 conversations. Group chats go through `ChatEntity.toUiModel()` instead.

**However:** If a group chat somehow gets a `unifiedChatId` assigned (bug), it will be processed through the wrong path.

### Issue 7: Messages Associated with Wrong Conversation

**Symptom:** Messages sent to one person appear in another conversation (e.g., messages to Drew showing in group with Harrison, Drew, Nick).

**Possible Causes:**
1. **Wrong chatGuid in message** - Message was inserted with incorrect `chatGuid`
2. **Wrong unifiedChatId** - Message's `unifiedChatId` points to wrong unified chat
3. **UI model using wrong sourceId** - `ConversationUiModel.guid` is set to wrong chat

**Critical Investigation Needed:** Check the `messages` table:
```sql
SELECT guid, chat_guid, unified_chat_id, text, is_from_me, date_created
FROM messages
WHERE chat_guid LIKE '%Drew%' OR unified_chat_id IN (
    SELECT id FROM unified_chats WHERE normalized_address LIKE '%Drew%'
)
ORDER BY date_created DESC
LIMIT 50;
```

### Issue 8: Group Chats Getting unifiedChatId

**Symptom:** Group chat treated as 1:1 conversation.

**Check:** `IncomingMessageHandler.resolveUnifiedChatId()` has this guard:
```kotlin
if (chat?.isGroup == true) return null
```

**But:** If `chat.isGroup` is incorrectly set to `false`, the group chat gets a `unifiedChatId`.

**Investigation:**
```sql
SELECT c.guid, c.is_group, c.unified_chat_id, c.display_name
FROM chats c
WHERE c.unified_chat_id IS NOT NULL AND c.guid LIKE '%+%'
ORDER BY c.latest_message_date DESC
LIMIT 50;
```
(`%+%` in guid indicates group chat format: `iMessage;+;chatXXX`)

---

## DIAGNOSIS: Current Database State (December 2024)

### Critical Issue 1: Messages Missing unified_chat_id

**37,616 messages** have `unified_chat_id = NULL` even though their chat has a `unified_chat_id`.

**Query to verify:**
```sql
SELECT COUNT(*)
FROM messages m
INNER JOIN chats c ON c.guid = m.chat_guid
WHERE m.unified_chat_id IS NULL
  AND c.unified_chat_id IS NOT NULL;
-- Result: 37616
```

**Impact:**
- These messages are not counted when determining the "latest message" for the unified chat
- The conversation list shows stale/wrong previews
- Pagination by `unified_chat_id` misses these messages

**Root Cause:** Migration didn't backfill `unified_chat_id` on existing messages.

**Fix Required:** Migration to update messages:
```sql
UPDATE messages
SET unified_chat_id = (
    SELECT c.unified_chat_id
    FROM chats c
    WHERE c.guid = messages.chat_guid
)
WHERE unified_chat_id IS NULL
  AND chat_guid IN (SELECT guid FROM chats WHERE unified_chat_id IS NOT NULL);
```

### Critical Issue 2: Stale Latest Message Cache

Many unified chats have outdated `latest_message_*` fields:
- Some have `latest_message_text = NULL` even with messages
- Some have older `latest_message_date` than actual latest message

**Examples found:**
- `+14047715155`: `latest_message_text = NULL`, actual: "Okay perfect!"
- `+14047545156`: `latest_message_date` is 4+ hours behind actual

**Root Cause:**
1. Messages inserted with NULL `unified_chat_id` don't trigger `updateLatestMessageIfNewer()`
2. No mechanism to recalculate cache after migration

**Fix Required:** After backfilling `unified_chat_id`, recalculate latest message:
```sql
UPDATE unified_chats
SET latest_message_date = (
    SELECT MAX(m.date_created)
    FROM messages m
    INNER JOIN chats c ON c.guid = m.chat_guid
    WHERE c.unified_chat_id = unified_chats.id
),
latest_message_text = (
    SELECT m.text
    FROM messages m
    INNER JOIN chats c ON c.guid = m.chat_guid
    WHERE c.unified_chat_id = unified_chats.id
    ORDER BY m.date_created DESC
    LIMIT 1
),
-- ... other latest_message_* fields
WHERE id IN (SELECT unified_chat_id FROM chats WHERE unified_chat_id IS NOT NULL);
```

### Issue 3: Group Chat Messages Missing Sender Info

**193 messages** in group chats have NULL `sender_address` AND NULL `handle_id`.

**Query:**
```sql
SELECT COUNT(*) as messages_missing_sender
FROM messages m
INNER JOIN chats c ON c.guid = m.chat_guid
WHERE c.is_group = 1
  AND m.is_from_me = 0
  AND m.sender_address IS NULL
  AND m.handle_id IS NULL;
-- Result: 193
```

**Impact:** Group chat previews can't show "John: Hello" format.

**Root Cause:** Message DTOs from server sometimes lack `handle` field.

### Issue 4: Case-Sensitivity in Chat GUIDs

Multiple chat records exist for same address with different case:
- `sms;-;+18643208089` (lowercase)
- `SMS;-;+18643208089` (uppercase)
- `RCS;-;+18643208089`

All link to the same `unified_chat_id`, which is correct. But queries may need case-insensitive matching.

### Summary of Data Quality Issues

| Issue | Count | Impact |
|-------|-------|--------|
| Messages missing `unified_chat_id` | 37,616 | Stale previews, missing in pagination |
| Unified chats with stale latest_message | Many | Wrong preview text/time |
| Group messages missing sender | 193 | Can't identify sender |
| Total 1:1 messages with NULL unified_chat_id | 7,627 | Not associated with unified conversation |

---

## Fixes Applied (December 2024)

The following bugs were identified and fixed:

### Fix 1: Outgoing Messages Now Set unifiedChatId

**Files Changed:**
- `PendingMessageRepository.kt` - Local echo messages now include `unifiedChatId`
- `IMessageSenderStrategy.kt` - Both `sendTextOnly()` and `sendWithAttachments()` now set `unifiedChatId`
- `HeadlessSmsSendService.kt` - Quick reply messages now include `unifiedChatId`
- `SmsSendService.kt` - Edge case message creations now include `unifiedChatId`
- `MmsSendService.kt` - Edge case message creations now include `unifiedChatId`

**Pattern Applied:**
```kotlin
// Before creating MessageEntity, look up the chat's unifiedChatId
val chat = chatDao.getChatByGuid(chatGuid)
val unifiedChatId = chat?.unifiedChatId

val message = MessageEntity(
    guid = ...,
    chatGuid = chatGuid,
    unifiedChatId = unifiedChatId,  // Now included!
    ...
)
```

### Fix 2: Group Photos Now Work for Group Chats

**Problem:** Group photos were stored on `UnifiedChatEntity`, but group chats don't use unified chats.

**Solution:**
1. Added `serverGroupPhotoPath` and `serverGroupPhotoGuid` fields to `ChatEntity`
2. Created database migration 54→55 to add the new columns
3. Updated `GroupPhotoSyncManager` to:
   - Use `ChatEntity` for group chats (`chat.isGroup == true`)
   - Use `UnifiedChatEntity` for 1:1 chats (fallback path)

**Files Changed:**
- `ChatEntity.kt` - Added group photo fields
- `ChatUpdateDao.kt` - Added `updateServerGroupPhoto()` method
- `DatabaseMigrations.kt` - Added MIGRATION_54_55
- `BothBubblesDatabase.kt` - Bumped version to 55
- `GroupPhotoSyncManager.kt` - Split logic for group vs 1:1 chats

---

## Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                     INCOMING MESSAGE                                 │
└───────────────────────────┬─────────────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│  IncomingMessageHandler.handleIncomingMessage()                      │
│    1. Insert message with chatGuid                                  │
│    2. resolveUnifiedChatId():                                       │
│       - Skip groups (isGroup=true → unifiedChatId=NULL)            │
│       - Return existing link, OR                                    │
│       - Normalize address → getOrCreate unified chat → link chat   │
│    3. Update unified chat's latest message cache                    │
│    4. Increment unread count                                        │
└───────────────────────────┬─────────────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     DATABASE STATE                                   │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────┐  │
│  │ unified_chats   │  │ chats           │  │ messages            │  │
│  │ (id, norm_addr, │◀─│ (guid,          │  │ (guid, chatGuid,    │  │
│  │  sourceId,      │  │  unifiedChatId) │  │  unifiedChatId)     │  │
│  │  latestMsg*)    │  └─────────────────┘  └─────────────────────┘  │
│  └─────────────────┘                                                │
└───────────────────────────┬─────────────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ConversationLoadingDelegate.loadConversations()                     │
│    1. Query unified_chats ORDER BY latestMessageDate                │
│    2. Query group chats + non-linked chats                          │
│    3. Batch fetch: chatGuids, chats, participants                   │
│    4. Convert via UnifiedGroupMappingDelegate                       │
└───────────────────────────┬─────────────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│  UnifiedGroupMappingDelegate.unifiedChatToUiModel()                  │
│    1. Use cached latestMessageText from unified_chats               │
│    2. Merge participants from ALL linked chats  ← ISSUE            │
│    3. Build display name with fallback chain                        │
│    4. Return ConversationUiModel                                    │
└───────────────────────────┬─────────────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     UI: Conversation List                            │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Key SQL Queries

**Load active conversations:**
```sql
SELECT * FROM unified_chats
WHERE is_archived=0 AND date_deleted IS NULL
ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
LIMIT :limit OFFSET :offset
```

**Get chats linked to unified conversation:**
```sql
SELECT guid FROM chats
WHERE unified_chat_id = :id
```

**Batch get participants:**
```sql
SELECT h.* FROM handles h
INNER JOIN chat_handle_crossref chc ON h.id = chc.handle_id
WHERE chc.chat_guid IN (:chatGuids)
```

**Get unified chat by normalized address:**
```sql
SELECT * FROM unified_chats
WHERE normalized_address = :address
LIMIT 1
```

---

## Debugging Commands

Query unified chats and their linked protocol channels:
```sql
SELECT uc.id, uc.normalized_address, uc.source_id, uc.latest_message_text,
       c.guid as chat_guid, c.is_group
FROM unified_chats uc
LEFT JOIN chats c ON c.unified_chat_id = uc.id
ORDER BY uc.latest_message_date DESC
LIMIT 50;
```

Find orphaned chats (not linked to unified):
```sql
SELECT * FROM chats
WHERE unified_chat_id IS NULL AND is_group = 0;
```

Check for duplicate normalized addresses:
```sql
SELECT normalized_address, COUNT(*) as cnt
FROM unified_chats
GROUP BY normalized_address
HAVING cnt > 1;
```

Find messages without unified_chat_id:
```sql
SELECT guid, chat_guid, text, date_created
FROM messages
WHERE unified_chat_id IS NULL AND chat_guid NOT LIKE '%+%'
ORDER BY date_created DESC
LIMIT 20;
```
