# Attachment UX Vision for BothBubbles

This document provides a comprehensive study of attachment handling UX in messaging apps and establishes a cohesive vision for BothBubbles' optimal attachment experience.

---

## Table of Contents

1. [Research Summary](#research-summary)
2. [Current State Analysis](#current-state-analysis)
3. [Signal Android Patterns](#signal-android-patterns)
4. [BlueBubbles Flutter (Legacy) Patterns](#bluebubbles-flutter-legacy-patterns)
5. [Pain Points & Opportunities](#pain-points--opportunities)
6. [Vision: Optimal Attachment Experience](#vision-optimal-attachment-experience)
7. [Implementation Priorities](#implementation-priorities)

---

## Research Summary

### Apps Studied

| App | Platform | Key Strengths | Key Weaknesses |
|-----|----------|---------------|----------------|
| **BothBubbles (Current)** | Kotlin/Compose | Priority queue, offline-first, blurhash placeholders | No quality selection, limited gallery features |
| **Signal Android** | Kotlin | Quality selection, privacy-first, reliable retry | Silent failures, aggressive compression, no thumbnail preview during download |
| **BlueBubbles Flutter** | Flutter | Rich picker UI, handwriting, comprehensive format support | Complex codebase, performance on low-end devices |

### Core User Needs Identified

1. **Speed** - Users want instant visual feedback, even before uploads complete
2. **Control** - Users want to choose quality vs. speed tradeoffs
3. **Reliability** - Failed transfers must be clearly communicated with obvious retry options
4. **Discoverability** - Attachment options should be easy to find but not overwhelming
5. **Continuity** - Transfers should survive app kills and network changes

---

## Current State Analysis

### BothBubbles Kotlin/Compose Architecture

#### Strengths

**1. Priority-Based Download Queue**
```
Priority Levels:
├── IMMEDIATE (0) - User tapped download
├── ACTIVE_CHAT (1) - Currently viewing chat
├── VISIBLE (2) - Message in scroll viewport
└── BACKGROUND (3) - Background sync
```
- Max 2 concurrent downloads (prevents network saturation)
- Dynamic re-prioritization when user switches chats
- StateFlow-based progress tracking

**2. Offline-First Design**
- PendingMessageEntity + PendingAttachmentEntity persisted to Room
- Files copied to app-internal storage immediately (survives URI permission revocation)
- WorkManager handles retry with exponential backoff
- Messages survive app termination

**3. Blurhash Placeholders**
- Server generates low-quality placeholder strings
- Decoded client-side for immediate visual preview
- Matches original aspect ratio

**4. HEIC Sticker Handling**
- Automatic HEIC → PNG conversion via HeifCoder
- Fallback chain: original → converted → JPEG
- Preserves transparency for stickers

**5. GIF Speed Fix**
- Detects and corrects zero-delay GIF frames
- Prevents animation playing too fast

#### Current Gaps

| Gap | Impact | Severity |
|-----|--------|----------|
| No quality selection before send | Users can't choose speed vs. quality | High |
| No attachment reordering | Can't arrange multi-select attachments | Medium |
| Limited compression options | Video compression not exposed to user | Medium |
| No caption support | Can't add text to images before send | Medium |
| No edit before send | Can't crop/rotate before sending | High |
| Basic gallery view | Missing grid customization, filtering | Low |

---

## Signal Android Patterns

### What Signal Does Well

**1. Quality Selection**
```
Standard Quality: 1201 × 1600 px, ~204KB (fast)
High Quality:     3075 × 4096 px, ~1.4MB (detailed)
Document Mode:    Original quality, 100MB limit (uncompressed)
```
- Per-message override via image icon tap
- Global preference in Settings > Data Usage
- Clear tradeoff communication

**2. Clear Error States**
- Red exclamation mark for failed messages
- "Not delivered" status text
- Tap-for-details pattern
- Dedicated resend button

**3. View-Once Media**
- Ephemeral media that disappears after viewing
- Privacy-focused feature

### What Signal Does Poorly

**1. Silent Failures**
- Attachments can fail without notification
- No clear indication of what went wrong
- Retry often fails immediately

**2. Aggressive Compression**
- Even "High" quality compresses significantly
- 2.2MB PNG → 209KB JPEG
- Users must use document workaround for true quality

**3. Missing Download Previews**
- Only shows download arrow during pending state
- No thumbnail or blur placeholder
- Theme-aware tinting is poor substitute

**4. No Multi-Select in Gallery**
- Feature request since Issue #5088
- Single-select only in built-in picker

### Key Learnings

> **Lesson 1:** Quality selection is expected by power users. Offer clear options with visible tradeoffs.

> **Lesson 2:** Never fail silently. Every failure needs visual feedback and a retry path.

> **Lesson 3:** Show something during downloads. Blank states feel broken.

---

## BlueBubbles Flutter (Legacy) Patterns

### Picker UI Excellence

**Horizontal Scrolling Multi-Grid Layout**
```
┌────────────────────────────────────────────────────────────┐
│ [Camera] [Video] │ [Files] [Location] │ [Recent Photos]    │
│                  │ [Schedule] [Draw]  │ [  ] [  ] [  ] ... │
│  (2-column)      │   (4-column)       │   (4-column)       │
└────────────────────────────────────────────────────────────┘
                    ← Horizontal Scroll →
```

- 300px height panel
- Action grids + recent gallery in one swipe
- Auto-detects recently taken photos (< 2 minutes)
- File size validation (> 1GB shows error)

**Attachment Options**
1. Photo capture (direct camera)
2. Video capture
3. File picker (multi-select, 1GB limit)
4. Location sharing (Apple vLocation format)
5. Scheduled message (date/time picker)
6. Handwritten message (canvas + color picker)

### Preview Experience

**Horizontal Attachment Strip**
```
┌─────────────────────────────────────────┐
│ [Thumb] [Thumb] [Thumb] [Thumb] ...     │
│   ✕       ✕       ✕       ✕             │
│ 2.1MB   1.4MB   320KB   4.5MB           │
└─────────────────────────────────────────┘
```

- Remove button per attachment
- File size displayed
- Video duration badge
- Open container animation to full preview

### Progress Visualization

**Circular Progress Pattern**
```
┌───────────────────┐
│     ○○○○○○○      │  ← Circular arc (0-100%)
│      45%         │  ← Percentage text
│   1.2MB / 2.6MB  │  ← Size progress
│    [Cancel]      │  ← Action button
└───────────────────┘
```

- Determinate progress when known
- "Waiting for iMessage..." after upload complete
- Cancel button during upload

### Media Viewer

**Full-Screen Experience**
- PageView swipe navigation
- "X of Y" counter
- Double-tap to zoom
- Pinch to zoom
- Download to gallery button
- Share button (native sheet)
- Metadata/EXIF dialog

**Video Player**
- Play/pause animation
- Mute toggle
- Fullscreen support
- Seek bar with time display

### Format Handling

**Automatic Conversions**
```
HEIC/HEIF → PNG (preserves transparency)
TIFF → PNG (via isolate for performance)
Videos → Thumbnail at 128px, 25% quality
```

**Quality Levels**
- Preview: 25% quality (thumbnails, gallery)
- Full: 100% quality (display, sharing)
- EXIF preservation enabled

### Special Features

| Feature | Description |
|---------|-------------|
| Live Photos | Badge indicator, special handling |
| Handwriting | Color wheel + canvas, PNG output |
| Stickers | Tap to fade (25% opacity), horizontal scroll |
| Contact Cards | vCard parsing with avatar |
| Audio Messages | Waveform visualization, transcript display |
| Location | Apple Maps link format |

### Settings Panel

**User Preferences**
- Auto-download toggle
- WiFi-only restriction
- Auto-save to gallery
- Custom save locations (media vs. documents)
- "Ask where to save" prompt option

---

## Pain Points & Opportunities

### Universal Pain Points (All Apps)

| Pain Point | Frequency | User Impact |
|------------|-----------|-------------|
| Silent upload/download failures | Common | Critical |
| No quality control before send | Very Common | High |
| Aggressive compression destroying quality | Very Common | High |
| Can't edit (crop/rotate) before send | Common | Medium |
| Can't reorder attachments | Occasional | Low |
| Unclear progress for large files | Common | Medium |
| Failed retry doesn't explain why | Common | High |

### BothBubbles-Specific Opportunities

**1. Quality Selection (High Priority)**
```
Current: No choice - server compresses arbitrarily
Optimal: Three-tier selection with clear tradeoffs
```

**2. Edit Before Send (High Priority)**
```
Current: Send as-is or use external app
Optimal: Built-in crop, rotate, markup tools
```

**3. Caption Support (Medium Priority)**
```
Current: Not supported
Optimal: Text overlay on images before send
```

**4. Attachment Reordering (Medium Priority)**
```
Current: Order fixed at selection time
Optimal: Drag-and-drop reorder in preview strip
```

**5. Gallery Enhancements (Low Priority)**
```
Current: Basic grid
Optimal: Filtering, date grouping, search
```

---

## Vision: Optimal Attachment Experience

### Design Principles

1. **Instant Feedback** - Show something immediately, refine progressively
2. **User Control** - Offer choices where tradeoffs matter
3. **Graceful Degradation** - Handle failures visibly with clear recovery paths
4. **Minimal Friction** - Common actions should be one tap away
5. **Progressive Disclosure** - Hide complexity until needed

### Attachment Picker Vision

#### Bottom Sheet Design (Phase 1)

```
┌──────────────────────────────────────────────────────────┐
│ ═══════════════════════════════════════════════════════  │  ← Drag handle
│                                                          │
│  [📷 Camera]  [🎥 Video]  [📁 Files]  [📍 Location]      │
│                                                          │
│  ───────────── Recent Photos ─────────────               │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐              │
│  │    │ │    │ │    │ │    │ │ ▶  │ │    │  ...         │
│  │    │ │    │ │ ✓  │ │    │ │    │ │    │              │
│  └────┘ └────┘ └────┘ └────┘ └────┘ └────┘              │
│                                                          │
│  ─────────────── More Options ───────────────            │
│  [⏰ Schedule]  [✍️ Draw]  [📇 Contact]  [🎁 GIF]         │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

**Key Features:**
- Half-screen default, drag to expand full-screen
- Recent photos grid (last 24 items)
- Multi-select with checkmark overlay
- Video duration badge on video thumbnails
- Quick access to common actions (top row)
- Secondary actions collapsible (bottom row)

#### Full Gallery Mode (Phase 2)

```
┌──────────────────────────────────────────────────────────┐
│  ← Back    Gallery    [All ▼]  [Albums ▼]    ✓ Done (3)  │
│ ─────────────────────────────────────────────────────────│
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐              │
│  │  1 │ │  2 │ │  3 │ │    │ │    │ │    │              │
│  └────┘ └────┘ └────┘ └────┘ └────┘ └────┘              │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐              │
│  │    │ │    │ │    │ │    │ │    │ │    │              │
│  └────┘ └────┘ └────┘ └────┘ └────┘ └────┘              │
│                        ...                               │
└──────────────────────────────────────────────────────────┘
```

**Key Features:**
- Album filtering dropdown
- Selection counter with numbers (shows order)
- Tap to select, long-press to preview
- "Done" button shows selection count

### Preview Strip Vision

```
┌──────────────────────────────────────────────────────────┐
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌───────┐       │
│  │         │  │         │  │    ▶    │  │  ✎    │       │
│  │  ✕   ✎  │  │  ✕   ✎  │  │  ✕   ✎  │  │       │  [+]  │
│  │  2.1MB  │  │  320KB  │  │  0:24   │  │ Add   │       │
│  └─────────┘  └─────────┘  └─────────┘  └───────┘       │
│       ↕            ↕            ↕                        │
│   (drag to     (drag to    (drag to                     │
│    reorder)    reorder)    reorder)                     │
└──────────────────────────────────────────────────────────┘
```

**Key Features:**
- **Remove button (✕)**: Top-right, removes from queue
- **Edit button (✎)**: Top-left, opens edit sheet
- **Size/Duration**: Bottom overlay
- **Drag handles**: Long-press enables reorder mode
- **Add button (+)**: Quick add more attachments
- **Horizontal scroll**: Swipe through attachments

### Edit Before Send Vision

```
┌──────────────────────────────────────────────────────────┐
│  ✕ Cancel                              Done ✓           │
│ ─────────────────────────────────────────────────────────│
│                                                          │
│                    ┌────────────────┐                    │
│                    │                │                    │
│                    │    [IMAGE]     │                    │
│                    │                │                    │
│                    └────────────────┘                    │
│                                                          │
│ ─────────────────────────────────────────────────────────│
│  [⟲ Rotate]  [⊡ Crop]  [✎ Draw]  [T Text]  [😊 Sticker] │
│                                                          │
│ ─────────────────────────────────────────────────────────│
│  Caption: [Add a caption...]                             │
└──────────────────────────────────────────────────────────┘
```

**Edit Tools:**
| Tool | Function |
|------|----------|
| Rotate | 90° increments + free rotation |
| Crop | Freeform + aspect ratio presets (1:1, 4:3, 16:9) |
| Draw | Brush with color picker, eraser |
| Text | Add text overlay with font/color options |
| Sticker | Emoji and sticker overlay |

**Caption:**
- Single line text field at bottom
- Persists with attachment through send
- Displayed below image in message bubble

### Quality Selection Vision

```
┌──────────────────────────────────────────────────────────┐
│  Send Quality                                    [✓]     │
│ ─────────────────────────────────────────────────────────│
│                                                          │
│  ○ Auto (Recommended)                                    │
│    Balances quality and speed based on network           │
│                                                          │
│  ○ Standard                                              │
│    Faster send, smaller files (~200KB per image)         │
│                                                          │
│  ● High Quality                                          │
│    Best quality, larger files (~1-2MB per image)         │
│                                                          │
│  ○ Original                                              │
│    No compression, sent as-is (large files)              │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

**Access Points:**
1. **Per-message**: Tap quality icon in composer before send
2. **Global default**: Settings > Messages > Send Quality
3. **Remember last used**: Optional preference

**Technical Specifications:**
| Level | Max Dimensions | Target Size | Format |
|-------|---------------|-------------|--------|
| Standard | 1600px | ~200KB | JPEG 70% |
| High | 3000px | ~1MB | JPEG 85% |
| Original | Unchanged | Unchanged | Original |

### Progress & Status Vision

#### Upload Progress

```
┌──────────────────────────────────────────────────────────┐
│  [Blur Preview]                                          │
│                                                          │
│              ╭──────────────╮                            │
│              │   ○○○○○      │                            │
│              │    67%       │                            │
│              │   2.1/3.2MB  │                            │
│              │  [Cancel]    │                            │
│              ╰──────────────╯                            │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

#### Download Progress

```
┌──────────────────────────────────────────────────────────┐
│  [Blurhash Placeholder - Aspect Ratio Matched]           │
│                                                          │
│              ╭──────────────╮                            │
│              │   ○○○○○      │                            │
│              │    45%       │                            │
│              │   1.2/2.6MB  │                            │
│              ╰──────────────╯                            │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

#### Error State with Retry

```
┌──────────────────────────────────────────────────────────┐
│  [Blurhash or Last Known Preview]                        │
│                                                          │
│              ╭──────────────╮                            │
│              │      ⚠       │                            │
│              │   Failed     │                            │
│              │  [↻ Retry]   │                            │
│              ╰──────────────╯                            │
│                                                          │
│  "Network error - tap to retry"                          │
└──────────────────────────────────────────────────────────┘
```

**Error Messages:**
| Error Type | User Message |
|------------|--------------|
| Network timeout | "Connection timed out - tap to retry" |
| Server error | "Server unavailable - tap to retry" |
| File too large | "File exceeds size limit (100MB)" |
| Format unsupported | "This format isn't supported" |
| Storage full | "Not enough storage space" |

### Media Viewer Vision

#### Full-Screen Image

```
┌──────────────────────────────────────────────────────────┐
│  ← Back                            ⋮ More               │
│                                                          │
│                                                          │
│                     [FULL IMAGE]                         │
│                                                          │
│                     (pinch to zoom)                      │
│                     (swipe to navigate)                  │
│                                                          │
│                                                          │
│ ─────────────────────────────────────────────────────────│
│      ○ ○ ● ○ ○        3 of 12                           │
│ ─────────────────────────────────────────────────────────│
│  [↓ Save]    [↗ Share]    [ℹ Info]    [🗑 Delete]        │
└──────────────────────────────────────────────────────────┘
```

**Interactions:**
- **Tap**: Toggle UI overlay
- **Double-tap**: Zoom to fit / reset
- **Pinch**: Zoom in/out
- **Swipe horizontal**: Navigate to prev/next
- **Swipe down**: Close viewer

**Actions:**
- **Save**: Download to device gallery
- **Share**: Native share sheet
- **Info**: EXIF data, file size, dimensions
- **Delete**: Remove from conversation (with confirmation)

#### Video Player

```
┌──────────────────────────────────────────────────────────┐
│  ← Back                            ⋮ More               │
│                                                          │
│                     [VIDEO FRAME]                        │
│                                                          │
│                         advancement                       │
│                          ▶                               │
│                                                          │
│ ─────────────────────────────────────────────────────────│
│  0:24 ══════════●══════════════════════════════ 2:45    │
│ ─────────────────────────────────────────────────────────│
│  [🔇 Mute]   [⏪ -10s]   [▶ Play]   [⏩ +10s]   [⛶ Full] │
└──────────────────────────────────────────────────────────┘
```

### Gallery View Vision

#### Conversation Media Gallery

```
┌──────────────────────────────────────────────────────────┐
│  ← Chat Details    Media              [Filter ▼]        │
│ ─────────────────────────────────────────────────────────│
│                                                          │
│  December 2024                                           │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐                     │
│  │    │ │ ▶  │ │    │ │    │ │    │                     │
│  └────┘ └────┘ └────┘ └────┘ └────┘                     │
│                                                          │
│  November 2024                                           │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐              │
│  │    │ │    │ │    │ │ ▶  │ │    │ │    │              │
│  └────┘ └────┘ └────┘ └────┘ └────┘ └────┘              │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

**Filter Options:**
- All Media
- Photos Only
- Videos Only
- Files/Documents

**Date Grouping:**
- Monthly sections with sticky headers
- Sorted newest first (default) or oldest first

---

## Implementation Priorities

### Phase 1: Foundation (Critical)

| Feature | Effort | Impact | Status |
|---------|--------|--------|--------|
| Blurhash placeholders | Done | High | ✅ |
| Priority download queue | Done | High | ✅ |
| Offline-first upload | Done | High | ✅ |
| Progress indicators | Done | Medium | ✅ |
| HEIC conversion | Done | Medium | ✅ |
| Clear error states with retry | Medium | Critical | 🔲 |

### Phase 2: User Control (High Priority)

| Feature | Effort | Impact | Status |
|---------|--------|--------|--------|
| Quality selection (per-message) | Medium | High | 🔲 |
| Quality selection (global setting) | Low | Medium | 🔲 |
| Edit before send (crop/rotate) | High | High | 🔲 |
| Caption support | Medium | Medium | 🔲 |

### Phase 3: Polish (Medium Priority)

| Feature | Effort | Impact | Status |
|---------|--------|--------|--------|
| Attachment reordering | Medium | Low | 🔲 |
| Full gallery picker | High | Medium | 🔲 |
| Edit before send (draw/text) | High | Medium | 🔲 |
| Date-grouped gallery view | Medium | Low | 🔲 |

### Phase 4: Delight (Lower Priority)

| Feature | Effort | Impact | Status |
|---------|--------|--------|--------|
| GIF picker integration | Medium | Medium | 🔲 |
| Sticker support | Medium | Low | 🔲 |
| Handwriting/drawing | High | Low | 🔲 |
| View-once media | Medium | Low | 🔲 |

---

## Technical Considerations

### Compression Pipeline

```
User selects attachment
        │
        ▼
┌───────────────────┐
│ Quality Selection │
│ (Auto/Std/High/Orig)│
└─────────┬─────────┘
          │
          ▼
┌───────────────────┐
│ Format Detection  │
│ (HEIC? TIFF? etc) │
└─────────┬─────────┘
          │
          ▼
┌───────────────────┐     ┌───────────────────┐
│ Needs Conversion? │────▶│ Convert Format    │
│ (HEIC→PNG, etc)   │ Yes │ (preserve alpha)  │
└─────────┬─────────┘     └─────────┬─────────┘
          │ No                      │
          ▼                         ▼
┌───────────────────┐
│ Apply Compression │
│ (based on quality)│
└─────────┬─────────┘
          │
          ▼
┌───────────────────┐
│ Generate Thumbnail│
│ (300px, 80% JPEG) │
└─────────┬─────────┘
          │
          ▼
┌───────────────────┐
│ Persist to Storage│
│ + Enqueue Upload  │
└───────────────────┘
```

### State Machine

```
                    ┌─────────────┐
                    │   PENDING   │
                    │ (not started)│
                    └──────┬──────┘
                           │ Start download/upload
                           ▼
┌─────────────┐     ┌─────────────┐
│   FAILED    │◀────│ TRANSFERRING│
│  (error)    │     │ (in progress)│
└──────┬──────┘     └──────┬──────┘
       │                   │ Complete
       │ Retry             ▼
       │            ┌─────────────┐
       └───────────▶│  COMPLETED  │
                    │  (success)  │
                    └─────────────┘
```

### Caching Strategy

```
Cache Directory Structure:
├── thumbnails/
│   └── {attachment_guid}.jpg    (300px, preview quality)
├── attachments/
│   └── {attachment_guid}.{ext}  (full file, downloaded)
└── pending/
    └── {local_id}.{ext}         (awaiting upload)

Cache Eviction:
- Thumbnails: LRU, max 500MB
- Full attachments: User-configurable limit
- Pending: Cleared on successful send
```

---

## Success Metrics

### User Experience KPIs

| Metric | Target | Measurement |
|--------|--------|-------------|
| Time to visual feedback | < 100ms | Blurhash render time |
| Upload success rate | > 99% | Completed / Attempted |
| Download success rate | > 99% | Completed / Attempted |
| Retry success rate | > 95% | Success on 2nd attempt |
| User-initiated quality changes | Track | Per-message overrides |

### Technical KPIs

| Metric | Target | Measurement |
|--------|--------|-------------|
| Concurrent downloads | Max 2 | Semaphore limit |
| Memory per thumbnail | < 500KB | Bitmap allocation |
| Thumbnail generation time | < 200ms | End-to-end |
| Upload queue persistence | 100% | Survives app kill |

---

## Appendix: Research Sources

### BothBubbles (Current) Files Analyzed
- `AttachmentEntity.kt` - Data model
- `AttachmentRepository.kt` - Download/upload logic
- `AttachmentDownloadQueue.kt` - Priority queue
- `AttachmentPreloader.kt` - Preloading strategy
- `PendingMessageRepository.kt` - Offline queueing
- `ChatAttachmentDelegate.kt` - ViewModel delegation
- `AttachmentContent.kt` - UI rendering

### Signal Android Patterns
- GitHub Issues: #5088, #4027, #6234, #5463, #8612, #7595
- Signal Blog: Attachment bug disclosure, Blur tools
- Support Articles: Troubleshooting, Media viewing

### BlueBubbles Flutter Files Analyzed
- `text_field_attachment_picker.dart` - Picker UI
- `picked_attachment.dart` - Preview strip
- `attachment_holder.dart` - Progress/rendering
- `fullscreen_image.dart` - Viewer experience
- `attachments_service.dart` - Compression
- `downloads_service.dart` - Download queue
- `attachment_panel.dart` - Settings

---

*Document created: December 2024*
*Last updated: December 2024*
