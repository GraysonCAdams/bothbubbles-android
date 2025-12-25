# Unified Chat Logic - Bug Investigation

## Confirmed Issue: Group Chats Missing on Initial Load

### Symptoms
- iMessage group chats (e.g., "Squad Goals", "Siblings", "Family") do not appear in the conversation list for 5-10 seconds after app launch
- They appear after socket connects and incremental sync completes

### Root Cause (CONFIRMED)

**The `unified_chats` table has `is_group = 0` (false) for group chats, when it should be `is_group = 1` (true).**

Evidence from database query:
```sql
SELECT id, display_name, source_id, is_group FROM unified_chats
WHERE id IN ('115775591952285696', '115775591953858560');

-- Results:
-- 115775591952285696||iMessage;+;chat510611336324871340|0   <- Siblings, is_group=0 WRONG
-- 115775591953858560||iMessage;+;chat653447225601703668|0   <- Squad Goals, is_group=0 WRONG
```

### How This Causes the Bug

1. **Unified chat creation sets wrong is_group value** - When unified chats are created for group conversations, `isGroup` is set to `false`

2. **UI model inherits wrong isGroup** - `UnifiedGroupMappingDelegate.unifiedChatToUiModel()` creates a `ConversationUiModel` with `isGroup = unifiedChat.isGroup`, so the UI model has `isGroup = false`

3. **Partition puts group chats in wrong bucket** - In `ConversationLoadingDelegate.buildConversationList()`:
   ```kotlin
   val (groupConversations, individualChats) = sortedConversations.partition {
       it.isGroup || it.contactKey.isBlank()
   }
   ```
   Since `isGroup = false`, group chats go into `individualChats` instead of `groupConversations`

4. **Deduplication removes group chats** - Individual chats are deduplicated by `contactKey`:
   ```kotlin
   val deduplicatedIndividualChats = individualChats
       .groupBy { it.contactKey }
       .map { ... pick one per contactKey ... }
   ```

   Evidence from logs:
   ```
   MISPARTITIONED GROUPS: 3 chats with ';chat' in guid are in individualChats:
   [Squad Goals|isGroup=false|key=+14042750157,
    Siblings|isGroup=false|key=+17062022195,
    Family|isGroup=false|key=+17062022195]
   ```

   Notice that Siblings and Family have the SAME contactKey (+17062022195). One will be removed during deduplication.

### Why Groups Appear After Sync

After incremental sync completes and DataChanged triggers, a refresh runs. At this point, something (possibly the sync or related processing) must be updating the data in a way that the groups appear. Further investigation needed to understand exactly what changes.

### Files Involved

- `data/local/db/dao/UnifiedChatDao.kt` - Database queries
- `data/local/db/entity/UnifiedChatEntity.kt` - Entity with `isGroup` field
- `services/messaging/IncomingMessageHandler.kt` - Creates unified chats for incoming messages
- `ui/conversations/delegates/UnifiedGroupMappingDelegate.kt` - Converts unified chats to UI models
- `ui/conversations/delegates/ConversationLoadingDelegate.kt` - Builds conversation list, does partitioning

## Root Cause Locations (CONFIRMED)

### Bug Location 1: `services/messaging/IncomingMessageHandler.kt:352-358`

The bug is in `resolveUnifiedChatId()`:

```kotlin
// Line 341 - isGroup is correctly determined
val isGroup = chat?.isGroup == true

// ... normalizedAddress logic uses isGroup correctly ...

// Lines 352-358 - BUT isGroup is NOT passed to the entity!
val unifiedChat = unifiedChatDao.getOrCreate(
    UnifiedChatEntity(
        id = UnifiedChatIdGenerator.generate(),
        normalizedAddress = normalizedAddress,
        sourceId = chatGuid
        // isGroup = isGroup  <-- MISSING!
    )
)
```

### Bug Location 2: `services/contacts/sync/GroupContactSyncManager.kt:237-242`

