# UX Plan: Attachment Upload/Download/Send Flow

## Overview

This document provides a comprehensive technical analysis of the attachment handling system in BothBubbles, including the upload, download, and display flows. It identifies current issues and proposes improvements.

---

## Current Architecture

### Flow Diagram

```
SEND FLOW:
┌──────────────────────────────────────────────────────────────────────────────┐
│ User Action                                                                  │
│ ┌─────────────┐   ┌──────────────┐   ┌─────────────┐   ┌──────────────────┐ │
│ │ Camera      │   │ Gallery      │   │ File Picker │   │ GIF Picker       │ │
│ │ Capture     │   │ Selection    │   │             │   │                  │ │
│ └──────┬──────┘   └──────┬───────┘   └──────┬──────┘   └────────┬─────────┘ │
│        │                 │                  │                    │           │
│        └────────────────┼──────────────────┼────────────────────┘           │
│                         ▼                                                    │
│              ┌──────────────────────┐                                        │
│              │ PendingAttachmentInput│                                       │
│              │ - uri: Uri            │                                       │
│              │ - caption: String?    │                                       │
│              │ - mimeType: String?   │                                       │
│              └──────────┬───────────┘                                        │
│                         ▼                                                    │
│              ┌──────────────────────┐                                        │
│              │ ChatViewModel        │                                        │
│              │ _pendingAttachments  │                                        │
│              └──────────┬───────────┘                                        │
│                         │                                                    │
│                    User taps Send                                            │
│                         ▼                                                    │
│              ┌──────────────────────┐                                        │
│              │ ChatSendDelegate     │                                        │
│              │ sendMessage()        │                                        │
│              └──────────┬───────────┘                                        │
│                         ▼                                                    │
│         ┌───────────────────────────────┐                                    │
│         │ PendingMessageRepository      │                                    │
│         │ queueMessage()                │                                    │
│         │ - Persist to app-internal dir │                                    │
│         │ - Create PendingMessageEntity │                                    │
│         │ - Schedule WorkManager job    │                                    │
│         └───────────────┬───────────────┘                                    │
└─────────────────────────┼────────────────────────────────────────────────────┘
                          ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ Background Processing                                                        │
│         ┌───────────────────────────────┐                                    │
│         │ MessageSendWorker (WorkManager)│                                   │
│         └───────────────┬───────────────┘                                    │
│                         ▼                                                    │
│         ┌───────────────────────────────┐                                    │
│         │ MessageSendingService         │                                    │
│         │ sendIMessageWithAttachments() │                                    │
│         │                               │                                    │
│         │ 1. Create temp AttachmentEntity│                                   │
│         │    (localPath = uri.toString())│◄───── Immediate UI display       │
│         │                               │                                    │
│         │ 2. Upload to server           │                                    │
│         │    (with progress tracking)   │                                    │
│         │                               │                                    │
│         │ 3. syncOutboundAttachments()  │                                    │
│         │    - DELETE temp attachment   │◄───── BUG: localPath lost!        │
│         │    - Create server attachment │                                    │
│         │      (webUrl only, no local)  │                                    │
│         └───────────────────────────────┘                                    │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Key Components

### 1. Attachment Input Sources

| Source | File | Notes |
|--------|------|-------|
| Camera | [InAppCameraScreen.kt](app/src/main/kotlin/com/bothbubbles/ui/camera/InAppCameraScreen.kt) | CameraX integration, saves to MediaStore |
| Gallery | [MediaPickerPanel.kt](app/src/main/kotlin/com/bothbubbles/ui/chat/composer/panels/MediaPickerPanel.kt) | Uses PickMultipleVisualMedia (max 10) |
| Files | [AttachmentPickerPanel.kt](app/src/main/kotlin/com/bothbubbles/ui/components/input/AttachmentPickerPanel.kt) | GetMultipleContents() |
| GIFs | [GifPickerPanel.kt](app/src/main/kotlin/com/bothbubbles/ui/chat/composer/panels/GifPickerPanel.kt) | GIPHY integration |

### 2. Data Models

**PendingAttachmentInput** - Used in UI layer before send:
```kotlin
// app/src/main/kotlin/com/bothbubbles/data/model/PendingAttachmentInput.kt
data class PendingAttachmentInput(
    val uri: Uri,
    val caption: String? = null,
    val mimeType: String? = null,
    val name: String? = null,
    val size: Long? = null
)
```

**AttachmentEntity** - Database model:
```kotlin
// app/src/main/kotlin/com/bothbubbles/data/local/db/entity/AttachmentEntity.kt
// Key fields for transfer tracking:
- transferState: PENDING | DOWNLOADING | UPLOADED | UPLOADING | FAILED
- transferProgress: Float (0.0 to 1.0)
- localPath: String? (local file path when downloaded)
- webUrl: String? (server URL for download)
- blurhash: String? (placeholder preview - NOT populated by server!)
- errorType/errorMessage: Error details for failed transfers
```

### 3. Services

| Service | File | Purpose |
|---------|------|---------|
| MessageSendingService | [MessageSendingService.kt:560-690](app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendingService.kt#L560-L690) | Upload attachments, create temp records |
| AttachmentPersistenceManager | [AttachmentPersistenceManager.kt](app/src/main/kotlin/com/bothbubbles/services/messaging/AttachmentPersistenceManager.kt) | Copy URIs to app-internal storage |
| AttachmentDownloadQueue | [AttachmentDownloadQueue.kt](app/src/main/kotlin/com/bothbubbles/services/media/AttachmentDownloadQueue.kt) | Priority-based download management |
| AttachmentRepository | [AttachmentRepository.kt:190-353](app/src/main/kotlin/com/bothbubbles/data/repository/AttachmentRepository.kt#L190-L353) | Download, HEIC conversion, GIF fix |

---

## Identified Issues

### Issue #1: Camera "Send" Button Mislabeled (UX Confusion)

**Severity**: Medium
**File**: [InAppCameraScreen.kt:431](app/src/main/kotlin/com/bothbubbles/ui/camera/InAppCameraScreen.kt#L431)

**Current Behavior**:
After capturing a photo, the preview screen shows two buttons:
- "Retake" - Clear and reasonable
- "Send" - **Misleading** - doesn't send, just attaches

**User Expectation**: Tapping "Send" will immediately send the photo as a message.

**Actual Behavior**: Photo is added to pending attachments, user returns to chat composer, must tap Send again.

**Code**:
```kotlin
// PhotoPreviewScreen (line 431)
Button(
    onClick = onSend,  // Actually means "attach" not "send"
    ...
) {
    Text("Send")  // MISLEADING
}
```

**Recommendation**: Change button text to one of:
- "Attach" (clear and accurate)
- "Add to Message" (verbose but explicit)
- "Use Photo" (iOS-style)

---

### Issue #2: Empty Bubble → Image Appears → Image Breaks

**Severity**: High
**Files**:
- [MessageSendingService.kt:956-989](app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendingService.kt#L956-L989)
- [AttachmentContent.kt:433](app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentContent.kt#L433)

**Root Cause**: When an outgoing attachment upload completes, the temp attachment (with local file reference) is deleted and replaced with a server attachment that ONLY has a webUrl, losing the local path.

**Sequence of Events**:
```
1. User sends image
2. Temp AttachmentEntity created:
   - guid: "temp_xxx"
   - localPath: "content://media/external/images/123"  ← CAN DISPLAY
   - transferState: UPLOADING

