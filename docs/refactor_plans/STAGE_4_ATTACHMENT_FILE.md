# Refactor Plan: AttachmentContent - File Component

**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentContent.kt`
**Goal:** Extract generic file (PDF, Zip, etc.) rendering logic into a standalone Composable.

## Design Philosophy: Stateless UI Components

- **Statelessness:** Receives data and interactions.
- **Consistency:** Uses `AttachmentInteractions` from Stage 1.

## Instructions

### 1. Create the Component

Create: `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/FileAttachment.kt`

**Signature:**

```kotlin
@Composable
fun FileAttachment(
    attachment: AttachmentUiModel,
    modifier: Modifier = Modifier,
    interactions: AttachmentInteractions
) {
    // Implementation
}
```

### 2. Extract Logic

1.  Locate the `AttachmentType.DOCUMENT` (or default/fallback) branch in `AttachmentContent.kt`.
2.  Copy the logic (usually an icon, filename, and file size).

### 3. Update AttachmentContent

1.  Replace the Document/File block with `FileAttachment(...)`.

## Verification

- **Visual:** File icon and name display correctly.
- **Behavior:** Clicking opens the file (or triggers download).
