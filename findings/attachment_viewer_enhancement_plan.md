# Attachment Viewer Enhancement Plan

## Overview

BothBubbles already has a `MediaViewerScreen` with basic functionality. This plan enhances it with:
- **Bug fixes** (swipe not working, query too restrictive)
- Sender info header (avatar, name)
- Save to device (gallery/Downloads)
- Enhanced video playback controls
- Better share options

## Current State Analysis

### Existing Features (ui/media/)
| File | Feature |
|------|---------|
| [MediaViewerScreen.kt](app/src/main/kotlin/com/bothbubbles/ui/media/MediaViewerScreen.kt) | HorizontalPager, top bar, page indicator |
| [MediaViewerViewModel.kt](app/src/main/kotlin/com/bothbubbles/ui/media/MediaViewerViewModel.kt) | Media list loading, download progress |
| [MediaPage.kt](app/src/main/kotlin/com/bothbubbles/ui/media/MediaPage.kt) | Routes to image/video/audio players |
| [MediaPlayers.kt](app/src/main/kotlin/com/bothbubbles/ui/media/MediaPlayers.kt) | ZoomableImage, VideoPlayer, AudioPlayer |

### Current Capabilities
- ⚠️ Swipe between attachments (HorizontalPager) - **BROKEN: gesture conflict**
- ✅ Pinch-to-zoom and double-tap on images
- ✅ Video playback with ExoPlayer
- ✅ Audio playback with progress
- ✅ Basic share (ACTION_SEND intent)
- ✅ Page indicator ("X / Y")
- ✅ Auto-download on open

### Bugs Found
1. **Gesture Conflict** - `ZoomableImage` consumes all pan gestures, blocking HorizontalPager swipes
2. **Query Too Restrictive** - `getCachedMediaForChat` only returns downloaded attachments (`local_path IS NOT NULL`)

### Missing / Needs Enhancement
- ❌ Sender info in header (avatar, display name)
- ❌ Save to gallery/Downloads
- ❌ Video controls (speed, loop, scrubbing)
- ❌ Download progress UI in viewer
- ❌ Media info (size, date, dimensions)
- ❌ Copy image to clipboard

---

## Implementation Plan

### Phase 0: Bug Fixes (CRITICAL)

#### Bug Fix A: Gesture Conflict in ZoomableImage

**Problem:** `detectTransformGestures` consumes ALL pan gestures, even when not zoomed. HorizontalPager never receives swipe events.