This is specifically for GROUP chat contact sync, but also doesn't pass `isGroup`:

```kotlin
val newUnifiedChat = unifiedChatDao.getOrCreate(
    UnifiedChatEntity(
        id = UnifiedChatIdGenerator.generate(),
        normalizedAddress = chat.guid,
        sourceId = chat.guid
        // isGroup = true  <-- MISSING! This file is for groups!
    )
)
```

### Files That Correctly Pass isGroup

- `data/repository/UnifiedChatRepository.kt:204-210` - Correctly passes `isGroup = isGroup`
- `data/repository/ChatSyncOperations.kt:177` - Only creates for 1:1 chats (returns null for groups), so default `isGroup = false` is correct

The `UnifiedChatEntity` has `isGroup: Boolean = false` (line 254 in entity definition), so when not provided, it defaults to `false` even for group chats.

## Additional Issue: contactKey for Groups

### What is contactKey?

`contactKey` is a normalized phone number used for:
1. **Deduplication of 1:1 chats** - When the same contact appears as separate iMessage/SMS chats due to broken unified chat linking
2. **Partition logic** - The code checks `it.isGroup || it.contactKey.isBlank()` to determine if a conversation goes into `groupConversations`

### What Should Groups Have for contactKey?

**Groups should have contactKey = "" (empty string).**

Evidence from `ConversationMappers.kt:184`:
```kotlin
val contactKey = if (!isGroup && address.isNotBlank()) {
    PhoneNumberFormatter.getContactKey(address)
} else {
    ""  // Empty for groups!
}
```

This makes groups:
1. Go into `groupConversations` partition (because `contactKey.isBlank()` is true)
2. Avoid deduplication (each group is unique)

### The Bug in UnifiedGroupMappingDelegate

**File**: `UnifiedGroupMappingDelegate.kt:209`

```kotlin
// Line 110 - address is set from a participant's phone number
val address = primaryParticipant?.address ?: primaryChat.chatIdentifier ?: unifiedChat.normalizedAddress

// Line 209 - contactKey is ALWAYS computed from address, no isGroup check!
contactKey = PhoneNumberFormatter.getContactKey(address),
```

This should be:
```kotlin
contactKey = if (!unifiedChat.isGroup) {
    PhoneNumberFormatter.getContactKey(address)
} else {
    ""  // Groups don't use contactKey
}
```

### Impact of Wrong contactKey

With a phone number as contactKey instead of empty:
1. Groups go into `individualChats` partition (because `it.isGroup` is false AND `it.contactKey.isBlank()` is false)
2. Groups get deduplicated by contactKey - if multiple groups share a participant, one gets removed
3. Example: Siblings and Family both had contactKey `+17062022195`, so one was being removed

## Fixes Required

### Fix 1: IncomingMessageHandler - Pass isGroup when creating UnifiedChatEntity

**File**: `services/messaging/IncomingMessageHandler.kt:352-358`

```kotlin
val unifiedChat = unifiedChatDao.getOrCreate(
    UnifiedChatEntity(
        id = UnifiedChatIdGenerator.generate(),
        normalizedAddress = normalizedAddress,
        sourceId = chatGuid,
        isGroup = isGroup  // ADD THIS
    )
)
```

### Fix 2: GroupContactSyncManager - Pass isGroup = true

**File**: `services/contacts/sync/GroupContactSyncManager.kt:237-242`

```kotlin
val newUnifiedChat = unifiedChatDao.getOrCreate(
    UnifiedChatEntity(
        id = UnifiedChatIdGenerator.generate(),
        normalizedAddress = chat.guid,
        sourceId = chat.guid,
        isGroup = true  // ADD THIS - this file is specifically for groups
    )
)
```

### Fix 3: UnifiedGroupMappingDelegate - Use local isGroup and fix contactKey

**File**: `ui/conversations/delegates/UnifiedGroupMappingDelegate.kt`

The code already correctly determines `isGroup` at line 74:
```kotlin
val isGroup = unifiedChat.sourceId.contains(";chat")  // CORRECT
```