3. UI shows image from localPath immediately ✓

4. Upload completes, syncOutboundAttachments() called:
   - DELETES temp attachment (line 958-960)
   - Creates NEW attachment from server response:
     - guid: "server_abc"
     - localPath: null  ← LOST!
     - webUrl: "https://server/api/v1/attachment/abc/download"
     - transferState: UPLOADED

5. UI must now load from webUrl instead of local file

6. If webUrl fails (auth expired, server slow, network hiccup):
   - Coil shows error state
   - User sees "broken image" icon
```

**Code Evidence**:
```kotlin
// syncOutboundAttachments (line 956-989)
private suspend fun syncOutboundAttachments(messageDto: MessageDto, tempMessageGuid: String? = null) {
    // DELETE temp attachment with localPath
    tempMessageGuid?.let { tempGuid ->
        attachmentDao.deleteAttachmentsForMessage(tempGuid)  // line 959
    }

    // Create new attachment WITHOUT localPath
    val attachment = AttachmentEntity(
        guid = attachmentDto.guid,
        messageGuid = messageDto.guid,
        // ... other fields
        webUrl = webUrl,
        localPath = null,  // NOT PRESERVED!
        transferState = TransferState.UPLOADED.name,
    )
}
```

**Why Image "Breaks" Later**:
```kotlin
// AttachmentContent.kt (line 433)
val imageUrl = attachment.localPath ?: attachment.webUrl
// Falls back to webUrl which may fail due to:
// - Server authentication token expiration
// - Server connection timeout
// - Coil cache eviction + server unavailable
```

**Recommendations**:
1. **Preserve local file for outgoing attachments**: Copy the attachment to a permanent app-internal location before upload, set localPath to this copy
2. **Re-download after upload**: After successful upload, immediately download the server version to local cache
3. **Keep temp attachment localPath**: Instead of deleting, update the guid in place to preserve localPath

---

### Issue #3: No Blurhash From Server

**Severity**: Medium
**Files**:
- [ApiDtos.kt:114-128](app/src/main/kotlin/com/bothbubbles/data/remote/api/dto/ApiDtos.kt#L114-L128)
- [AttachmentRepository.kt:162](app/src/main/kotlin/com/bothbubbles/data/repository/AttachmentRepository.kt#L162)

**Problem**: The BlueBubbles server API does not provide blurhash in AttachmentDto. The entity has a blurhash column but it's never populated during sync.

**Impact**:
- Placeholder for downloading images shows solid gray color instead of blurred preview
- User sees "empty" placeholder boxes before images load

**AttachmentDto** (no blurhash field):
```kotlin
data class AttachmentDto(
    val guid: String,
    val originalRowId: Int? = null,
    val uti: String? = null,
    val mimeType: String? = null,
    val isOutgoing: Boolean = false,
    val transferName: String? = null,
    val totalBytes: Long? = null,
    val height: Int? = null,
    val width: Int? = null,
    val hasLivePhoto: Boolean = false,
    val hideAttachment: Boolean = false,
    val isSticker: Boolean = false,
    val metadata: Map<String, Any>? = null
    // NO blurhash!
)
```

**Recommendations**:
1. **Server-side**: Request BlueBubbles server team to generate and include blurhash
2. **Client-side fallback**: Generate blurhash locally after download, cache for future displays
3. **Alternative placeholder**: Use dominant color extraction or generic pattern based on mime type

---

### Issue #4: Race Condition in Multi-Attachment Sends

**Severity**: Low
**File**: [PendingMessageRepository.kt:91-119](app/src/main/kotlin/com/bothbubbles/data/repository/PendingMessageRepository.kt#L91-L119)

**Problem**: If persistence fails for one attachment in a multi-attachment message, previously inserted attachments remain orphaned.

**Code**:
```kotlin
attachments.forEachIndexed { index, attachment ->
    val persistedPath = attachmentPersistenceManager.persistAttachment(uri, localId)
    // If this throws for attachment 3 of 5:
    // - Attachments 1-2 already inserted to DB
    // - Message still queued with incomplete attachments
    attachmentDao.insertPendingAttachment(...)
}
```

**Recommendations**:
1. Wrap in database transaction, rollback on any failure
2. Or: Persist all files first, then insert all DB records

---

### Issue #5: Coil Image Loading Has No Timeout Handling

**Severity**: Medium
**File**: [AttachmentContent.kt:458-474](app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentContent.kt#L458-L474)

**Problem**: When loading from webUrl, AsyncImage uses Coil defaults. If the server is slow or unresponsive, the loading spinner shows indefinitely.

**Code**:
```kotlin
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(imageUrl)
        .crossfade(true)
        .size(maxWidthPx, targetHeightPx)
        .precision(Precision.INEXACT)
        // NO timeout configuration!
        .build(),
    ...
)
```

**Recommendations**:
1. Add OkHttp call timeout to image requests
2. Show "retry" option after timeout
3. Consider adding `.error(R.drawable.placeholder_error)` placeholder

---

### Issue #6: Image Error State Not Recoverable Without Scroll

**Severity**: Low
**File**: [AttachmentContent.kt:428-429](app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentContent.kt#L428-L429)

**Problem**: Error state is tracked with `remember { mutableStateOf(false) }`. Once an image fails, it stays in error state until the composable is recreated (user scrolls away and back).

**Code**:
```kotlin
var isLoading by remember { mutableStateOf(true) }
var isError by remember { mutableStateOf(false) }
```

**Recommendations**:
1. Add tap-to-retry on error state
2. Or add a retry button overlay similar to AttachmentErrorOverlay

---

## Download Flow Analysis

### Priority Queue System

**File**: [AttachmentDownloadQueue.kt](app/src/main/kotlin/com/bothbubbles/services/media/AttachmentDownloadQueue.kt)

```kotlin
enum class DownloadPriority(val value: Int) {
    IMMEDIATE(0),      // User manually tapped download
    ACTIVE_CHAT(1),    // Currently viewing this chat
    VISIBLE(2),        // Visible in scroll
    BACKGROUND(3)      // Background sync
}

