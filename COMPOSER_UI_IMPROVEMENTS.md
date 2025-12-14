# Chat Composer UI Improvements Plan

This document outlines identified UX issues and proposed improvements for the chat composer component.

---

## User-Reported Issues

### 1. Camera Close Button Status Bar Overlap
**Priority:** High
**File:** `ui/camera/InAppCameraScreen.kt`
**Problem:** The close button (X) in the camera interface is positioned with only `16.dp` padding from top, causing it to overlap with the phone's status bar on devices with notches or cutouts.

**Current Code:**
```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)  // Only 16dp from top edge
        .align(Alignment.TopCenter),
    ...
)
```

**Fix:** Add `WindowInsets.statusBars` padding to ensure the button is below the status bar:
```kotlin
.padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp)
```

---

### 2. Media Picker Drag Handle Non-Functional
**Priority:** Medium
**File:** `ui/chat/composer/panels/MediaPickerPanel.kt`
**Problem:** The drag handle (32dp x 4dp pill at top of panel) is purely decorative. Users expect to be able to swipe down on it to dismiss the panel, following standard bottom sheet patterns.

**Current Code:** (Lines 193-201)
```kotlin
// Drag handle - just a visual Box, no gesture handling
Box(
    modifier = Modifier
        .align(Alignment.CenterHorizontally)
        .width(32.dp)
        .height(4.dp)
        .clip(RoundedCornerShape(2.dp))
        .background(...)
)
```

**Fix Options:**
- A) Add `Modifier.draggable()` with velocity threshold to dismiss
- B) Replace panel with `ModalBottomSheet` from Material 3 which has built-in swipe-to-dismiss
- C) Implement `AnchoredDraggable` for partial drag support

---

### 3. "+" Button Animation Not Working
**Priority:** High
**File:** `ui/chat/composer/components/ComposerActionButtons.kt` + `ChatComposer.kt`
**Problem:** The "+" button should rotate 45° to form an "X" and change to primary color when the media picker is expanded. The animation code exists but may not be triggered correctly.

**Analysis:** The animation code in `ComposerActionButtons.kt` is correct (lines 49-81). The issue is in `ChatComposer.kt` line 254:
```kotlin
ComposerActionButtons(
    isExpanded = state.isPickerExpanded,  // Need to verify this state is being set
    onClick = { onEvent(ComposerEvent.ToggleMediaPicker) }
)
```

**Investigation Needed:**
- Verify `ComposerState.isPickerExpanded` is updated when `activePanel` changes
- The state may be using `activePanel != ComposerPanel.None` check which needs validation

**Likely Fix:** Ensure `isPickerExpanded` is derived correctly:
```kotlin
val isPickerExpanded: Boolean
    get() = activePanel == ComposerPanel.MediaPicker || activePanel == ComposerPanel.EmojiKeyboard
```

---

### 4. Photo Icon Opens Media Browser Instead of Gallery
**Priority:** Medium
**File:** `ui/chat/composer/components/ComposerMediaButtons.kt` + `ChatComposer.kt`
**Problem:** The image/gallery icon in the text field opens the full media browser picker instead of going directly to the device's photo gallery.

**Current Flow:**
1. User taps gallery icon
2. `ComposerEvent.OpenGallery` is dispatched
3. This triggers the Android Photo Picker via `PickMultipleVisualMedia`

**Expected Behavior:** Tapping the gallery icon should open the photo picker directly (current behavior may be correct - need to clarify user expectation)

**Possible Fix:** If user wants the full media picker panel:
- Change `OpenGallery` event to `ToggleMediaPicker` for this button
- Or add a long-press to open full panel, tap for direct photo picker

---

### 5. Voice Memo No Cancel Option
**Priority:** High
**File:** `ui/chat/composer/ChatComposer.kt` + `ui/chat/components/` voice components
**Problem:** Once a user starts recording or enters preview mode, there's no clear way to cancel/discard and return to the regular text compose mode.

**Analysis:**
- Recording mode (`ExpandedRecordingPanel`) has: Restart, Stop, Attach - **NO CANCEL**
- Preview mode (`PreviewContent`) has: Play/Pause, Re-record - **NO CANCEL** in inline view

The `VoiceMemoPanel.kt` panel version DOES have cancel (line 424), but the inline components used in `ChatComposer.kt` don't expose this.

**Fix:** Add cancel/discard button to both:
1. `ExpandedRecordingPanel` - Add "Cancel" TextButton
2. `PreviewContent` - Add "Discard" TextButton with confirmation

Add new event:
```kotlin
data object CancelVoiceRecording : ComposerEvent
```

---

## Additional UX Issues Found