But then at line 188, it uses the WRONG value from the database:
```kotlin
isGroup = unifiedChat.isGroup,  // WRONG - uses database value which is false for groups
```

And at line 209, contactKey doesn't check isGroup:
```kotlin
contactKey = PhoneNumberFormatter.getContactKey(address),  // WRONG - always computes contactKey
```

**Fixes needed:**
1. Line 188: Change `isGroup = unifiedChat.isGroup,` to `isGroup = isGroup,` (use local variable)
2. Line 209: Change to `contactKey = if (!isGroup) PhoneNumberFormatter.getContactKey(address) else "",`

### Fix 4: Migration for existing incorrect records

Need to update existing unified_chats records where:
- `source_id` contains `;chat` (indicating a group)
- `is_group = 0`

SQL:
```sql
UPDATE unified_chats SET is_group = 1 WHERE source_id LIKE '%;chat%' AND is_group = 0;
```

## Other Places Checked (OK)

- `SmsContentObserver.kt:424` - Explicitly skips creating unified chats for groups (`if (!isGroup ...)`). Only creates for 1:1 SMS, which correctly has `isGroup = false` by default.
- `ChatSyncOperations.kt:177` - Returns null for groups at line 160-161. Only creates unified chats for 1:1.
- `UnifiedChatRepository.kt:204-210` - Correctly passes `isGroup = isGroup`.

## Database State (Confirmed)

All group unified chats have incorrect data:
```
| id                 | display_name | source_id                          | is_group |
|--------------------|--------------|------------------------------------| ---------|
| 115775591953858560 | NULL         | iMessage;+;chat653447225601703668  | 0        |
| 115775591952285696 | NULL         | iMessage;+;chat510611336324871340  | 0        |
| 115775591955496960 | NULL         | iMessage;+;chat963253037252943072  | 0        |
```

The `display_name = NULL` is less critical because `UnifiedGroupMappingDelegate.kt:114` falls back to `primaryChat.displayName`. But `is_group = 0` breaks the partition/deduplication logic.

## Order of Fixes

1. **Fix 3 first** - UnifiedGroupMappingDelegate (UI layer) - This gives immediate fix by using correct isGroup from sourceId and empty contactKey for groups
2. **Fix 1 and 2** - IncomingMessageHandler and GroupContactSyncManager - Prevents new incorrect records
3. **Fix 4** - Migration - Fixes existing records in database

---

## Confirmed Issue: 1:1 SMS Messages Showing as Group Chat

### Symptoms
- Messages sent to a single contact (e.g., Drew Andersen) appear in the UI as a group chat with multiple participants (Drew + Harrison)
- Google Messages correctly shows these as 1:1 conversations
- Example: Message "Nick was there for it though" to Drew appears in a group with Drew+Harrison

### Root Cause (CONFIRMED)

**The `SmsImporter` incorrectly assigns thread-level chat_guid to ALL messages in a thread, even when individual messages have different recipients.**

Evidence from database:
```sql
-- Thread 34 contains messages with TWO different chat_guids:
SELECT chat_guid, COUNT(*) FROM messages WHERE sms_thread_id = 34 GROUP BY chat_guid;
-- mms;-;+14042750157,+14046265002  | 176  <- WRONG (group)
-- sms;-;+14042750157               | 1    <- CORRECT (1:1)
```

The single correct message (sms-2458) was processed by `SmsContentObserver.processSmsChanges()` which determines chat_guid per-message. The 176 incorrect messages were bulk-imported by `SmsImporter.importThread()`.

### How This Causes the Bug

1. **Android threads can contain mixed recipients** - A single SMS thread can have messages to different recipients over time (1:1 messages and group messages in the same thread)

2. **SmsImporter determines thread-level addresses** - In `importThread()` at line 72-92:
   ```kotlin
   val rawAddresses = thread.recipientAddresses  // Thread-level!
   // ...
   val isGroup = addresses.size > 1
   val chatGuid = if (isGroup) {
       "mms;-;${addresses.sorted().joinToString(",")}"  // Group format
   } else {
       "sms;-;${addresses.first()}"  // 1:1 format
   }
   ```

