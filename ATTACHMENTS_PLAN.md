# Attachment Feature Implementation Plan

This plan outlines the step-by-step implementation of attachment features described in [ATTACHMENTS.md](ATTACHMENTS.md), aligned with the architecture patterns from [CLAUDE.md](CLAUDE.md).

---

## Phase 1: Foundation Completion

The foundation is mostly complete. One critical gap remains:

### 1.1 Clear Error States with Retry

**Priority: Critical | Effort: Medium**

- [x] **Create `AttachmentErrorState` sealed class** in `util/error/`

  - NetworkTimeout, ServerError, FileTooLarge, FormatUnsupported, StorageFull
  - Include user-friendly message strings for each

- [x] **Add error state to `AttachmentEntity`**

  - Add `errorType: String?` and `errorMessage: String?` columns
  - Create Room migration

- [x] **Update `AttachmentDownloadQueue`**

  - Capture specific error types on failure
  - Store error details in entity
  - Implement exponential backoff with max retry count

- [x] **Create `AttachmentErrorOverlay` composable** in `ui/components/attachment/`

  - Display error icon (⚠)
  - Show user-friendly error message
  - Retry button with tap handler
  - Match blurhash/placeholder aspect ratio

- [x] **Update `AttachmentContent.kt`**

  - Integrate error overlay for failed states
  - Wire retry button to `AttachmentRepository.retryDownload()`

- [x] **Add upload error handling to `MessageSendingService`**
  - Capture and store upload errors in `PendingAttachmentEntity`
  - Surface errors in chat UI via pending message state

---

## Phase 2: User Control

### 2.1 Quality Selection (Per-Message)

**Priority: High | Effort: Medium**

- [x] **Create `AttachmentQuality` enum** in `data/model/`

  ```kotlin
  enum class AttachmentQuality(val maxDimension: Int, val jpegQuality: Int) {
      AUTO(0, 0),           // Server decides
      STANDARD(1600, 70),   // ~200KB
      HIGH(3000, 85),       // ~1MB
      ORIGINAL(-1, 100)     // No compression
  }
  ```

- [ ] **Add quality preference to DataStore** in `data/local/prefs/`

  - `defaultAttachmentQuality: AttachmentQuality`
  - `rememberLastQuality: Boolean`

- [x] **Create `QualitySelectionSheet` composable** in `ui/chat/components/`

  - Bottom sheet with radio options
  - Show tradeoff descriptions (size/speed)
  - "Remember my choice" checkbox

- [ ] **Add quality icon to message composer**

  - Tap to open `QualitySelectionSheet`
  - Show current quality indicator

- [x] **Create `ImageCompressor` utility** in `util/media/`

  - Implement compression for each quality level
  - Preserve EXIF orientation
  - Handle HEIC conversion before compression

- [x] **Update `PendingAttachmentEntity`**

  - Add `quality: String` column
  - Migration to add column

- [x] **Integrate compression in `MessageSendingService`**
  - Apply compression based on selected quality before upload
  - Skip compression for ORIGINAL quality

### 2.2 Quality Selection (Global Setting)

**Priority: Medium | Effort: Low**

- [ ] **Add Settings screen** at `ui/settings/messages/SendQualityScreen.kt`

  - Radio group with all quality options
  - Description for each option

- [ ] **Add navigation** from Settings > Messages > Send Quality
  - Update `SettingsScreen.kt` with link
  - Add route to `Screen.kt` and `NavHost.kt`

### 2.3 Edit Before Send (Crop/Rotate)

**Priority: High | Effort: Medium**

- [ ] **Implement System Editor Integration**

  - Launch `Intent(Intent.ACTION_EDIT)` with the attachment URI
  - Handle the result to update the attachment
  - Fallback to internal editor only if system editor is unavailable

- [x] **Create `AttachmentEditScreen` (Fallback)** at `ui/chat/edit/`

  - Use standard M3 TopAppBar and Icons
  - Implement basic crop/rotate if system intent fails

- [ ] **Update `AttachmentPreviewStrip`**

  - Add edit button (✎) overlay on each thumbnail
  - Launch editor on tap

