# Refactor Plan: AttachmentContent - Contact Component

**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentContent.kt`
**Goal:** Extract VCard/Contact rendering logic into a standalone Composable.

## Design Philosophy: Stateless UI Components
- **Statelessness:** Receives data and interactions.
- **Consistency:** Uses `AttachmentInteractions` from Stage 1.

## Instructions

### 1. Create the Component
Create: `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/ContactAttachment.kt`

**Signature:**
```kotlin
@Composable
fun ContactAttachment(
    attachment: AttachmentUiModel,
    modifier: Modifier = Modifier,
    interactions: AttachmentInteractions
) {
    // Implementation
}
```

### 2. Extract Logic
1.  Locate the `AttachmentType.CONTACT` branch in `AttachmentContent.kt`.
2.  Copy the logic (Avatar, Name, "Add Contact" button).

### 3. Update AttachmentContent
1.  Replace the Contact block with `ContactAttachment(...)`.

## Verification
- **Visual:** Contact card displays correctly.
- **Behavior:** "Add Contact" or clicking the card works.
