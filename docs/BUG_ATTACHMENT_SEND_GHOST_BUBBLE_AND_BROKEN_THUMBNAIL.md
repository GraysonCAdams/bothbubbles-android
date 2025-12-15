# Bug Investigation: Attachment Send Shows Empty Bubble + Broken Thumbnail

## Symptom timeline (as reported)

1. Tap **Send** with an attachment
2. A **small empty outgoing (blue) bubble** appears
3. ~5 seconds later: the attachment/media appears, but the empty bubble persists
4. ~15 seconds later: the **thumbnail breaks** (until you leave the thread and re-enter)

Observation: Server shows only the attachment (no empty message), so the empty bubble is client-side.

---

## What the codebase suggests is happening

There are two _independent but compounding_ behaviors in the Android client that match the timeline.

### A) Empty outgoing bubble: UI routes attachment-only messages into the “text-only” bubble

**Key UI behavior**

- `MessageBubble` decides whether to render:
  - `SegmentedMessageBubble` (shows media/link previews), or
  - `SimpleBubbleContent` (fast path intended for text-only messages)

Relevant code:

- `app/src/main/kotlin/com/bothbubbles/ui/components/message/MessageBubble.kt`
  - routes based on `MessageSegmentParser.needsSegmentation(...)`
- `app/src/main/kotlin/com/bothbubbles/ui/components/message/MessageSegment.kt`
  - `needsSegmentation` currently returns `true` **only** when an attachment is recognized as `isImage` or `isVideo`.

```kotlin
// MessageSegment.kt
fun needsSegmentation(message: MessageUiModel, hasLinkPreview: Boolean): Boolean {
    val hasMedia = message.attachments.any { it.isImage || it.isVideo }
    return hasMedia || hasLinkPreview
}
```

**Why this yields an empty bubble**

- `SimpleBubbleContent` always draws a bubble `Surface`, but attachment rendering inside it is disabled:
  - `app/src/main/kotlin/com/bothbubbles/ui/components/message/MessageSimpleBubble.kt`

```kotlin
// MessageSimpleBubble.kt
// Attachments
// TEMPORARILY DISABLED: Skip attachment rendering to focus on text-only performance
// if (message.attachments.isNotEmpty()) { ... AttachmentContent(...) }
```

So if the message is _attachment-only_ and the attachment is not recognized as image/video yet, you get:

- bubble background + no text + no attachment content => “empty blue bubble”.

**Why the attachment may not be recognized as media initially**
On the optimistic insert path, attachment MIME is derived from `ContentResolver.getType(uri)`:

- `app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendingService.kt` (`sendIMessageWithAttachments`)

```kotlin
val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
```

But when messages are sent from the pending send queue, the worker constructs inputs using `Uri.fromFile(...)`:

- `app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendWorker.kt`

```kotlin
PendingAttachmentInput(
    uri = Uri.fromFile(file),
    mimeType = attachment.mimeType,
    name = attachment.fileName,
    size = attachment.fileSize
)
```

On many Android versions, `contentResolver.getType(file://...)` returns `null`, which forces `application/octet-stream`.
If `AttachmentUiModel.isImage/isVideo` is determined primarily from MIME/UTI, these optimistic attachments won’t count as media, so the UI stays in the `SimpleBubbleContent` path until server-provided metadata arrives.

That matches your “empty bubble first, then media appears later”.

---

### B) Thumbnail breaks later: localPath points at a file that gets deleted after send

**Pending attachment persistence + cleanup**
Attachments queued for WorkManager are copied into app storage and then deleted after send succeeds:

- `app/src/main/kotlin/com/bothbubbles/services/messaging/AttachmentPersistenceManager.kt`

  - stores in `filesDir/pending_attachments/`
  - `cleanupAttachments(paths)` deletes those files

- `app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendWorker.kt`

```kotlin
// After send success
attachmentPersistenceManager.cleanupAttachments(attachments.map { it.persistedPath })
```

**But outbound attachment sync preserves localPath**
After the server confirms the message, the app replaces temp attachments with server attachments and tries to preserve the temp `localPath` to prevent flicker:

- `app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendingService.kt`

```kotlin
// Preserves localPath from temp attachments
val preservedLocalPath = tempLocalPaths.getOrNull(index)
...
localPath = preservedLocalPath
```