3. **Thread addresses come from ANY MMS in thread** - `getAddressesForThread()` in `SmsContentProviderHelpers.kt:30-90` looks at MMS addresses. If ANY MMS in the thread was a group message, those addresses are returned for the entire thread.

4. **All imported messages get wrong chat_guid** - In `importLatestMessageForThread()` at line 173-178:
   ```kotlin
   latestSms.toMessageEntity(chatGuid)  // Uses THREAD's chatGuid, not per-message!
   ```

### Why Only Some Messages Are Wrong

- `SmsContentObserver.processSmsChanges()` processes NEW messages in real-time and correctly determines chat_guid per-message from the SMS ADDRESS field
- `SmsImporter.importThread()` bulk-imports historical messages using thread-level addresses, incorrectly applying the same chat_guid to all

### Files Involved

- `data/repository/SmsImporter.kt:71-155` - `importThread()` determines thread-level chat_guid
- `data/repository/SmsImporter.kt:161-187` - `importLatestMessageForThread()` uses wrong chat_guid
- `services/sms/SmsContentProviderHelpers.kt:30-90` - `getAddressesForThread()` returns addresses from any MMS in thread
- `services/sms/SmsContentObserver.kt:147-238` - `processSmsChanges()` correctly handles per-message chat_guid

### Fix Required

**Option A: Per-message chat_guid determination in SmsImporter**

Modify `importLatestMessageForThread()` to determine chat_guid for each message individually:
- For SMS: Use the message's ADDRESS field directly
- For MMS: Query the specific MMS's addr table to get recipients

**Option B: Separate threads by actual recipients**

When importing, detect that a thread has mixed recipients and split into multiple chats accordingly.

**Option C: Don't use SmsImporter for message import**

Only use `SmsContentObserver` for message import, which already handles per-message chat_guid correctly.

### Migration for Existing Data

Need to:
1. Identify messages where `sms_thread_id` has multiple `chat_guid` values
2. For each SMS message (`guid LIKE 'sms-%'`), verify the chat_guid matches the message's actual recipient
3. For MMS messages, re-query the MMS addr table and update chat_guid if wrong

Example migration query to identify affected threads:
```sql
SELECT sms_thread_id, GROUP_CONCAT(DISTINCT chat_guid) as guids, COUNT(DISTINCT chat_guid) as unique_guids
FROM messages
WHERE sms_thread_id IS NOT NULL
GROUP BY sms_thread_id
HAVING unique_guids > 1;
```

---

## Confirmed Issue: Group Chats with Hex-Like Display Names

### Symptoms
- Some group chats display internal identifiers like "8a5f87", "930d51", "3833831" instead of proper names
- These are RCS group chats with many participants (e.g., 19 people)
- The names look like truncated hex strings or numeric IDs

### Root Cause (CONFIRMED)

**The `isValidDisplayName()` regex only filters strings starting with letters, not digits.**

The filter at `ChatSyncOperations.kt:248`:
```kotlin
if (Regex("^[a-z][0-9a-z]{4,7}$").matches(this)) return false
```

This regex requires `[a-z]` at the start - a **lowercase letter**. But the problematic names start with **digits**:
- `8a5f87` - starts with "8" ❌ Not caught by filter
- `930d51` - starts with "9" ❌ Not caught by filter
- `3833831` - starts with "3" ❌ Not caught by filter

Evidence from database:
```sql
SELECT guid, display_name FROM chats
WHERE display_name GLOB '[0-9a-f]*' AND length(display_name) <= 10;
-- RCS;+;chat804377021112530880|8a5f87
-- RCS;+;chat853851830602744867|3833831
-- RCS;+;chat369221079655718404|930d51
```

### Files Involved