- [ ] **Persist edits to pending attachment**
  - Save edited file to app storage
  - Update `PendingAttachmentEntity` with new path

### 2.4 Caption Support

**Priority: Medium | Effort: Medium**

- [x] **Add `caption: String?` to `PendingAttachmentEntity`**

  - Room migration

- [x] **Update `AttachmentEditScreen`**

  - Add caption text field at bottom
  - Persist caption with attachment

- [ ] **Update message sending flow**

  - Include caption in API request
  - Handle server-side caption support (if available)

- [x] **Display captions in message bubbles**
  - Add caption text below attachment in `AttachmentContent`
  - Style as secondary text

---

## Phase 3: Polish ✅ COMPLETE

### 3.1 Attachment Reordering ✅

**Priority: Low | Effort: Medium**

- [x] **Enable drag-and-drop in `AttachmentPreviewStrip`**

  - Created `ReorderableAttachmentStrip` component with drag modifiers
  - Visual feedback during drag (elevation, scale)

- [x] **Update pending attachments order**

  - Added `reorderAttachments()` to ChatViewModel
  - Reorder `List<PendingAttachmentInput>` based on drag result

- [x] **Add reorder handles**
  - Long-press to enable drag mode
  - Visual drag handle icon shown during drag

### 3.2 Android Photo Picker Integration ✅

**Priority: Medium | Effort: Low**

- [x] **Implement `ActivityResultContracts.PickVisualMedia`**

  - Already implemented in `MediaPickerPanel.kt`
  - Uses `PickMultipleVisualMedia(maxItems = 10)`
  - Provides consistent, privacy-friendly UI

- [x] **Handle Multi-Selection**

  - Uses `PickMultipleVisualMedia` contract
  - Processes returned list of URIs

- [x] **Fallback for older devices**
  - Google Play Services handles the backport automatically

### 3.3 Edit Before Send (Draw/Text) ✅

**Priority: Medium | Effort: High**

- [x] **Add drawing canvas to `AttachmentEditScreen`**

  - Created `DrawingCanvas` composable with touch-based brush strokes
  - Color picker with 9 colors
  - Brush size selector (4 presets)
  - Eraser tool with visual feedback

- [x] **Add text overlay tool**

  - Created `TextOverlayLayer` with draggable text items
  - Font size selection (S/M/L/XL)
  - Color selection
  - Background toggle
  - Drag to position

- [x] **Flatten edits on save**
  - Composite canvas drawings onto image
  - Render text overlays to bitmap
  - Save as new file in cache

### 3.4 Date-Grouped Gallery View ✅

**Priority: Low | Effort: Medium**

- [x] **Update `MediaGalleryScreen`** at `ui/chat/details/`

  - Grid layout with sticky month headers
  - Uses `LazyVerticalGrid` with `GridItemSpan` for headers

- [x] **Group attachments by month**

  - Added `getMediaWithDatesForChat()` query to DAO
  - Section headers: "This Month", "Last Month", "December 2024", etc.

- [x] **Add filter dropdown**
  - Filter by Images, Videos, or All Media in ViewModel

---

## Phase 4: Delight

### 4.1 Keyboard Rich Content Support

**Priority: Medium | Effort: Medium**

- [ ] **Implement `OnReceiveContentListener`**

  - Support Android 12+ unified content insertion API
  - Handle content insertion from Gboard (GIFs, Stickers) directly in the text field

- [ ] **Support `InputConnectionCompat` (Legacy)**

  - Implement `commitContent` API for older Android versions
  - Ensure seamless GIF insertion from keyboard

- [ ] **Remove custom GIF picker requirement**
  - Rely on user's keyboard for rich content discovery

### 4.2 Sticker Support

**Priority: Low | Effort: Medium**

- [ ] **Create sticker data model**

  - Sticker packs with metadata
  - Individual sticker assets

- [ ] **Create `StickerPickerSheet`**

  - Horizontal pack selector
  - Grid of stickers per pack

- [ ] **Handle sticker sending**
  - Send as image attachment
  - Preserve transparency (PNG)

### 4.3 Handwriting/Drawing

**Priority: Low | Effort: High**

