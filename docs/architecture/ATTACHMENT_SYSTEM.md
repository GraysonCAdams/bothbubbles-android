# Attachment System Architecture

This document provides a comprehensive overview of the attachment handling system in BothBubbles, covering composition, sending, receiving, and rendering for both iMessage and SMS/MMS.

---

## Table of Contents

1. [Data Models](#1-data-models)
2. [Attachment Composition Flow](#2-attachment-composition-flow)
3. [Sending Flow (iMessage)](#3-sending-flow-imessage)
4. [Sending Flow (SMS/MMS)](#4-sending-flow-smsmms)
5. [Receiving Flow](#5-receiving-flow)
6. [Download Queue System](#6-download-queue-system)
7. [UI Rendering Logic](#7-ui-rendering-logic)
8. [Known Issues & Potential Bugs](#8-known-issues--potential-bugs)
9. [Key File Locations](#9-key-file-locations)

---

## 1. Data Models

### 1.1 PendingAttachmentInput (UI → Repository)

**File:** `app/src/main/kotlin/com/bothbubbles/data/model/PendingAttachmentInput.kt`

Lightweight input model for attachments selected by user:

```kotlin
data class PendingAttachmentInput(
    val uri: Uri,              // Original content URI from picker
    val caption: String? = null,
    val mimeType: String? = null,
    val name: String? = null,
    val size: Long? = null
)
```

### 1.2 AttachmentItem (Composer UI State)

**File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/composer/ComposerState.kt:152-199`

Rich UI model with upload tracking:

```kotlin
data class AttachmentItem(
    val id: String,
    val uri: Uri,
    val mimeType: String?,
    val displayName: String?,
    val sizeBytes: Long?,
    val isUploading: Boolean = false,
    val uploadProgress: Float? = null,
    val error: String? = null,
    val quality: AttachmentQuality = AttachmentQuality.DEFAULT,
    val caption: String? = null
)
```

### 1.3 PendingAttachmentEntity (Database - Outgoing Queue)

**File:** `core/model/src/main/kotlin/com/bothbubbles/core/model/entity/PendingAttachmentEntity.kt`

Persisted in `pending_attachments` table for offline-first sending:

| Field | Purpose |
|-------|---------|
| `localId` | Unique ID (e.g., `temp-{uuid}-att-0`) |
| `pendingMessageId` | FK to parent PendingMessageEntity |
| `originalUri` | Original content URI (for reference) |
| `persistedPath` | **Path to file copied to app-internal storage** |
| `fileName` | Original filename |
| `mimeType` | MIME type |
| `fileSize` | Size in bytes |
| `uploadProgress` | 0.0-1.0 progress |
| `orderIndex` | Attachment order |
| `quality` | Compression quality setting |
| `caption` | Optional caption |

### 1.4 AttachmentEntity (Database - Synced Attachments)

**File:** `core/model/src/main/kotlin/com/bothbubbles/core/model/entity/AttachmentEntity.kt`

Main attachment table for both inbound and outbound:

| Field | Type | Purpose |
|-------|------|---------|
| `guid` | String | Unique identifier |
| `messageGuid` | String | Parent message FK |
| `mimeType` | String? | MIME type |
| `transferName` | String? | Original filename |
| `totalBytes` | Long? | File size |
| `width/height` | Int? | Dimensions |
| `webUrl` | String? | Server download URL |
| `localPath` | String? | **Local file path (key for rendering!)** |
| `thumbnailPath` | String? | Cached thumbnail path |
| `blurhash` | String? | Placeholder hash |
| `transferState` | String | PENDING, UPLOADING, UPLOADED, DOWNLOADING, DOWNLOADED, FAILED |
| `transferProgress` | Float | 0.0-1.0 |
| `isOutgoing` | Boolean | True if sent by user |
| `isSticker` | Boolean | Sticker flag (needs HEIC conversion) |
| `errorType` | String? | Error classification |
| `errorMessage` | String? | User-friendly error |
| `retryCount` | Int | Retry attempts |

### 1.5 AttachmentUiModel (UI Rendering)

**File:** `app/src/main/kotlin/com/bothbubbles/ui/components/message/MessageModels.kt:194-285`

Transformed from AttachmentEntity for UI consumption:

```kotlin
data class AttachmentUiModel(
    val guid: String,
    val mimeType: String?,
    val localPath: String?,      // Local file path or URI
    val webUrl: String?,         // Server URL fallback
    val transferState: String,
    val transferProgress: Float,
    // ... computed: needsDownload, isUploading, isDownloading, hasError, etc.
)
```

### 1.6 Transfer State Lifecycle

```
OUTBOUND (Sending):
  UPLOADING → UPLOADED (success)
           → FAILED (error)

INBOUND (Receiving):
  PENDING → DOWNLOADING → DOWNLOADED (success)
                       → FAILED (error)
```

---

## 2. Attachment Composition Flow

### 2.1 User Selects Attachment

**File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/composer/ChatComposer.kt:149-150`

```kotlin
val pickMedia = rememberLauncherForActivityResult(
    ActivityResultContracts.PickVisualMedia()
) { selectedUris ->
    onMediaSelected(selectedUris.map { it })  // → ComposerEvent.AddAttachments
}
```

### 2.2 ChatComposerDelegate Processing

**File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatComposerDelegate.kt:499-591`

1. **Validates** each attachment (size limits, counts)
2. **Extracts metadata** via AttachmentRepository
3. **Creates PendingAttachmentInput** objects
4. **Updates `_pendingAttachments` StateFlow**

### 2.3 Thumbnail Display

**File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/composer/components/AttachmentThumbnailRow.kt`

- Uses Coil `AsyncImage` with the original content URI
- Supports drag-and-drop reordering
- Shows quality selector for images

---

## 3. Sending Flow (iMessage)

### 3.1 Queue Message for Sending

**File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatSendDelegate.kt:115-207`

```kotlin
suspend fun queueMessageForSending(
    text: String,
    attachments: List<PendingAttachmentInput>,
    currentSendMode: ChatSendMode,
    ...
): Result<QueuedMessageInfo>
```

1. Generates temp GUID: `"temp-${UUID.randomUUID()}"`
2. Determines delivery mode (IMESSAGE, LOCAL_SMS, etc.)
3. Calls `pendingMessageSource.queueMessage()` on IO dispatcher

### 3.2 Persist Attachments to Internal Storage

**File:** `app/src/main/kotlin/com/bothbubbles/services/messaging/AttachmentPersistenceManager.kt:47-84`

**Critical step:** Copies attachment from content URI to app-internal storage because:
- Content URIs expire after process death
- Original files might be deleted
- WorkManager needs guaranteed access

```kotlin
suspend fun persistAttachment(uri: Uri, localId: String): Result<PersistenceResult> {
    val destFile = File(pendingDir, "${localId}.$extension")
    contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(destFile).use { output ->
            input.copyTo(output)
        }
    }
    return PersistenceResult(
        persistedPath = destFile.absolutePath,  // e.g., /data/data/.../pending_attachments/temp-uuid-att-0.jpg
        ...
    )
}
```

**Storage location:** `context.filesDir/pending_attachments/`

### 3.3 Database Transaction (Optimistic UI)

**File:** `app/src/main/kotlin/com/bothbubbles/data/repository/PendingMessageRepository.kt:138-210`

All-or-nothing transaction:

1. **Insert PendingMessageEntity** (for durability)
2. **Insert PendingAttachmentEntity** (for each attachment)
3. **Insert optimistic MessageEntity** (local echo for instant display)
4. **Insert AttachmentEntity** with:
   ```kotlin
   AttachmentEntity(
       guid = data.localId,  // "temp-{uuid}-att-0"
       messageGuid = clientGuid,
       localPath = Uri.fromFile(File(data.persistedPath)).toString(),  // file:///data/...
       transferState = TransferState.UPLOADING.name,
       transferProgress = 0f,
       isOutgoing = true,
       ...
   )
   ```

### 3.4 WorkManager Execution

**File:** `app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendWorker.kt:117-225`

1. Loads PendingMessageEntity and PendingAttachmentEntity records
2. Verifies persisted files still exist
3. Calls `messageSendingService.sendUnified()`

### 3.5 IMessageSenderStrategy Upload

**File:** `app/src/main/kotlin/com/bothbubbles/services/messaging/sender/IMessageSenderStrategy.kt:173-330`

For messages with attachments:

1. **Creates temp message + attachment entities** in transaction
2. **For each attachment:**
   - Compresses if needed (images/videos)
   - Uploads via `api.sendAttachment()` (multipart)
   - Tracks progress via `ProgressRequestBody`
   - Marks as `UPLOADED` on success

### 3.6 GUID Replacement After Send

**File:** `app/src/main/kotlin/com/bothbubbles/services/messaging/sender/IMessageSenderStrategy.kt:294-314`

After successful send:

1. **Captures local paths** BEFORE GUID replacement
2. **Replaces temp GUID** with server GUID: `messageDao.replaceGuid(tempGuid, serverGuid)`
3. **Calls `syncOutboundAttachments()`** to link local files to server message

### 3.7 Sync Outbound Attachments

**File:** `app/src/main/kotlin/com/bothbubbles/services/messaging/sender/IMessageSenderStrategy.kt:300-333`

- Captures temp attachment rows and builds `preservedLocalPaths`, but explicitly drops anything that still lives under `/pending_attachments/` ([IMessageSenderStrategy.kt](app/src/main/kotlin/com/bothbubbles/services/messaging/sender/IMessageSenderStrategy.kt#L306-L324)).
- Inserts fresh `AttachmentEntity` rows with the server GUID and `localPath = preservedLocalPaths[index]`, which resolves to `null` for every attachment that came from the pending directory.
- `MessageSendWorker` then calls `attachmentPersistenceManager.cleanupAttachments()` as soon as the send succeeds, so the high-res pending files are deleted immediately afterward.

**Impact:** Outgoing previews lose their local backing file right after upload, so the UI drops back to the download placeholder until the server-hosted copy is pulled down again.

---

## 4. Sending Flow (SMS/MMS)

**File:** `app/src/main/kotlin/com/bothbubbles/services/messaging/sender/SmsSenderStrategy.kt`

### 4.1 Routing Logic

```kotlin
val useMms = chat.isGroup || options.attachments.isNotEmpty() || options.subject != null
```

### 4.2 MMS Send

```kotlin
mmsSendService.sendMms(
    recipients = addresses,
    text = text,
    attachmentUris = options.attachments.map { it.uri },  // Direct URI pass-through
    chatGuid = chatGuid,
    subject = options.subject,
    subscriptionId = subscriptionId
)
```

**Key difference:** SMS/MMS uses original URIs directly (no server upload).

---

## 5. Receiving Flow

### 5.1 Incoming Message Handler

**File:** `app/src/main/kotlin/com/bothbubbles/services/messaging/IncomingMessageHandler.kt:45-143`

1. **Deduplication** check (prevents race conditions)
2. **Insert message** to Room
3. **Call `syncIncomingAttachments()`**

### 5.2 Sync Incoming Attachments

```kotlin
suspend fun syncIncomingAttachments(messageDto: MessageDto, tempMessageGuid: String?) {
    // Delete temp attachments if replacing optimistic message
    tempMessageGuid?.let { attachmentDao.deleteAttachmentsForMessage(tempGuid) }

    attachments.forEach { attachmentDto ->
        val webUrl = "$serverAddress/api/v1/attachment/${attachmentDto.guid}/download"

        val transferState = if (attachmentDto.isOutgoing) {
            TransferState.UPLOADED.name  // Already uploaded
        } else {
            TransferState.PENDING.name   // Needs download
        }

        val attachment = AttachmentEntity(
            guid = attachmentDto.guid,
            messageGuid = messageDto.guid,
            webUrl = webUrl,
            localPath = null,  // NOT YET DOWNLOADED
            transferState = transferState,
            transferProgress = if (attachmentDto.isOutgoing) 1f else 0f,
            ...
        )
        attachmentDao.insertAttachment(attachment)
    }
}
```

**Key point:** Inbound attachments have `localPath = null` until downloaded.

---

## 6. Download Queue System

### 6.1 AttachmentDownloadQueue

**File:** `app/src/main/kotlin/com/bothbubbles/services/media/AttachmentDownloadQueue.kt`

**Priority system:**

| Priority | Value | Use Case |
|----------|-------|----------|
| IMMEDIATE | 0 | User tapped download |
| ACTIVE_CHAT | 1 | Currently viewed chat |
| VISIBLE | 2 | Visible in scroll area |
| BACKGROUND | 3 | Background sync |

**Configuration:**

| Parameter | Value |
|-----------|-------|
| MAX_CONCURRENT_DOWNLOADS | 2 |
| MAX_RETRY_COUNT | 3 |
| MAX_AUTO_DOWNLOAD_SIZE | 50MB |
| MIN_FREE_MEMORY_BYTES | 20MB |

### 6.2 Enqueue Triggers

1. **Chat opens:** `ChatAttachmentDelegate.downloadPendingAttachments()` → ACTIVE_CHAT priority
2. **Scroll:** `CursorChatMessageListDelegate.updateScrollPosition()` → VISIBLE priority
3. **User tap:** Manual retry → IMMEDIATE priority

### 6.3 Download Execution

**File:** `app/src/main/kotlin/com/bothbubbles/data/repository/AttachmentRepository.kt:189-352`

1. **Check existing file** (skip if already downloaded)
2. **Create output file:** `filesDir/attachments/{guid}.{ext}`
3. **Download from webUrl** with progress tracking
4. **Post-processing:**
   - HEIC→PNG conversion for stickers
   - GIF speed fix
   - Thumbnail generation
5. **Update database:**
   ```kotlin
   attachmentDao.updateLocalPath(attachmentGuid, finalFile.absolutePath)
   ```

---

## 7. UI Rendering Logic

### 7.1 AttachmentContent (Main Router)

**File:** `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentContent.kt:39-168`

**Decision flow:**

```kotlin
val showError = attachment.hasError && !isDownloading && !isRetrying
val showPlaceholder = !showError && (
    attachment.needsDownload ||
    attachment.isDownloading ||
    (attachment.isSticker && attachment.localPath == null)
)

when {
    showError -> AttachmentErrorOverlay(...)
    showPlaceholder -> AttachmentPlaceholder(...)  // Blurhash + download button
    attachment.isGif -> GifAttachment(...)
    attachment.isImage -> ImageAttachment(...)
    attachment.isVideo -> VideoAttachment(...)
    attachment.isAudio -> AudioAttachment(...)
    attachment.isVCard -> ContactAttachment(...)
    else -> FileAttachment(...)
}
```

### 7.2 ImageAttachment URL Resolution

**File:** `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/ImageAttachment.kt:64-66`

```kotlin
val imageUrl = attachment.localPath ?: attachment.webUrl
```

**Simple priority:** Local path preferred, fallback to server URL.

### 7.3 BorderlessImageAttachment (Sticker Logic)

**File:** `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/ImageAttachment.kt:441-473`

```kotlin
val imageUrl = if (attachment.isSticker) {
    attachment.localPath  // Stickers NEVER use webUrl (HEIC doesn't work)
} else {
    val path = attachment.localPath
    if (path != null) {
        // Check if file exists
        val file = File(Uri.parse(path).path ?: "")
        if (file.exists()) path else attachment.webUrl
    } else {
        attachment.webUrl
    }
}
```

**Stickers:** Must use localPath only (server returns HEIC, needs PNG conversion).

### 7.4 Message Bubble Integration

**File:** `app/src/main/kotlin/com/bothbubbles/ui/components/message/MessageSegmentedBubble.kt:379-447`

```kotlin
when (segment) {
    is MessageSegment.MediaSegment -> {
        BorderlessMediaContent(
            attachment = segment.attachment,
            isDownloading = progress != null,
            downloadProgress = progress ?: 0f,
            ...
        )
    }
}
```

---

## 8. Known Issues & Potential Bugs

### 8.1 Outgoing previews drop to placeholders right after upload

**Symptom:** optimistic previews render correctly while the message is queued, but the moment the server acknowledges the send the bubble flashes back to the gray placeholder until the download queue finishes.

**What is actually happening:**
- Optimistic attachment rows write their `localPath` as a `file://` URI that points inside `filesDir/pending_attachments` ([PendingMessageRepository.kt](app/src/main/kotlin/com/bothbubbles/data/repository/PendingMessageRepository.kt#L190-L199)).
- When the server returns real GUIDs, `IMessageSenderStrategy` builds `preservedLocalPaths` but explicitly drops anything whose path contains `/pending_attachments/`, so the new `AttachmentEntity` rows end up with `localPath = null` ([IMessageSenderStrategy.kt](app/src/main/kotlin/com/bothbubbles/services/messaging/sender/IMessageSenderStrategy.kt#L306-L325)).
- `MessageSendWorker` immediately deletes the persisted files once the send succeeds ([MessageSendWorker.kt](app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendWorker.kt#L198-L214)), so there is no file left to fall back to even if the UI kept the path.
- `BorderlessImageAttachment` performs a file-existence check on every recomposition; as soon as Room emits the server-backed row with `localPath = null`, it recomposes into the placeholder ([ImageAttachment.kt](app/src/main/kotlin/com/bothbubbles/ui/components/attachment/ImageAttachment.kt#L441-L487)).

The end result is that a locally available, high-resolution file is discarded the instant the message is accepted, forcing users to wait for a redundant download.

### 8.2 Blurhash values are never persisted

- The server’s `AttachmentDto` currently exposes only basic metadata (guid, size, mime type, etc.)—no blurhash payload ships in `MessageDto.attachments` ([core/network/api/dto/ApiDtos.kt](core/network/src/main/kotlin/com/bothbubbles/core/network/api/dto/ApiDtos.kt#L86-L115)).
- `IncomingMessageHandler.syncIncomingAttachments` simply inserts those DTO values and leaves `blurhash`, `thumbnailPath`, and `localPath` empty ([IncomingMessageHandler.kt](app/src/main/kotlin/com/bothbubbles/services/messaging/IncomingMessageHandler.kt#L110-L141)).
- The UI expects `attachment.blurhash` to render the colorful placeholder and radial progress overlay ([AttachmentPlaceholder.kt](app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentPlaceholder.kt#L66-L139)), but because the field is always `null` every inbound media item shows as a flat gray rectangle with a spinner.
- Although the server exposes `/api/v1/attachment/{guid}/blurhash`, the client never calls it, so we never populate the column via `AttachmentDao.updateBlurhash()`.

### 8.3 Placeholder UX & recomposition cost

- The radial progress indicator uses a white stroke on top of a pale scrim; on light themes it is barely visible, and there is no textual percentage to reinforce progress ([AttachmentPlaceholder.kt](app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentPlaceholder.kt#L100-L139)).
- `BorderlessImageAttachment` allocates new `File` instances and checks `exists()` during every recomposition; once the pending file is deleted this check always fails, so we spend CPU and allocations just to confirm there is nothing to render ([ImageAttachment.kt](app/src/main/kotlin/com/bothbubbles/ui/components/attachment/ImageAttachment.kt#L441-L487)).
- Stickers and other HEIC-only assets depend entirely on a `localPath`, so once GUID replacement nulls that column they become unrecoverable until the download queue finishes a conversion pass.

---

## 9. Key File Locations

### Data Models
| File | Purpose |
|------|---------|
| `data/model/PendingAttachmentInput.kt` | UI input model |
| `core/model/entity/AttachmentEntity.kt` | Database entity |
| `core/model/entity/PendingAttachmentEntity.kt` | Pending queue entity |
| `core/model/entity/TransferState.kt` | Transfer state enum |
| `core/model/entity/AttachmentErrorState.kt` | Error types |
| `ui/components/message/MessageModels.kt` | UI model (AttachmentUiModel) |

### Composition & Sending
| File | Purpose |
|------|---------|
| `ui/chat/delegates/ChatComposerDelegate.kt` | Attachment validation, state |
| `ui/chat/delegates/ChatSendDelegate.kt` | Send orchestration |
| `data/repository/PendingMessageRepository.kt` | Queue & persist |
| `services/messaging/AttachmentPersistenceManager.kt` | File copying |
| `services/messaging/MessageSendWorker.kt` | Background send |
| `services/messaging/sender/IMessageSenderStrategy.kt` | iMessage upload |
| `services/messaging/sender/SmsSenderStrategy.kt` | SMS/MMS send |

### Receiving & Download
| File | Purpose |
|------|---------|
| `services/messaging/IncomingMessageHandler.kt` | Process incoming |
| `services/media/AttachmentDownloadQueue.kt` | Priority queue |
| `data/repository/AttachmentRepository.kt` | Download logic |
| `ui/chat/delegates/ChatAttachmentDelegate.kt` | Download triggers |

### UI Rendering
| File | Purpose |
|------|---------|
| `ui/components/attachment/AttachmentContent.kt` | Main router |
| `ui/components/attachment/ImageAttachment.kt` | Image rendering |
| `ui/components/attachment/VideoAttachment.kt` | Video rendering |
| `ui/components/attachment/AttachmentPlaceholder.kt` | Download placeholder |
| `ui/components/attachment/AttachmentErrorOverlay.kt` | Error display |
| `ui/components/message/MessageSegmentedBubble.kt` | Bubble integration |

---

## 10. Revamp Plan (Updated 2025-12-17)

The goals are (1) keep local previews alive all the way through server acknowledgement, (2) retain high-resolution local files so we do not redownload what we already have, and (3) make download placeholders informative with proper blurhash colors and progress cues.

### 10.1 Preserve local previews through GUID swap (Priority: High)

1. **Store raw paths, not `file://` URIs.** In `PendingMessageRepository.queueMessage()` write `data.persistedPath` directly to `AttachmentEntity.localPath` so downstream code always works with absolute file paths ([PendingMessageRepository.kt](app/src/main/kotlin/com/bothbubbles/data/repository/PendingMessageRepository.kt#L190-L207)).
2. **Move files out of the pending directory before deletion.** Extend `AttachmentPersistenceManager` (or add a helper) so that once the server returns real GUIDs we copy/rename `/pending_attachments/{clientGuid}` into `/attachments/{serverGuid}` and update the DB row in the same transaction.
3. **Stop filtering out pending paths.** Drop the `takeUnless { it.contains("/pending_attachments/") }` guard inside `IMessageSenderStrategy.syncOutboundAttachments` so the preserved map keeps pointing at the file until we relocate it ([IMessageSenderStrategy.kt](app/src/main/kotlin/com/bothbubbles/services/messaging/sender/IMessageSenderStrategy.kt#L306-L325)).
4. **Tighten `MessageSendWorker` cleanup.** Only delete files that were explicitly copied to a permanent location; temporary files should be removed after the move succeeds, not unconditionally ([MessageSendWorker.kt](app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendWorker.kt#L198-L214)).
5. **Add regression coverage.** Write an instrumentation test that sends an image, forces GUID replacement, and asserts the UI still renders from the local file without waiting for the download queue.

### 10.2 Keep high-resolution local files after success (Priority: High)

1. **Reuse existing files for inbound data.** Update `AttachmentRepository.downloadAttachment()` to short-circuit when `localPath` already points to an existing file—mark the transfer as complete and skip the network request ([AttachmentRepository.kt](app/src/main/kotlin/com/bothbubbles/data/repository/AttachmentRepository.kt#L120-L214)).
2. **Centralize retention policy.** Introduce an `AttachmentRetentionManager` that trims old files based on total disk usage or age instead of deleting them immediately after send. Persist the new location (e.g., `/attachments/{guid}`) so both the gallery and download queue can reuse it.
3. **Record provenance.** Annotate `AttachmentEntity` with a `localSource` (pending copy, download, external import) so cleanup routines know whether deleting would break the only copy.
4. **Verify sticker flow.** Ensure sticker conversions save their PNG output next to the final GUID folder so the UI never regresses to HEIC downloads mid-conversation.

### 10.3 Blurhash + download placeholder improvements (Priority: High)

1. **Get blurhash from the server.** Either add `blurhash` to `AttachmentDto` or call the existing `/attachment/{guid}/blurhash` endpoint from `IncomingMessageHandler` right after inserting the attachment row. Persist via `AttachmentDao.updateBlurhash()` once retrieved.
2. **Populate UI models.** Make sure `AttachmentUiModel` exposes the blurhash string so `AttachmentPlaceholder` can render the colorful bitmap ([AttachmentPlaceholder.kt](app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentPlaceholder.kt#L66-L139)).
3. **Redesign the placeholder overlay.** Use a darker scrim, a colored `CircularProgressIndicator`, and add either percentage text or an animated stroke sweep so progress is obvious on both light and dark themes. Keep the download button visible for retry scenarios.
4. **Cache blurhash bitmaps.** Wrap `rememberBlurhashBitmap` in a keyed cache so the expensive decode does not replay during every recomposition, especially while progress updates stream in.

### 10.4 UI & performance polish (Priority: Medium)

1. **Memoize file resolution.** In `BorderlessImageAttachment`, wrap the file-existence logic in `remember(attachment.localPath, attachment.webUrl)` so we do not allocate `File` objects on every recomposition ([ImageAttachment.kt](app/src/main/kotlin/com/bothbubbles/ui/components/attachment/ImageAttachment.kt#L441-L487)).
2. **Expose diagnostics.** Add structured logs around attachment state transitions (queued → uploading → uploaded → downloaded) to make future regressions easier to trace.
3. **Adopt a download snackbar.** Surface a small HUD when background downloads start/finish so users know why placeholders are stuck.
4. **Hardening:** Add a lint/CI check that fails if an `AttachmentEntity` with `localPath != null` would be overwritten by sync without first copying the file, preventing regressions.

---

## Appendix: Transfer State Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                     OUTBOUND (Sending)                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  User selects    Persist to      Create entities    Upload to       │
│  attachment  →  internal  →   with UPLOADING  →   server  →  UPLOADED│
│  (content URI)   storage        state                               │
│                                                                      │
│  PendingAttachmentInput → PendingAttachmentEntity → AttachmentEntity │
│                           (pending_attachments)     (attachments)    │
│                                                                      │
│  localPath = null    persistedPath = /data/...   localPath = file:// │
│                                                                      │
│  After send: temp GUID → server GUID, localPath preserved            │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                     INBOUND (Receiving)                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Server push   Create entity    Enqueue for    Download    Update   │
│  message  →   with PENDING  →  download  →   file  →   localPath    │
│                state                                                 │
│                                                                      │
│  MessageDto.attachments → AttachmentEntity → AttachmentDownloadQueue │
│                           localPath = null    localPath = /data/...  │
│                           webUrl = server/... transferState=DOWNLOADED│
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```