- `data/repository/ChatSyncOperations.kt:248` - Incomplete regex in `isValidDisplayName()`
- `data/repository/ChatSyncOperations.kt:205-220` - `ChatDto.toEntity()` uses validation

### Fix Required

Update the regex to also catch strings starting with digits:

**Current (buggy):**
```kotlin
if (Regex("^[a-z][0-9a-z]{4,7}$").matches(this)) return false
```

**Fixed:**
```kotlin
// Filter out short alphanumeric strings that look like internal IDs (e.g., "c46271", "8a5f87")
if (Regex("^[a-z0-9]{5,8}$", RegexOption.IGNORE_CASE).matches(this)) return false
```

This catches:
- Strings of 5-8 characters that are purely alphanumeric
- Case-insensitive (catches both "8A5F87" and "8a5f87")
- Starts with either letter OR digit

### Migration for Existing Data

```sql
-- Set display_name to NULL for chats with hex-like names
UPDATE chats
SET display_name = NULL
WHERE display_name GLOB '[0-9a-zA-Z][0-9a-zA-Z][0-9a-zA-Z][0-9a-zA-Z][0-9a-zA-Z]'
AND display_name GLOB '[0-9a-zA-Z][0-9a-zA-Z][0-9a-zA-Z][0-9a-zA-Z][0-9a-zA-Z][0-9a-zA-Z][0-9a-zA-Z][0-9a-zA-Z]'
AND length(display_name) <= 8;
```

Note: After clearing invalid display names, group chats will fall back to showing participant names or a generated name.

---

## Note: Building Avatar Logic

### Current Behavior (Needs Verification)
The building avatar (business icon) should only appear for **business contacts** - contacts that have ONLY a company name set, with NO first or last name.

### Correct Logic
```kotlin
// Show building avatar ONLY if:
// - Company name is set AND
// - First name is blank/null AND
// - Last name is blank/null
val isBusiness = !companyName.isNullOrBlank() &&
                 givenName.isNullOrBlank() &&
                 familyName.isNullOrBlank()
```

### Incorrect Logic (Bug)
```kotlin
// WRONG - shows building for anyone with a company, even if they have a name
val isBusiness = !companyName.isNullOrBlank()
```

### Files to Check
- `ui/components/common/Avatar.kt` - Where `isBusiness` parameter is used
- `ui/conversations/ConversationMappers.kt` - Where `isBusiness` is determined for conversation tiles
- `services/contacts/AndroidContactsService.kt` - Where contact data is fetched

### Expected Behavior
| First Name | Last Name | Company | Building Avatar? |
|------------|-----------|---------|------------------|
| "John"     | "Doe"     | "Acme"  | ❌ No - has name     |
| "John"     | null      | "Acme"  | ❌ No - has first name |
| null       | "Doe"     | "Acme"  | ❌ No - has last name |
| null       | null      | "Acme"  | ✅ Yes - only company |
| null       | null      | null    | ❌ No - no company   |

---

## Confirmed Issue: SMS Import Sets Wrong `latest_message_date`

### Symptoms
- Many conversations show Nov 20-25, 2025 as their "last message" date
- When opening the thread, actual messages are from 2021, 2022, or earlier
- Conversations are sorted incorrectly because of wrong dates

### Root Cause (CONFIRMED)

**The SmsImporter uses `thread.lastMessageDate` from Android's content provider, but this value is incorrect or is the import time instead of actual message dates.**

Evidence from database:
```sql
-- Most common latest_message_date values in chats table:
-- Nov 20, 2025 13:15:57 - 623 chats  <- BULK IMPORT TIME
-- Nov 25, 2025 00:27:17 - 230 chats  <- BULK IMPORT TIME
-- Nov 24, 2025 03:11:54 - 120 chats  <- BULK IMPORT TIME
-- Nov 22, 2025 04:17:01 - 73 chats   <- BULK IMPORT TIME
```