- [ ] **Create `DrawingScreen`** at `ui/chat/draw/`

  - Blank canvas (white or transparent)
  - Full brush/color toolkit

- [ ] **Add "Draw" option to attachment picker**
  - Launch `DrawingScreen`
  - Save drawing as PNG attachment

---

## Integration Phase: Chat Composer UI

This phase bridges the Attachments system with the new Chat Composer UI (see `docs/CHAT_COMPOSER_UI.md`).

### Integration Timing

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         INTEGRATION TIMELINE                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ATTACHMENTS                              COMPOSER                       │
│  ───────────                              ────────                       │
│                                                                         │
│  Phase 1: Error States ──────────────────▶ Prerequisite for integration │
│  (COMPLETE)                                                             │
│                                                                         │
│  Phase 2.1: Quality Selection ◀──────────── Composer Phase 5 (Panels)   │
│  (provides QualitySelectionSheet)          (consumes sheet)             │
│                                                                         │
│  Phase 2.3: Edit Before Send ◀───────────── Composer Phase 6 (Thumbnails)│
│  (provides AttachmentEditScreen)           (triggers edit)              │
│                                                                         │
│  Phase 3.2: Photo Picker ◀───────────────── Composer MediaPickerPanel   │
│  (provides PickVisualMedia)                (uses picker)                │
│                                                                         │
│                    ════════════════════════════════                     │
│                    ║  INTEGRATION CHECKPOINT  ║                     │
│                    ║  Execute integration     ║                     │
│                    ║  tasks here              ║                     │
│                    ════════════════════════════════                     │
│                                                                         │
│  Phase 3+ (Polish) ◀─────────────────────── Composer Phase 7 (Polish)   │
│  (parallel development OK)                 (parallel development OK)   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Prerequisites (Must Complete Before Integration)

**Attachments Side:**

- [x] Phase 1.1: `AttachmentErrorState` sealed class created
- [x] Phase 1.1: `AttachmentErrorOverlay` composable created
- [x] Phase 1.1: Error columns added to `AttachmentEntity`

**Composer Side:**

- [ ] Phase 5: `MediaPickerPanel` created (structure only)
- [ ] Phase 6: `AttachmentThumbnailRow` created (structure only)

### Integration Tasks

#### Task 1: Export Shared Types

Create shared model in `data/model/` accessible to both systems:

```kotlin
// data/model/AttachmentItem.kt
package com.bothbubbles.data.model

import android.net.Uri
import com.bothbubbles.util.error.AttachmentErrorState

/**
 * Unified attachment model used by both Composer UI and Attachment systems.
 * Represents a pending attachment before send.
 */
data class AttachmentItem(
    val id: String,
    val uri: Uri,
    val mimeType: String,
    val fileName: String?,
    val fileSize: Long,
    val width: Int?,
    val height: Int?,
    val quality: AttachmentQuality = AttachmentQuality.AUTO,
    val errorState: AttachmentErrorState? = null,
    val caption: String? = null,
    val thumbnailUri: Uri? = null,
    val isProcessing: Boolean = false
) {
    val isImage: Boolean
        get() = mimeType.startsWith("image/")

    val isVideo: Boolean
        get() = mimeType.startsWith("video/")

    val hasError: Boolean
        get() = errorState != null
}

// data/model/AttachmentQuality.kt
enum class AttachmentQuality(
    val maxDimension: Int,
    val jpegQuality: Int,
    val displayName: String
) {
    AUTO(0, 0, "Auto"),
    STANDARD(1600, 70, "Standard"),
    HIGH(3000, 85, "High"),
    ORIGINAL(-1, 100, "Original")
}
```

#### Task 2: Provide Composable APIs for Composer

Ensure attachment components expose clean composable APIs:

```kotlin
// ui/components/attachment/AttachmentErrorOverlay.kt
@Composable
fun AttachmentErrorOverlay(
    error: AttachmentErrorState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false  // ADD: for thumbnail size
)

// ui/chat/components/QualitySelectionSheet.kt
@Composable
fun QualitySelectionSheet(
    currentQuality: AttachmentQuality,
    onQualitySelected: (AttachmentQuality) -> Unit,
    onDismiss: () -> Unit,
    rememberChoice: Boolean,
    onRememberChoiceChanged: (Boolean) -> Unit
)
```