// Config
MAX_CONCURRENT_DOWNLOADS = 2
MAX_AUTO_DOWNLOAD_SIZE = 50MB  // Larger requires manual tap
```

### Download Logic

**File**: [AttachmentRepository.kt:190-353](app/src/main/kotlin/com/bothbubbles/data/repository/AttachmentRepository.kt#L190-L353)

```
1. Check if already downloaded (localPath exists & file valid)
2. Create target file with proper extension
3. Download from webUrl (with retry & exponential backoff)
4. Special handling:
   - Stickers: Try ?original=true for HEIC with transparency
   - HEIC/HEIF: Convert to PNG to preserve transparency
   - GIFs: Fix zero-delay frames (files <10MB only)
5. Generate thumbnail
6. Update DB with localPath and thumbnailPath
```

### Error Handling

**File**: [AttachmentEntity.kt:101-119](app/src/main/kotlin/com/bothbubbles/data/local/db/entity/AttachmentEntity.kt#L101-L119)

```kotlin
val errorType: String? = null,      // Maps to AttachmentErrorState
val errorMessage: String? = null,   // User-friendly message
val retryCount: Int = 0             // Max 3 retries before permanent failure
```

**Error Types** (from AttachmentErrorOverlay):
- NETWORK_TIMEOUT / NO_CONNECTION → Wifi off icon, retryable
- SERVER_ERROR → Cloud off icon, retryable
- STORAGE_FULL → Storage icon, retryable (after user clears space)
- FILE_TOO_LARGE / FORMAT_UNSUPPORTED → Warning icon, NOT retryable

---

## Display Flow Analysis

### Rendering Decision Tree

**File**: [AttachmentContent.kt:91-183](app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentContent.kt#L91-L183)

```
AttachmentContent receives AttachmentUiModel
│
├─ hasError && !downloading && !retrying?
│  └─ YES → AttachmentErrorOverlay (blurhash bg + retry button)
│
├─ needsDownload || isDownloading || (isSticker && localPath==null)?
│  └─ YES → AttachmentPlaceholder (blurhash + download button/spinner)
│
├─ isGif?
│  └─ YES → GifAttachment (Coil GIF decoder)
│
├─ isImage?
│  └─ YES → ImageAttachment (AsyncImage with Coil)
│
├─ isVideo?
│  └─ YES → InlineVideoAttachment (ExoPlayer)
│
├─ isAudio?
│  └─ YES → AudioAttachment (waveform + play controls)
│
├─ isVCard?
│  └─ YES → VCardAttachment (contact preview)
│
└─ else → FileAttachment (icon + metadata)
```

### Placeholder with Blurhash

**File**: [AttachmentContent.kt:189-418](app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentContent.kt#L189-L418)

```kotlin
val blurhashBitmap = rememberBlurhashBitmap(
    blurhash = attachment.blurhash,  // Usually NULL (server doesn't provide)
    aspectRatio = aspectRatio
)