### 6. No Swipe-to-Cancel During Voice Recording
**Priority:** Medium
**Problem:** Google Messages and other apps allow swiping left/up during recording to cancel. This app uses tap-based controls only.

**Enhancement:** Add swipe gesture during `VOICE_RECORDING` mode:
- Swipe left = Cancel recording
- Visual feedback: Red "slide to cancel" indicator that appears during swipe

---

### 7. Camera Button Hidden Without Visual Cue
**Priority:** Low
**File:** `ui/chat/composer/components/ComposerMediaButtons.kt`
**Problem:** The camera button hides when user types text, but there's no visual indication of where it went. First-time users may be confused.

**Enhancement Options:**
- A) Show tooltip on first hide: "Camera moved to media picker"
- B) Fade out more slowly with slight leftward translation
- C) Keep camera visible but reduce opacity when text is present

---

### 8. Attachment Quality Button Discoverability
**Priority:** Low
**File:** `ui/chat/composer/components/AttachmentThumbnailRow.kt`
**Problem:** The quality selector button only appears when images are attached. Users may not discover this feature exists.

**Enhancement Options:**
- A) Add quality indicator badge on images showing compression level
- B) Show quality option in media picker panel settings
- C) Add onboarding tooltip first time images are attached

---

### 9. No Keyboard-to-Panel Height Sync
**Priority:** Medium
**File:** `ui/chat/composer/panels/` panel components
**Problem:** When switching from keyboard to emoji/media panel, there may be a jarring height change. The panel doesn't match keyboard height.

**Enhancement:** Track keyboard height with `WindowInsets.ime` and set panel `minHeight` to match for smoother transitions.

---

### 10. Smart Reply Positioning Unconventional
**Priority:** Low
**File:** `ui/chat/composer/components/SmartReplyRow.kt`
**Problem:** Smart replies use `reverseLayout = true` making them right-aligned. This is unconventional - most apps left-align suggestions.

**Code (line 47):**
```kotlin
LazyRow(
    ...
    reverseLayout = true,  // Causes right-alignment
)
```

**Fix:** Remove `reverseLayout = true` or add design justification comment.

---

### 11. Voice Memo Waveform Doesn't Animate During Playback
**Priority:** Low
**File:** `ui/chat/composer/panels/VoiceMemoPanel.kt`
**Problem:** During preview playback, the waveform is static. Bars are colored based on progress, but there's no "current position" indicator animation.

**Enhancement:** Add:
- Animated playhead line that moves across waveform
- OR pulse animation on the bar at current playback position

---

### 12. No Haptic Feedback on Media Selection
**Priority:** Low
**File:** `ui/chat/composer/panels/MediaPickerPanel.kt`
**Problem:** Tapping media options doesn't provide haptic feedback, unlike the send button gesture.

**Fix:** Add `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LightTap)` on option click.

---

### 13. Missing Send Mode Tutorial Activation
**Priority:** Medium
**File:** `ui/chat/composer/tutorial/`
**Problem:** Code includes `ComposerTutorialState` with tutorial steps, but the trigger logic to show first-time users the swipe-to-toggle feature isn't connected.

**Investigation:** Verify tutorial is shown when:
- User has both iMessage and SMS capability
- First time viewing send button with toggle available

---

### 14. Attachment Error State UX
**Priority:** Medium
**File:** `ui/chat/composer/components/AttachmentThumbnailRow.kt`
**Problem:** Failed uploads show red overlay with refresh icon, but:
- No error message is shown
- Unclear if tap retries or shows details
- No "remove failed" option

**Enhancement:**
- Show error reason on long-press
- Add explicit "Retry" and "Remove" buttons in error state

---

### 15. No Loading State for Photo Picker
**Priority:** Low
**Problem:** When `PickMultipleVisualMedia` is launched, there's no loading indicator while waiting for system picker.

**Enhancement:** Add brief loading indicator or scrim after tap.

---

## Implementation Priority Matrix

| Issue | Impact | Effort | Priority |
|-------|--------|--------|----------|
| #1 Camera status bar | High | Low | **P0** |
| #5 Voice memo cancel | High | Low | **P0** |
| #3 "+" button animation | Medium | Low | **P1** |
| #2 Drag handle | Medium | Medium | **P1** |
| #6 Swipe-to-cancel | Medium | Medium | **P2** |
| #4 Gallery vs picker | Low | Low | **P2** |
| #9 Panel height sync | Medium | High | **P2** |
| #13 Tutorial activation | Medium | Medium | **P2** |
| #14 Error state UX | Medium | Medium | **P2** |
| #7 Camera button hint | Low | Low | **P3** |
| #8 Quality discoverability | Low | Low | **P3** |
| #10 Smart reply alignment | Low | Low | **P3** |
| #11 Waveform animation | Low | Medium | **P3** |
| #12 Haptic feedback | Low | Low | **P3** |
| #15 Loading state | Low | Low | **P3** |