#### Task 3: Provide Entity Converters

Add extension functions to convert between layers:

```kotlin
// In PendingAttachmentEntity.kt or a separate Mappers.kt
fun PendingAttachmentEntity.toAttachmentItem(): AttachmentItem {
    return AttachmentItem(
        id = this.id.toString(),
        uri = Uri.parse(this.localPath),
        mimeType = this.mimeType,
        fileName = this.fileName,
        fileSize = this.fileSize,
        width = this.width,
        height = this.height,
        quality = AttachmentQuality.valueOf(this.quality ?: "AUTO"),
        errorState = this.errorType?.let {
            AttachmentErrorState.fromString(it, this.errorMessage)
        },
        caption = this.caption,
        thumbnailUri = this.thumbnailPath?.let { Uri.parse(it) },
        isProcessing = this.uploadState == UploadState.PROCESSING
    )
}

fun AttachmentItem.toPendingEntity(messageId: Long): PendingAttachmentEntity {
    // Reverse mapping for creating/updating entities
}
```

#### Task 4: Wire Photo Picker to Attachment Processing

When composer receives URIs from Photo Picker, route to attachment processing:

```kotlin
// In ChatViewModel or AttachmentDelegate
fun handleMediaSelected(uris: List<Uri>) {
    viewModelScope.launch {
        uris.forEach { uri ->
            // Use attachment system's processing pipeline
            val result = attachmentRepository.prepareAttachment(
                uri = uri,
                quality = composerState.value.attachmentQuality
            )
            result.fold(
                onSuccess = { item ->
                    updateComposerState { it.copy(
                        attachments = it.attachments + item
                    )}
                },
                onFailure = { error ->
                    // Show error in composer
                    updateComposerState { it.copy(
                        attachmentWarning = AttachmentWarning.fromError(error)
                    )}
                }
            )
        }
    }
}
```

#### Task 5: Connect Edit Flow

Wire the edit action from composer thumbnails to attachment edit screen:

```kotlin
// Navigation setup in NavHost.kt
composable<Screen.AttachmentEdit>(
    // ... existing setup
) { backStackEntry ->
    val args = backStackEntry.toRoute<Screen.AttachmentEdit>()
    AttachmentEditScreen(
        attachmentUri = args.uri,
        onSave = { editedUri ->
            // Return result to composer
            navController.previousBackStackEntry
                ?.savedStateHandle
                ?.set("edited_uri", editedUri.toString())
            navController.popBackStack()
        },
        onCancel = { navController.popBackStack() }
    )
}

// In ChatViewModel, observe edit result
init {
    savedStateHandle.getStateFlow<String?>("edited_uri", null)
        .filterNotNull()
        .onEach { uriString ->
            val editedUri = Uri.parse(uriString)
            handleAttachmentEdited(composerState.value.editingAttachment, editedUri)
        }
        .launchIn(viewModelScope)
}
```

### Component Responsibilities

| Component                   | Owner       | Responsibility                     |
| --------------------------- | ----------- | ---------------------------------- |
| `AttachmentItem`            | Shared      | Data model for pending attachments |
| `AttachmentQuality`         | Attachments | Quality enum and compression logic |
| `AttachmentErrorState`      | Attachments | Error types and user messages      |
| `AttachmentErrorOverlay`    | Attachments | Error display UI                   |
| `QualitySelectionSheet`     | Attachments | Quality picker UI                  |
| `AttachmentEditScreen`      | Attachments | Crop/rotate/draw UI                |
| `ImageCompressor`           | Attachments | Compression implementation         |
| `AttachmentThumbnailRow`    | Composer    | Thumbnail display and actions      |
| `MediaPickerPanel`          | Composer    | Picker trigger UI                  |
| `ComposerState.attachments` | Composer    | Attachment list state              |

### API Contract

The composer expects these APIs from the attachment system:

