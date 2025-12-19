# Reply & Quote UI/UX Improvement Plan

## Overview
This document outlines a plan to improve the user experience and visual design of quoted messages (replies) in both the composition phase (Chat Composer) and the viewing phase (Message Bubble).

## Goals
1.  **Visual Consistency**: Unify the design language between the composer preview and the rendered quote using a shared component.
2.  **Rich Context**: Display media thumbnails in quotes instead of generic text placeholders.
3.  **Better UX**: Improve readability, interaction, and discoverability.

## Proposed Changes

### Phase 1: Unified Design Component
Create a shared `ReplyMessageCard` composable to be used in both contexts.

**Design Specs:**
- **Container**: Rounded rectangle (Radius: 12dp).
- **Background**: `MaterialTheme.colorScheme.surfaceContainerHigh` (or similar subtle contrast).
- **Accent**: Vertical bar (3-4dp width) on the start side, colored by sender (Primary for others, BubbleColor for me).
- **Typography**:
    - Sender: `LabelMedium` (Bold/Medium).
    - Message: `BodySmall` (Regular), max 2 lines.

### Phase 2: Rich Media Previews
Enhance the quote to show what is actually being replied to.

**Data Layer Updates:**
- Update `ReplyPreviewData` to include:
    - `attachmentThumbnailPath: String?`
    - `attachmentMimeType: String?`
- Update `MessageMapper` (or equivalent) to populate these fields from the original message.

**UI Updates:**
- **Images/Videos**: Display a small, rounded square thumbnail (e.g., 40x40dp in composer, 48x48dp in chat) on the end side of the quote.
- **Audio**: Show a microphone icon or waveform visualization.
- **Files**: Show a file icon.
- **Stickers**: Show the sticker image (scaled down).

### Phase 3: Interaction Improvements
- **Composer**:
    - Tapping the quote preview scrolls the chat to the original message (if loaded).
    - "X" button remains for dismissing.
- **Viewing**:
    - **Tap**: Smooth scroll to original message in chat (if loaded) + brief highlight animation.
    - **Long Press**: Open Thread Overlay.
- **Discovery**:
    - Add "Reply" option to the Message Context Menu (Long-press menu).

### Phase 4: Advanced Features (Future)
- **Nested Quotes**: Visual indication for quotes within quotes.
- **Inline Expansion**: Chevron to expand long quoted text inline.
- **Accessibility**: Enhanced content descriptions for screen readers.

### Phase 5: Implementation Details

#### 1. Shared Component (`ReplyMessageCard`)
Create `app/src/main/kotlin/com/bothbubbles/ui/components/message/ReplyMessageCard.kt`.

```kotlin
@Composable
fun ReplyMessageCard(
    senderName: String,
    messageText: String?,
    isFromMe: Boolean,
    mediaUri: String? = null, // For thumbnail
    mediaType: String? = null,
    onClose: (() -> Unit)? = null, // Only for composer
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    // ... implementation
}
```

#### 2. Update `ReplyPreviewBar`
Refactor `ReplyPreviewBar.kt` to use `ReplyMessageCard`.

#### 3. Update `MessageBubble`
Refactor `ReplyQuoteIndicator` in `MessageBubble.kt` to use `ReplyMessageCard`.

## Timeline
- **Step 1**: Create `ReplyMessageCard` with basic text support.
- **Step 2**: Integrate into `ReplyPreviewBar` and `MessageBubble`.
- **Step 3**: Add media thumbnail support to `ReplyMessageCard`.
- **Step 4**: Implement "Tap to Scroll" interaction and Context Menu item.

