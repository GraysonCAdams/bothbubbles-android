# Refactor Plan: AttachmentContent - Video Component

**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentContent.kt`
**Goal:** Extract video rendering logic into a standalone Composable.

## Design Philosophy: Stateless UI Components

- **Statelessness:** The component should not hold business logic. It receives data (`AttachmentUiModel`) and callbacks (`onPlay`, `onClick`).
- **Single Responsibility:** This file handles _only_ Video rendering.
- **Standard Signature:** Use a consistent function signature for all attachment types.

## Instructions

### 1. Use Shared Types

Ensure `AttachmentCommon.kt` exists (created in Stage 1).

- Import `AttachmentInteractions`
- Import `LocalExoPlayerPool`

### 2. Create the Component

Create: `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/VideoAttachment.kt`

**Signature:**

```kotlin
@Composable
fun VideoAttachment(
    attachment: AttachmentUiModel,
    modifier: Modifier = Modifier, // Always 2nd parameter
    interactions: AttachmentInteractions,
    pool: ExoPlayerPool? = LocalExoPlayerPool.current
) {
    // Implementation
}
```

### 3. Extract Logic

1.  Locate the `AttachmentType.VIDEO` branch in `AttachmentContent.kt`.
2.  Copy the logic, including `AndroidView` (PlayerView), `ExoPlayer` setup, and overlay controls.
3.  Replace local variable references with `attachment` properties or `interactions` callbacks.

### 4. Update AttachmentContent

1.  In `AttachmentContent.kt`, replace the `AttachmentType.VIDEO` block.
2.  Construct the `AttachmentInteractions` object once and pass it to `VideoAttachment`.

## Verification

- **Visual:** Video player appears correctly with rounded corners/styling.
- **Behavior:** Play/Pause works.
- **Pooling:** Scrolling away pauses the video (via `DisposableEffect` or `ExoPlayerPool` logic).