```kotlin
interface AttachmentOperations {
    /**
     * Prepare an attachment from a URI for sending.
     * Validates, generates thumbnail, calculates dimensions.
     */
    suspend fun prepareAttachment(
        uri: Uri,
        quality: AttachmentQuality
    ): Result<AttachmentItem>

    /**
     * Retry a failed attachment preparation.
     */
    suspend fun retryAttachment(item: AttachmentItem): Result<AttachmentItem>

    /**
     * Apply compression to an attachment before upload.
     * Called by MessageSendingService at send time.
     */
    suspend fun compressForUpload(
        item: AttachmentItem,
        quality: AttachmentQuality
    ): Result<Uri>

    /**
     * Clean up temporary files for an attachment.
     */
    suspend fun cleanup(item: AttachmentItem)
}
```

### Testing the Integration

- [ ] Photo Picker selection → creates `AttachmentItem` → displays in thumbnail row
- [ ] Error during preparation → `AttachmentErrorState` populated → overlay shown
- [ ] Retry button tap → `retryAttachment()` called → state updated
- [ ] Edit button tap → navigates to `AttachmentEditScreen` → returns edited URI
- [ ] Quality change → persisted → applied at compression time
- [ ] Send message → `compressForUpload()` called → compressed URI used for upload
- [ ] Message sent → `cleanup()` called → temp files removed

---

## Technical Prerequisites

### Before Starting Phase 2:

- [ ] **Review and test error handling framework** (`util/error/AppError.kt`)

  - Ensure `safeCall {}` works for attachment operations
  - Verify Result handling in repositories

- [ ] **Audit `AttachmentRepository`** for error propagation
  - All operations should return `Result<T>`
  - Errors should be typed (not generic exceptions)

### Before Starting Phase 3:

- [ ] **Performance baseline**

  - Profile thumbnail loading in current gallery
  - Measure memory usage with 100+ attachments visible

- [ ] **Review Coil configuration**
  - Verify cache sizes are appropriate
  - Test crossfade animations

---

## Architecture Notes

### New Files to Create

```
app/src/main/kotlin/com/bothbubbles/
├── data/
│   └── model/
│       └── AttachmentQuality.kt
├── ui/
│   ├── chat/
│   │   ├── components/
│   │   │   └── QualitySelectionSheet.kt
│   │   ├── edit/
│   │   │   └── AttachmentEditScreen.kt
│   │   ├── picker/
│   │   │   └── GalleryPickerScreen.kt
│   │   ├── gallery/
│   │   │   └── MediaGalleryScreen.kt
│   │   └── draw/
│   │       └── DrawingScreen.kt
│   ├── components/
│   │   └── attachment/
│   │       └── AttachmentErrorOverlay.kt
│   └── settings/
│       └── messages/
│           └── SendQualityScreen.kt
└── util/
    ├── error/
    │   └── AttachmentErrorState.kt
    └── media/
        └── ImageCompressor.kt
```

### Dependency Injection

New bindings needed in `di/AppModule.kt`:

- `ImageCompressor` (singleton, depends on app context)

### Database Migrations

| Phase | Migration       | Changes                                               |
| ----- | --------------- | ----------------------------------------------------- |
| 1.1   | `Migration_X_Y` | Add `errorType`, `errorMessage` to `AttachmentEntity` |
| 2.1   | `Migration_Y_Z` | Add `quality` to `PendingAttachmentEntity`            |
| 2.4   | `Migration_Z_W` | Add `caption` to `PendingAttachmentEntity`            |

---

## Estimated Scope

| Phase     | Tasks  | Complexity  |
| --------- | ------ | ----------- |
| Phase 1   | 6      | Medium      |
| Phase 2   | 18     | High        |
| Phase 3   | 12     | High        |
| Phase 4   | 12     | Medium-High |
| **Total** | **48** |             |

---

## Getting Started

1. Start with **Phase 1.1** (Error States) - this is critical and unblocks better UX
2. Move to **Phase 2.1** (Quality Selection) - highest user impact
3. Tackle **Phase 2.3** (Crop/Rotate) - depends on library selection
4. Defer Phase 3-4 until core functionality is solid

---

_Plan created: December 2024_
_Based on: ATTACHMENTS.md vision document_
