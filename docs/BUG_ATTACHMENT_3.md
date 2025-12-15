# Bug Analysis: Attachment Sending "Ghost Bubble" & Broken Thumbnails

## Problem Description

When sending an attachment (image/video):

1.  User taps send.
2.  A small, empty blue bubble appears immediately (the "ghost bubble").
3.  After ~5 seconds, the media appears (bubble expands or new bubble appears).
4.  After ~15 seconds (likely upon sync/refresh), the image thumbnail breaks.

## Root Cause Analysis

### 1. The "Ghost Bubble" (Race Condition)

**Location:** `app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendingService.kt` -> `sendIMessageWithAttachments`

**The Issue:**
The code performs two separate database operations sequentially without a transaction:

1.  Inserts `MessageEntity` (Optimistic message).
2.  Inserts `AttachmentEntity` records (Optimistic attachments).

```kotlin
// 1. Message inserted
messageDao.insertMessage(tempMessage)

// ... processing ...

// 2. Attachments inserted
attachmentDao.insertAttachment(tempAttachment)
```

**The Consequence:**
The UI (`RoomMessageDataSource`) observes the database. It is possible (and likely) for the observer to trigger _after_ step 1 but _before_ step 2.

1.  UI sees new Message. `hasAttachments=true`.
2.  UI queries for attachments for this message. Result: `Empty List` (because step 2 hasn't finished).
3.  `MessageBubble` checks `needsSegmentation`. Since there are no visible media attachments yet, it returns `false`.
4.  `MessageBubble` renders `SimpleBubbleContent` (text mode).
5.  Since `text` is null/blank, `SimpleBubbleContent` renders an empty container with padding/margins, creating the "ghost bubble".

### 2. Broken Thumbnails (MIME Type Loss)

**Location:** `app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendingService.kt` -> `sendIMessageWithAttachments`

**The Issue:**
When creating the temporary `AttachmentEntity`, the code attempts to resolve the MIME type from the URI again, ignoring the one passed in `PendingAttachmentInput`.

```kotlin
val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
```

**The Consequence:**
The `uri` passed here is often a `file://` URI (pointing to the persisted copy in `filesDir`). `ContentResolver.getType()` often returns `null` for file URIs.

1.  MIME type falls back to `application/octet-stream`.
2.  The UI (`AttachmentContent` / `BorderlessMediaContent`) checks `isImage` or `isVideo` based on MIME type.
3.  Since it's `application/octet-stream`, the UI treats it as a generic file, not an image.
4.  It fails to render the thumbnail or renders a generic file icon, appearing "broken" compared to the expected image preview.

## Plan of Attack

### Step 1: Fix Race Condition (Database Transaction)

Wrap the creation of the optimistic message and its attachments in a single Room transaction. This ensures the UI observer only fires when both the message and its attachments are present.

**Changes:**

- Inject `BothBubblesDatabase` into `MessageSendingService`.
- Wrap the insertion logic in `database.withTransaction { ... }`.

### Step 2: Fix MIME Type Logic

Respect the MIME type that was already determined during the picking/queuing phase.

**Changes:**

- In `sendIMessageWithAttachments`, use `input.mimeType` if available. Only fall back to `contentResolver` if it's missing.

```kotlin
val mimeType = input.mimeType.takeIf { it.isNotBlank() }
    ?: context.contentResolver.getType(uri)
    ?: "application/octet-stream"
```

### Step 3: UI Resilience (Optional but Recommended)

Make the UI more robust against this state, just in case.

**Changes:**

- **`MessageSegmentParser`**: Ensure it doesn't create a `TextSegment` if the text is purely whitespace/invisible characters.
- **`SimpleBubbleContent`**: If `text` is empty and there are no attachments (edge case), render nothing or a minimal placeholder instead of a bubble with padding.

## Verification Plan

1.  **Send Image:** Send an image from the gallery.
2.  **Observe Immediate State:** Verify the bubble immediately shows the image (optimistic state) without an intermediate empty bubble.
3.  **Observe Persisted State:** Wait for the send to complete (or restart app). Verify the thumbnail remains visible and doesn't turn into a generic file icon.