Specific examples where stored date is YEARS newer than actual messages:
```
| Chat               | Stored Latest  | Actual Latest | Diff (days) |
|--------------------|----------------|---------------|-------------|
| +14707635953       | Nov 20, 2025   | Jul 07, 2021  | 1597        |
| +11410100017       | Nov 20, 2025   | Jul 13, 2021  | 1591        |
| +17735374496       | Nov 25, 2025   | Oct 02, 2024  | 419         |
```

### How This Happens

1. **SmsImporter.importThread()** at line 102:
   ```kotlin
   val chat = ChatEntity(
       // ...
       latestMessageDate = thread.lastMessageDate  // Uses Android's value
   )
   ```

2. **SmsContentProvider.getThreads()** at line 68:
   ```kotlin
   lastMessageDate = it.getLong(it.getColumnIndexOrThrow(Telephony.Threads.DATE))
   ```

3. **Android's Telephony.Threads.DATE** appears to return incorrect values for old threads, possibly returning the query time instead of actual thread date.

### Files Involved

- `data/repository/SmsImporter.kt:102` - Uses `thread.lastMessageDate` for chat creation
- `data/repository/SmsImporter.kt:146` - Uses it again for unified chat update
- `services/sms/SmsContentProvider.kt:68` - Reads `Telephony.Threads.DATE`

### Fix Required

**Option A: Calculate date from actual messages**

Instead of trusting `thread.lastMessageDate`, query the actual latest message date from the SMS/MMS tables:
```kotlin
val actualLatestDate = max(
    smsMessages.maxOfOrNull { it.date } ?: 0L,
    mmsMessages.maxOfOrNull { it.date } ?: 0L
)
```

**Option B: Validate the thread date**

Only use `thread.lastMessageDate` if it's reasonable (not in the future, not more than X years ago from messages).

### Migration for Existing Data

Fix the dates by recalculating from actual messages:
```sql
-- Update chats.latest_message_date from actual messages
UPDATE chats
SET latest_message_date = (
    SELECT MAX(date_created)
    FROM messages
    WHERE messages.chat_guid = chats.guid
)
WHERE EXISTS (
    SELECT 1 FROM messages WHERE messages.chat_guid = chats.guid
);

-- Update unified_chats similarly
UPDATE unified_chats
SET latest_message_date = (
    SELECT MAX(m.date_created)
    FROM messages m
    JOIN chats c ON m.chat_guid = c.guid
    WHERE c.unified_chat_id = unified_chats.id
)
WHERE EXISTS (
    SELECT 1 FROM chats c
    JOIN messages m ON m.chat_guid = c.guid
    WHERE c.unified_chat_id = unified_chats.id
);
```

---

## Investigation: Missing Dates When Scrolling Conversations

### Initial Symptom
User reported seeing conversations with missing dates when rapidly scrolling through the conversation list.

### Root Cause (FIXED)

Empty chats were being created during SMS import even when no messages existed. This was caused by `SmsImporter.importThread()` creating chat records before checking if any messages existed.

Database analysis before fix:
```sql
-- 2,185 chats with NULL dates, all have ZERO messages
SELECT COUNT(*) FROM chats WHERE latest_message_date IS NULL;  -- 2185
SELECT COUNT(*) FROM chats c
WHERE NOT EXISTS (SELECT 1 FROM messages m WHERE m.chat_guid = c.guid);  -- 2213
```

Example: "Brosephs v2.0" had 0 messages - this chat should never have been created.

**Fix Applied**: SmsImporter now checks for messages FIRST and skips empty threads entirely.

---

## Confirmed Issue: Group Chats Incorrectly Added to Unified Chats

### Symptoms
- Group chats appearing in `unified_chats` table with their GUID as `normalized_address`
- Some of these have NULL `latest_message_date` even when they have messages

### Root Cause (CONFIRMED)

**Group chats are being incorrectly inserted into the `unified_chats` table, which is designed for merging 1:1 iMessage/SMS conversations.**

