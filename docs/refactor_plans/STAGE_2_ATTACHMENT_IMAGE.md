# Refactor Plan: AttachmentContent - Image Component

**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentContent.kt`
**Goal:** Extract image and GIF rendering logic into a standalone Composable.

## Design Philosophy: Stateless UI Components

- **Statelessness:** The component receives data and emits events.
- **Single Responsibility:** Handles Images, GIFs, and Stickers (visual static/animated media).
- **Standard Signature:** Matches the pattern used in `VideoAttachment`.

## Instructions

### 1. Use Shared Types

Reuse the `AttachmentInteractions` data class from `AttachmentCommon.kt` (created in Stage 1).

### 2. Create the Component

Create: `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/ImageAttachment.kt`

**Signature:**

```kotlin
@Composable
fun ImageAttachment(
    attachment: AttachmentUiModel,
    modifier: Modifier = Modifier,
    interactions: AttachmentInteractions
) {
    // Implementation
}
```

### 3. Extract Logic

1.  Locate `AttachmentType.IMAGE` and `AttachmentType.GIF` branches in `AttachmentContent.kt`.
2.  Copy the `AsyncImage` (Coil) logic.
3.  Include the "Blurhash" placeholder logic.
4.  Include the "Download/Progress" overlay logic if it's part of the image container.

### 4. Update AttachmentContent

1.  In `AttachmentContent.kt`, replace the Image/GIF blocks with `ImageAttachment(...)`.

## Verification

- **Visual:** Images load with correct aspect ratio and corner radius.
- **Behavior:** Tapping the image triggers `interactions.onClick`.
- **Performance:** Ensure `rememberAsyncImagePainter` or `AsyncImage` is used efficiently.