**File:** [MediaPlayers.kt:48-60](app/src/main/kotlin/com/bothbubbles/ui/media/MediaPlayers.kt#L48-L60)

**Solution:** Only consume pan gestures when zoomed (`scale > 1f`):

```kotlin
@Composable
internal fun ZoomableImage(
    imageUrl: String,
    contentDescription: String,
    onTap: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Only handle zoom gestures, let pager handle horizontal swipes when not zoomed
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(0.5f, 5f)
                    scale = newScale

                    // Only consume pan when zoomed in
                    if (newScale > 1f) {
                        offset = Offset(
                            x = offset.x + pan.x,
                            y = offset.y + pan.y
                        )
                    } else {
                        offset = Offset.Zero
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            },
        // ...
    )
}
```

**Alternative (Better):** Use `transformable` with `consumePositionChange` control:
```kotlin
val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
    scale = (scale * zoomChange).coerceIn(0.5f, 5f)
    if (scale > 1f) {
        offset += panChange
    }
}

Modifier.transformable(
    state = transformableState,
    canPan = { scale > 1f }  // Only consume pan when zoomed
)
```

---

#### Bug Fix B: Query Too Restrictive

**Problem:** `getCachedMediaForChat` requires `local_path IS NOT NULL`, so only downloaded media appears in the swipeable list.

**File:** [AttachmentDao.kt:86-94](app/src/main/kotlin/com/bothbubbles/data/local/db/dao/AttachmentDao.kt#L86-L94)

**Solution:** Create new query that includes all media (cached or not):

```kotlin
// New query - all media for chat (not just downloaded)
@Query("""
    SELECT a.* FROM attachments a
    INNER JOIN messages m ON a.message_guid = m.guid
    WHERE m.chat_guid = :chatGuid
      AND (a.mime_type LIKE 'image/%' OR a.mime_type LIKE 'video/%')
      AND a.hide_attachment = 0
    ORDER BY m.date_created DESC
""")
suspend fun getAllMediaForChat(chatGuid: String): List<AttachmentEntity>
```

Then update `MediaViewerViewModel.loadMediaList()` to use this query and handle download state per-item.

---

### Phase 1: Sender Info Header

Show who sent the attachment in the top bar with their avatar.

**UI Design:**
```
┌──────────────────────────────────────────┐
│  ← [Avatar] John Appleseed               │  ← Enhanced top bar
│     Dec 15, 2024 • 2.4 MB                │     with sender info
├──────────────────────────────────────────┤
│                                          │
│              [Image/Video]               │
│                                          │
└──────────────────────────────────────────┘
```

**Data Requirements:**

Need to join attachment → message → handle to get sender info:

```kotlin
// New data class for media with sender info
data class MediaWithSender(
    @Embedded val attachment: AttachmentEntity,
    @ColumnInfo(name = "is_from_me") val isFromMe: Boolean,
    @ColumnInfo(name = "sender_address") val senderAddress: String?,
    @ColumnInfo(name = "date_created") val dateCreated: Long,
    // Handle info (nullable for "from me" messages)
    @ColumnInfo(name = "cached_display_name") val displayName: String?,
    @ColumnInfo(name = "cached_avatar_path") val avatarPath: String?,
    @ColumnInfo(name = "formatted_address") val formattedAddress: String?
)
```

**New DAO query:**
```kotlin
@Query("""
    SELECT
        a.*,
        m.is_from_me,
        m.sender_address,
        m.date_created,
        h.cached_display_name,
        h.cached_avatar_path,
        h.formatted_address
    FROM attachments a
    INNER JOIN messages m ON a.message_guid = m.guid
    LEFT JOIN handles h ON m.handle_id = h.id
    WHERE m.chat_guid = :chatGuid
      AND (a.mime_type LIKE 'image/%' OR a.mime_type LIKE 'video/%')
      AND a.hide_attachment = 0
    ORDER BY m.date_created DESC
""")
suspend fun getMediaWithSenderForChat(chatGuid: String): List<MediaWithSender>
```

**UI State update:**
```kotlin
data class MediaViewerUiState(
    // ... existing fields
    val senderName: String? = null,      // "John" or "You"
    val senderAvatarPath: String? = null,
    val senderAddress: String? = null,   // For avatar generation fallback
    val messageDateMillis: Long? = null
)
```

**Top bar enhancement:**
```kotlin
@Composable
private fun MediaViewerTopBar(
    senderName: String?,
    senderAvatarPath: String?,
    senderAddress: String?,
    dateMillis: Long?,
    fileSize: Long?,
    onBack: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Avatar
                ContactAvatar(
                    avatarPath = senderAvatarPath,
                    address = senderAddress,
                    displayName = senderName,
                    size = 36.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = senderName ?: "Unknown",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row {
                        dateMillis?.let {
                            Text(
                                text = formatDate(it),
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        fileSize?.let {
                            Text(
                                text = " • ${formatFileSize(it)}",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Black.copy(alpha = 0.5f)
        )
    )
}
```

---

### Phase 2: Bottom Action Bar

Replace single share button with a bottom action bar for better UX.

**Files to modify:**
- [MediaViewerScreen.kt](app/src/main/kotlin/com/bothbubbles/ui/media/MediaViewerScreen.kt)

**UI Design:**
```
┌──────────────────────────────────────────┐
│  ←  filename.jpg                         │  ← Top bar (existing)
├──────────────────────────────────────────┤
│                                          │
│                                          │
│              [Image/Video]               │  ← Media content (existing)
│                                          │
│                                          │
├──────────────────────────────────────────┤
│                 3 / 12                   │  ← Page indicator (existing)
├──────────────────────────────────────────┤
│  ⬇️ Save    📤 Share    ℹ️ Info          │  ← NEW bottom action bar
└──────────────────────────────────────────┘
```

**Implementation:**
```kotlin
// Bottom action bar composable
@Composable
private fun MediaActionBar(
    attachment: AttachmentEntity?,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionButton(Icons.Default.Download, "Save", onSave)
            ActionButton(Icons.Default.Share, "Share", onShare)
            ActionButton(Icons.Default.Info, "Info", onInfo)
        }
    }
}
```

---

### Phase 3: Save to Device

Add ability to save media to device gallery (images/videos) or Downloads folder (other files).

**Files to modify:**
- [MediaViewerViewModel.kt](app/src/main/kotlin/com/bothbubbles/ui/media/MediaViewerViewModel.kt) - Add `saveToDevice()` function
- [AttachmentRepository.kt](app/src/main/kotlin/com/bothbubbles/data/repository/AttachmentRepository.kt) - Add `saveToGallery()` function

**ViewModel addition:**
```kotlin
// MediaViewerViewModel.kt
sealed interface SaveResult {
    object Success : SaveResult
    data class Error(val message: String) : SaveResult
}

private val _saveResult = MutableStateFlow<SaveResult?>(null)
val saveResult = _saveResult.asStateFlow()

fun saveToDevice() {
    viewModelScope.launch {
        val attachment = _uiState.value.attachment ?: return@launch
        val localPath = attachment.localPath

        if (localPath == null) {
            _saveResult.value = SaveResult.Error("File not downloaded")
            return@launch
        }

        attachmentRepository.saveToGallery(localPath, attachment.mimeType)
            .fold(
                onSuccess = { _saveResult.value = SaveResult.Success },
                onFailure = { _saveResult.value = SaveResult.Error(it.message ?: "Save failed") }
            )
    }
}
```

**Repository addition:**
```kotlin
// AttachmentRepository.kt
suspend fun saveToGallery(localPath: String, mimeType: String?): Result<Uri> {
    return withContext(Dispatchers.IO) {
        try {
            val file = File(localPath)
            val isImage = mimeType?.startsWith("image") == true
            val isVideo = mimeType?.startsWith("video") == true

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isImage) Environment.DIRECTORY_PICTURES + "/BothBubbles"
                        else if (isVideo) Environment.DIRECTORY_MOVIES + "/BothBubbles"
                        else Environment.DIRECTORY_DOWNLOADS + "/BothBubbles"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val collection = when {
                isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }

            val uri = context.contentResolver.insert(collection, contentValues)
                ?: return@withContext Result.failure(Exception("Failed to create media entry"))

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                file.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }

            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

**Permission handling:**
- Android 10+ (Q): No permission needed (scoped storage)
- Android 9 and below: Request `WRITE_EXTERNAL_STORAGE`

---

### Phase 4: Enhanced Share Options

Create a share bottom sheet with multiple options.

**New file:** `ui/media/MediaShareSheet.kt`

**Options:**
1. **Share** - Standard Android share sheet
2. **Copy to Clipboard** - For images only
3. **Share to specific apps** - Quick access to common apps

```kotlin
@Composable
fun MediaShareSheet(
    attachment: AttachmentEntity,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onCopyToClipboard: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Share", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))

            ListItem(
                headlineContent = { Text("Share via...") },
                leadingContent = { Icon(Icons.Default.Share, null) },
                modifier = Modifier.clickable { onShare() }
            )

            if (attachment.isImage) {
                ListItem(
                    headlineContent = { Text("Copy to clipboard") },
                    leadingContent = { Icon(Icons.Default.ContentCopy, null) },
                    modifier = Modifier.clickable { onCopyToClipboard() }
                )
            }
        }
    }
}
```

---

### Phase 5: Media Info Dialog

Show metadata about the current attachment.

**New file:** `ui/media/MediaInfoDialog.kt`

```kotlin
@Composable
fun MediaInfoDialog(
    attachment: AttachmentEntity,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Media Info") },
        text = {
            Column {
                InfoRow("Filename", attachment.transferName ?: "Unknown")
                InfoRow("Type", attachment.mimeType ?: "Unknown")
                attachment.totalBytes?.let {
                    InfoRow("Size", formatFileSize(it))
                }
                if (attachment.width != null && attachment.height != null) {
                    InfoRow("Dimensions", "${attachment.width} × ${attachment.height}")
                }
                // Could add: date sent, sender, etc.
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}
```

---

### Phase 6: Enhanced Video Player

Add video-specific controls.

**Files to modify:**
- [MediaPlayers.kt](app/src/main/kotlin/com/bothbubbles/ui/media/MediaPlayers.kt)

**Enhancements:**
1. Playback speed control (0.5x, 1x, 1.5x, 2x)
2. Loop toggle
3. Seek bar with timestamps
4. Mute/unmute with volume
5. Fullscreen toggle (rotation hint)

```kotlin
@Composable
internal fun EnhancedVideoPlayer(
    videoUrl: String,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    var showControls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var isLooping by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
            prepare()
            playWhenReady = true
        }
    }

    // Auto-hide controls after 3 seconds
    LaunchedEffect(showControls) {
        if (showControls && isPlaying) {
            delay(3000)
            showControls = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { PlayerView(it).apply {
                player = exoPlayer
                useController = false // Custom controls
            }},
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            showControls = !showControls
                            onTap()
                        }
                    )
                }
        )

        // Custom controls overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            VideoControlsOverlay(
                isPlaying = isPlaying,
                currentPosition = currentPosition,
                duration = duration,
                playbackSpeed = playbackSpeed,
                isLooping = isLooping,
                isMuted = isMuted,
                onPlayPause = { /* toggle */ },
                onSeek = { exoPlayer.seekTo(it) },
                onSpeedChange = { speed ->
                    playbackSpeed = speed
                    exoPlayer.setPlaybackSpeed(speed)
                },
                onLoopToggle = {
                    isLooping = !isLooping
                    exoPlayer.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                },
                onMuteToggle = {
                    isMuted = !isMuted
                    exoPlayer.volume = if (isMuted) 0f else 1f
                }
            )
        }
    }
}
```

---

### Phase 7: Download Progress Indicator

Show download progress when attachment isn't cached.

**Files to modify:**
- [MediaViewerScreen.kt](app/src/main/kotlin/com/bothbubbles/ui/media/MediaViewerScreen.kt)
- [MediaPage.kt](app/src/main/kotlin/com/bothbubbles/ui/media/MediaPage.kt)

```kotlin
// In MediaPage.kt, add download state handling
@Composable
internal fun MediaPage(
    attachment: AttachmentEntity,
    downloadProgress: Float?,
    onTap: () -> Unit,
    onRetryDownload: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            downloadProgress != null && downloadProgress < 1f -> {
                // Show download progress
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        progress = { downloadProgress },
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "${(downloadProgress * 100).toInt()}%",
                        color = Color.White
                    )
                }
            }
            attachment.localPath == null && attachment.webUrl != null -> {
                // Not downloaded, show download button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onRetryDownload) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = "Download",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    Text("Tap to download", color = Color.White.copy(alpha = 0.7f))
                }
            }
            // ... existing media display logic
        }
    }
}
```

---

## File Structure

```
ui/media/
├── MediaViewerScreen.kt      # Main screen (modify)
├── MediaViewerViewModel.kt   # ViewModel (modify)
├── MediaPage.kt              # Media router (modify)
├── MediaPlayers.kt           # Players (modify)
├── MediaActionBar.kt         # NEW: Bottom action bar
├── MediaShareSheet.kt        # NEW: Share options
├── MediaInfoDialog.kt        # NEW: Info dialog
└── VideoControlsOverlay.kt   # NEW: Video controls
```

---

## State Changes

### MediaViewerUiState Additions
```kotlin
data class MediaViewerUiState(
    // Existing
    val isLoading: Boolean = true,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val mediaList: List<AttachmentEntity> = emptyList(),
    val currentIndex: Int = 0,
    val attachment: AttachmentEntity? = null,
    val localFile: File? = null,
    val title: String = "",
    val error: String? = null,

    // NEW
    val saveResult: SaveResult? = null,
    val showShareSheet: Boolean = false,
    val showInfoDialog: Boolean = false
)
```

---

## Implementation Order

### Priority 1: Bug Fixes (Must Do First)
1. **Phase 0A: Gesture Conflict** - Fix swipe not working
   - Modify `ZoomableImage` to only consume pan when zoomed
   - Test swipe navigation works

2. **Phase 0B: Query Too Restrictive** - Include all media
   - Add new DAO query `getAllMediaForChat` (or `getMediaWithSenderForChat`)
   - Update ViewModel to use new query

### Priority 2: Core Features
3. **Phase 1: Sender Info Header** - Show who sent attachment
   - Add `MediaWithSender` data class
   - Create enhanced top bar with avatar
   - Handle "You" for own messages

4. **Phase 2: Bottom Action Bar** - Visual foundation
   - Add action bar UI
   - Wire up button stubs

5. **Phase 3: Save to Device** - Core functionality
   - Add repository method
   - Add ViewModel method
   - Handle permissions
   - Show success/error toast

### Priority 3: Enhancements
6. **Phase 5: Media Info Dialog** - Quick win
   - Create dialog composable
   - Wire to info button

7. **Phase 4: Enhanced Share** - More share options
   - Create share sheet
   - Add copy to clipboard

8. **Phase 6: Video Controls** - Better video UX
   - Create controls overlay
   - Add speed/loop/mute

9. **Phase 7: Download Progress** - Polish
   - Add progress UI to MediaPage
   - Handle retry

---

## Considerations

### Compose Best Practices
- Use `ImmutableList` for media list
- Method references for callbacks (`viewModel::saveToDevice`)
- No logic in composition path
- Push state collection down where possible

### Error Handling
- Show snackbar for save success/failure
- Handle missing file gracefully
- Retry download on failure

### Testing
- Unit test `saveToGallery()` with mock ContentResolver
- UI test swipe navigation
- Test download progress updates