Evidence from database:
```sql
SELECT normalized_address, COUNT(*) as count
FROM unified_chats
WHERE normalized_address LIKE '%chat%'  -- Group-style GUIDs
GROUP BY normalized_address;

-- 10 unified_chats have group GUIDs as normalized_address:
-- iMessage;+;chat653447225601703668  <- Squad Goals group
-- iMessage;+;chat510611336324871340  <- Siblings group
-- RCS;+;chat804377021112530880       <- "8a5f87" RCS group
-- RCS;+;chat853851830602744867       <- "3833831" RCS group
-- etc.
```

3 of these unified_chats have NULL `latest_message_date` but actually have messages:
```sql
-- unified_chats with messages but NULL date
| id                 | normalized_address              | msg_count | newest_msg       |
|--------------------|--------------------------------|-----------|------------------|
| 115775591957200896 | SMS;+;chat494101200860558526   | 2         | Oct 19, 2025     |
| 115775591962116096 | iMessage;+;chat814332991582426454| 25      | Mar 24, 2025     |
| 115775591962705920 | iMessage;+;chat558254845735867672| 25      | Jun 26, 2025     |
```

### Files Involved

The root cause is in the same locations identified earlier:
- `services/messaging/IncomingMessageHandler.kt:352-358` - Creates unified chats without checking if it's a group
- `services/contacts/sync/GroupContactSyncManager.kt:237-242` - Explicitly for groups but still creates unified chats

### Fix Required

Group chats should NEVER be added to unified_chats. Add guards:
```kotlin
// In IncomingMessageHandler.resolveUnifiedChatId()
if (isGroup) {
    return null  // Groups don't use unified chats
}
```

### Affected Records

10 unified_chats need to be cleaned up:
```sql
-- Delete unified_chats that are actually groups
DELETE FROM unified_chats
WHERE normalized_address LIKE '%;chat%';

-- Also unlink any chats that were pointing to these
UPDATE chats SET unified_chat_id = NULL
WHERE unified_chat_id IN (
    SELECT id FROM unified_chats WHERE normalized_address LIKE '%;chat%'
);
```

---

## Summary: Conversation List Issues

| Issue | Type | Status |
|-------|------|--------|
| Group chats missing initially | Bug | Documented, fix identified |
| 1:1 SMS showing as group | Bug | Documented, fix identified |
| Hex-like group names (8a5f87) | Bug | Documented, fix identified |
| Wrong latest_message_date | Bug | **FIXED** in SmsImporter |
| Empty chats created | Bug | **FIXED** in SmsImporter |
| Groups in unified_chats | Bug | Documented, fix identified |

---

## Fix Applied: SmsImporter Empty Chats & Wrong Dates

**File**: `data/repository/SmsImporter.kt`

**Changes made**:
1. Check for messages BEFORE creating any chat/handle/unified chat records
2. Skip threads entirely if no SMS or MMS messages exist
3. Use actual message `date` instead of unreliable `thread.lastMessageDate`

**Before** (buggy):
```kotlin
// Created chat first, then checked for messages
val chat = ChatEntity(
    latestMessageDate = thread.lastMessageDate  // Unreliable!
)
chatDao.insertChat(chat)
// ... later ...
importLatestMessageForThread(...)  // Might find no messages
```

**After** (fixed):
```kotlin
// Check for messages FIRST
val smsMessages = smsContentProvider.getSmsMessages(thread.threadId, limit = 1)
val mmsMessages = smsContentProvider.getMmsMessages(thread.threadId, limit = 1)

if (latestSms == null && latestMms == null) {
    Timber.d("Skipping thread ${thread.threadId} - no messages found")
    return  // Skip empty threads entirely
}

// Use actual message date
val actualLatestDate = maxOf(latestSms?.date ?: 0L, latestMms?.date ?: 0L)

val chat = ChatEntity(
    latestMessageDate = actualLatestDate  // Real date from message!
)
```

This fixes both issues:
- Empty threads are skipped (no more chats with 0 messages)
- Dates come from actual messages (no more Nov 25 import timestamps)
