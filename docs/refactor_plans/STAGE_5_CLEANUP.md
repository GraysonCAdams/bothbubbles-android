# Refactor Plan: Final Cleanup & Assembly

**Target Files:**

- `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatViewModel.kt`
- `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentContent.kt`

**Goal:** Perform the final polish to ensure the monolithic files are truly reduced and clean.

## Instructions

### 1. ChatViewModel Cleanup

- **Constructor:** Verify that the constructor is now significantly smaller. Remove any unused dependencies that were left behind during previous stages.
- **Imports:** Run "Optimize Imports" to remove all unused imports.
- **Structure:** Ensure the class body is primarily composed of Delegate initializations and simple pass-through properties.

### 2. AttachmentContent Assembly

- **Dispatcher:** Ensure `AttachmentContent.kt` is now just a clean `when` statement dispatching to `VideoAttachment`, `ImageAttachment`, etc.
- **Imports:** Remove `ExoPlayer`, `Coil`, and other heavy dependencies from this file (they should now be in the sub-components).

### 3. Documentation

- Update the class KDoc to reflect the new architecture.
- Add comments explaining where the logic has moved (e.g., "See ChatComposerDelegate for input logic").

## Verification

- **Build:** The app must compile and run.
- **Lines of Code:** Measure the final line count of `ChatViewModel.kt` (Target: < 1,000) and `AttachmentContent.kt` (Target: < 400).