---

## Recommended Implementation Order

### Phase 1 - Critical Fixes
1. Fix camera close button status bar overlap (#1)
2. Add cancel button to voice memo recording/preview (#5)
3. Debug and fix "+" button animation trigger (#3)

### Phase 2 - Core UX Improvements
4. Implement drag-to-dismiss on media panel (#2)
5. Add swipe-to-cancel gesture for voice recording (#6)
6. Verify/fix tutorial trigger for send mode (#13)

### Phase 3 - Polish
7. Keyboard-panel height synchronization (#9)
8. Attachment error state improvements (#14)
9. Remaining P3 polish items

---

## Technical Notes

### State Management
The composer uses `ComposerState` data class with `ComposerEvent` sealed interface for event-driven updates. Any new interactions should follow this pattern:

```kotlin
// Add event
sealed interface ComposerEvent {
    data object CancelVoiceRecording : ComposerEvent
}

// Handle in ViewModel/delegate
when (event) {
    is ComposerEvent.CancelVoiceRecording -> {
        // Stop any recording
        // Clear recording state
        // Return to TEXT input mode
    }
}
```

### Animation System
Use tokens from `ComposerMotionTokens.kt`:
- Durations: `FAST` (100ms), `NORMAL` (200ms), `EMPHASIZED` (300ms)
- Springs: `Snappy`, `Responsive`, `Gentle`, `Bouncy`
- Scales: `Pressed` (0.88f), `EntranceInitial` (0.8f)

### Testing Considerations
- Test status bar fix on devices with notches, punch-holes, and dynamic island
- Test drag-to-dismiss with various swipe velocities
- Verify voice cancel doesn't lose unsent text

---

## Completed Fixes (Phase 1)

| Issue | Status | Commit Notes |
|-------|--------|--------------|
| #1 Camera status bar | ✅ Done | Added `WindowInsets.statusBars` padding |
| #5 Voice memo cancel | ✅ Done | Added `onCancel` to `ExpandedRecordingPanel` and `PreviewContent` |
| #3 "+" button animation | ✅ Done | Changed `isPickerExpanded` to computed property from `activePanel` |
| #2 Drag handle | ✅ Done | Added `draggable` modifier with 120dp threshold |
| #4 Gallery icon | ✅ Done | Now launches `PickMultipleVisualMedia` directly |

---

## Media Picker Feature Implementation Plan

### Current Status

| Option | Status | Details |
|--------|--------|---------|
| **Gallery** | ✅ Working | `PickMultipleVisualMedia` - fully functional |
| **Camera** | ✅ Working | `InAppCameraScreen` - fully functional |
| **GIF** | ⚠️ Partial | UI exists, no API integration |
| **Files** | ❌ TODO | Empty callback in ChatScreen.kt |
| **Location** | ❌ TODO | Empty callback in ChatScreen.kt |
| **Audio** | ✅ Working | Triggers voice recording |
| **Contact** | ❌ TODO | Empty callback in ChatScreen.kt |

---

### 1. Files Picker (High Priority)

**Technical Approach:**

**Contract:** Use `ActivityResultContracts.OpenMultipleDocuments`
```kotlin
val filePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenMultipleDocuments()
) { uris ->
    uris.forEach { uri ->
        // Process each file URI
    }
}

// Launch with all document types
filePicker.launch(arrayOf("*/*"))
```

**URI Handling:**
- Content URIs are references to files in other apps
- Must resolve display name and file size via `ContentResolver`:
```kotlin
context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
    cursor.moveToFirst()
    val name = cursor.getString(nameIndex)
    val size = cursor.getLong(sizeIndex)
}
```

**Stream Copying:**
- Copy content to app's cache directory for reliable access:
```kotlin
context.contentResolver.openInputStream(uri)?.use { input ->
    File(context.cacheDir, fileName).outputStream().use { output ->
        input.copyTo(output)
    }
}
```

**Integration Points:**
- `ChatScreen.kt` line 891-892: Wire up `onFileClick`
- Pass cached files to existing attachment pipeline via `viewModel.addAttachments()`

---

### 2. Contact Sharing (Medium Priority)

**Technical Approach:**

**Contract:** Use `ActivityResultContracts.PickContact`
```kotlin
val contactPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickContact()
) { uri ->
    uri?.let { extractContactData(it) }
}
```

**Data Extraction via Contacts Provider:**
```kotlin
fun extractContactData(contactUri: Uri): ContactData {
    val projection = arrayOf(
        ContactsContract.Contacts.DISPLAY_NAME,
        ContactsContract.Contacts._ID
    )
    // Query for name, then sub-query for phones/emails
}
```

**VCard Generation:**
- Leverage existing `VCardService` in `services/contacts/`
- Map extracted data to `ContactData` model
- Generate `.vcf` file:
```kotlin
val vcardService = VCardService()
val vcfContent = vcardService.generateVCard(contactData)
val vcfFile = File(context.cacheDir, "${contactData.displayName}.vcf")
vcfFile.writeText(vcfContent)
```

**Integration Points:**
- `ChatScreen.kt` line 897-898: Wire up `onContactClick`
- Treat `.vcf` as standard file attachment

---

### 3. GIF Picker (Medium Priority)

**Technical Approach:**

**State Management:**
Add to `ChatViewModel` or create dedicated `GifViewModel`:
```kotlin
private val _gifSearchQuery = MutableStateFlow("")
private val _gifResults = MutableStateFlow<List<GifItem>>(emptyList())
private val _gifLoadingState = MutableStateFlow<GifPickerState>(GifPickerState.Idle)
```

**API Integration (Tenor recommended - free tier):**
```kotlin
interface TenorApi {
    @GET("v2/search")
    suspend fun searchGifs(
        @Query("q") query: String,
        @Query("key") apiKey: String,
        @Query("limit") limit: Int = 20,
        @Query("pos") position: String? = null // pagination
    ): TenorSearchResponse
}
```

**Wiring in ComposerPanelHostSimple:**
```kotlin
ComposerPanelHost(
    // ...existing params...
    gifPickerState = viewModel.gifLoadingState.collectAsState().value,
    gifSearchQuery = viewModel.gifSearchQuery.collectAsState().value,
    onGifSearchQueryChange = { viewModel.updateGifQuery(it) },
    onGifSearch = { viewModel.searchGifs(it) },
    onGifSelected = { gif -> viewModel.downloadAndAttachGif(gif) },
)
```

**GIF Download Flow:**
```kotlin
suspend fun downloadAndAttachGif(gif: GifItem) {
    val tempFile = File(context.cacheDir, "${gif.id}.gif")
    URL(gif.fullUrl).openStream().use { input ->
        tempFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    addAttachment(tempFile.toUri())
}
```

---

### 4. Location Sharing (Low Priority / High Complexity)

**Technical Approach:**

**Permissions:**
```kotlin
val locationPermissions = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
) { permissions ->
    if (permissions.values.all { it }) {
        getCurrentLocation()
    }
}
```

**Location Retrieval:**
```kotlin
val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

fusedLocationClient.getCurrentLocation(
    Priority.PRIORITY_HIGH_ACCURACY,
    cancellationToken
).addOnSuccessListener { location ->
    // location.latitude, location.longitude
}
```

**iMessage-Compatible Format (.loc.vcf):**
```
BEGIN:VCARD
VERSION:3.0
PRODID:-//Apple Inc.//iPhone OS 17.0//EN
N:;Current Location;;;
FN:Current Location
item1.URL;type=pref:http://maps.apple.com/?ll=37.7749,-122.4194&q=Current%20Location
item1.X-ABLabel:map url
END:VCARD
```

**Implementation Phases:**
- **Phase 1 (MVP):** Simple "Share Current Location" confirmation dialog
- **Phase 2:** Map picker UI with draggable pin (Google Maps SDK or OSM)

---

## Implementation Checklist

### Files Picker
- [ ] Add `OpenMultipleDocuments` launcher in `ChatScreen.kt`
- [ ] Create `FileUtils.kt` for URI resolution and caching
- [ ] Wire `onFileClick` callback
- [ ] Test with PDF, DOCX, ZIP files

### Contact Sharing
- [ ] Add `PickContact` launcher in `ChatScreen.kt`
- [ ] Create contact data extraction helper
- [ ] Integrate with existing `VCardService`
- [ ] Wire `onContactClick` callback
- [ ] Test vCard generation and sending

### GIF Picker
- [ ] Add Tenor/Giphy API key to build config
- [ ] Create `GifRepository` with search/trending endpoints
- [ ] Add GIF state to `ChatViewModel`
- [ ] Wire `ComposerPanelHostSimple` callbacks
- [ ] Implement GIF download and caching
- [ ] Add pagination support

### Location Sharing
- [ ] Add location permissions to manifest
- [ ] Create permission request flow
- [ ] Implement `FusedLocationProviderClient` integration
- [ ] Create `.loc.vcf` generator
- [ ] Design confirmation dialog UI
- [ ] (Optional) Add map picker UI