If that preserved local path points to a file inside `filesDir/pending_attachments/`, it will be deleted by `cleanupAttachments` shortly after send success.

**Why it appears to break after ~15 seconds**

- The UI may initially render fine because the local file exists.
- Later (recompose, image cache eviction, paging refresh, etc.), the loader re-reads the file path and fails because the file has been deleted.

**Why leaving the thread and coming back “fixes” it**
`BorderlessImageAttachment` chooses `attachment.localPath ?: attachment.webUrl`:

- `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentContent.kt`

If the attachment row is refreshed/rebuilt such that `localPath` becomes `null` (or the UI layer prefers `webUrl` after reload), the image loads from `webUrl` and renders again.
In contrast, while staying in the thread, the UI can keep holding onto the stale `localPath` in the current model instance.

---

## Notes on the “socket wins” race condition

There is also a real race-handling path in `MessageDao.replaceGuid(...)`:

- `app/src/main/kotlin/com/bothbubbles/data/local/db/dao/MessageDao.kt`

```kotlin
// If server GUID already exists, delete temp
if (existingMessage != null) deleteMessage(tempGuid)
```

This can delete the temp message (and via FK CASCADE, its temp attachments) when the socket inserts the server message first.
That race can cause its own forms of “missing localPath” depending on timing.

However, your “break after ~15s” strongly aligns with the pending-file cleanup + preserved localPath problem even when there is no socket/HTTP race.

---

## Most likely explanation of your exact timeline

1. **Send** → optimistic temp message inserted.
2. UI decides `needsSegmentation == false` because temp attachment is not recognized as image/video (often MIME becomes `application/octet-stream` for `file://...`).
3. `SimpleBubbleContent` renders bubble, but attachments are disabled → **empty outgoing bubble**.
4. Server response arrives with attachment metadata (and/or attachment rows update) → UI now treats it as media → media renders (possibly as separate segmented content while the earlier bubble still exists).
5. WorkManager cleanup deletes `filesDir/pending_attachments/...` → any preserved `localPath` pointing there becomes invalid.
6. Coil/image loader re-reads that path later → **thumbnail breaks** until the UI reloads the model and falls back to `webUrl`.

---

## Concrete fix options (touch points)

### Option 1 (low risk): Use the known MIME type for optimistic attachments

In `MessageSendingService.sendIMessageWithAttachments(...)`, don’t re-derive MIME from `contentResolver.getType(uri)` when `PendingAttachmentInput.mimeType` is already provided.

- Touch: `MessageSendingService.kt` temp attachment creation.
- Expected result: `needsSegmentation` becomes true immediately for images/videos → no empty bubble.

### Option 2 (low risk): Don’t preserve localPath if it points into `pending_attachments`

In `syncOutboundAttachments(...)`, if `preservedLocalPath` is inside `filesDir/pending_attachments/`, set `localPath = null` so UI uses `webUrl` after send.

- Touch: `MessageSendingService.kt` (`syncOutboundAttachments`).
- Expected result: no broken thumbnail after cleanup.

### Option 3 (best UX): Promote pending files to a permanent attachment directory instead of deleting

Instead of deleting `pending_attachments` files after send success, move/copy them into a stable location (e.g. `filesDir/attachments/`) and update `AttachmentEntity.localPath`.

- Touch: `MessageSendWorker.kt` success path + a small helper in `AttachmentPersistenceManager`.
- Expected result: instant local preview stays valid and doesn’t break, even offline.

### Option 4 (UI-side safety net): Prefer `webUrl` when localPath file is missing

When building the image request model, if `localPath` is a file path that doesn’t exist, fall back to `webUrl`.

- Touch: `AttachmentContent.kt` (`BorderlessImageAttachment` and video thumbnail paths).
- Expected result: thumbnails self-heal without requiring navigation.

---

## Quick repro instrumentation ideas

- Log the attachment’s `mimeType`, `localPath`, and whether it exists on disk:
  - right after temp insert
  - right before/after `cleanupAttachments`
  - inside `BorderlessImageAttachment` when resolving `imageUrl`

If you want, I can implement Option 1 + Option 2 as a minimal patch (removes empty bubble and broken thumbnail without changing storage strategy), or Option 1 + Option 3 for the best long-term behavior.