// Falls back to solid color when blurhash is null
```

---

## Recommendations Summary

### High Priority

| Issue | Fix | Files |
|-------|-----|-------|
| Image breaks after upload | Preserve localPath in syncOutboundAttachments | MessageSendingService.kt |
| Empty placeholders | Generate blurhash client-side or request server support | AttachmentRepository.kt, ApiDtos.kt |
| Camera "Send" button | Change to "Attach" or "Use Photo" | InAppCameraScreen.kt:431 |

### Medium Priority

| Issue | Fix | Files |
|-------|-----|-------|
| No image load timeout | Add OkHttp timeout to ImageRequest | AttachmentContent.kt |
| Error not recoverable | Add tap-to-retry on error state | AttachmentContent.kt |

### Low Priority

| Issue | Fix | Files |
|-------|-----|-------|
| Multi-attachment race | Wrap persistence in transaction | PendingMessageRepository.kt |
| Large GIF speed not fixed | Extend fix to files >10MB or warn user | AttachmentRepository.kt |

---

## Proposed Fix: Preserve Local Path for Outgoing Attachments

The primary fix for Issue #2 involves modifying `syncOutboundAttachments` to preserve the local file:

**Option A**: Copy attachment to permanent location before upload
```kotlin
// In sendIMessageWithAttachments, before creating temp attachment:
val permanentPath = copyToPermanentStorage(uri)  // New helper

