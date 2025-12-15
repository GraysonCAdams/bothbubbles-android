# Refactor Plan: AttachmentContent - Audio Component

**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentContent.kt`
**Goal:** Extract audio rendering logic into a standalone Composable.

## Design Philosophy: Stateless UI Components
- **Statelessness:** Receives data and interactions.
- **Consistency:** Uses `AttachmentInteractions` from Stage 1.

## Instructions

### 1. Create the Component
Create: `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AudioAttachment.kt`

**Signature:**
```kotlin
@Composable
fun AudioAttachment(
    attachment: AttachmentUiModel,
    modifier: Modifier = Modifier,
    interactions: AttachmentInteractions
) {
    // Implementation
}
```

### 2. Extract Logic
1.  Locate the `AttachmentType.AUDIO` branch in `AttachmentContent.kt`.
2.  Copy the logic (likely involves a play button, progress bar, and duration text).
3.  Ensure it handles the "Me" vs "Other" styling if applicable.

### 3. Update AttachmentContent
1.  Replace the Audio block with `AudioAttachment(...)`.

## Verification
- **Visual:** Audio bubble looks correct.
- **Behavior:** Play/Pause works.