val tempAttachment = AttachmentEntity(
    guid = tempAttGuid,
    messageGuid = tempGuid,
    localPath = permanentPath,  // Use permanent path, not content:// URI
    // ...
)
```

**Option B**: Update guid in place instead of delete/insert
```kotlin
private suspend fun syncOutboundAttachments(messageDto: MessageDto, tempMessageGuid: String? = null) {
    // DON'T delete temp attachments
    // Instead, update them with server data while preserving localPath

    val existingAttachments = tempMessageGuid?.let {
        attachmentDao.getAttachmentsForMessage(it)
    } ?: emptyList()

    messageDto.attachments?.forEachIndexed { index, attachmentDto ->
        val existing = existingAttachments.getOrNull(index)
        val localPath = existing?.localPath  // Preserve!

        val attachment = AttachmentEntity(
            guid = attachmentDto.guid,
            messageGuid = messageDto.guid,
            localPath = localPath,  // PRESERVED
            webUrl = webUrl,
            // ...
        )
        attachmentDao.insertAttachment(attachment)
    }

    // Now safe to delete temp records
    tempMessageGuid?.let { attachmentDao.deleteAttachmentsForMessage(it) }
}
```

---

## Testing Checklist

- [ ] Send single image, verify it displays before and after upload completes
- [ ] Send multi-image message, verify all display correctly
- [ ] Force-kill app during upload, reopen, verify retry works
- [ ] Receive image while on chat, verify placeholder → image transition
- [ ] Receive image while app backgrounded, verify notification + download
- [ ] Test with server disconnected, verify error states and retry
- [ ] Test camera capture → "Attach" flow clarity
- [ ] Test large attachment (>50MB) manual download flow
