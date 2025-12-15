# Refactor Plan: Shared Types & CompositionLocals

**Target Files:**
- `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentCommon.kt` (New)
- `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentContent.kt` (Modify)

**Goal:** Extract shared interfaces and CompositionLocals to decouple the specific attachment renderers (Video, Image, etc.) and allow parallel development.

## Instructions

### 1. Create AttachmentCommon.kt
Create a new file: `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/AttachmentCommon.kt`

**Content:**
```kotlin
package com.bothbubbles.ui.components.attachment

import androidx.compose.runtime.staticCompositionLocalOf
import com.bothbubbles.services.media.ExoPlayerPool

/**
 * Shared interaction callbacks for all attachment types.
 * This prevents passing 10+ individual lambdas to every composable.
 */
data class AttachmentInteractions(
    val onMediaClick: (String) -> Unit,
    val onDownloadClick: ((String) -> Unit)? = null,
    val onRetryClick: ((String) -> Unit)? = null,
    // Add other common callbacks here as they are discovered
)

/**
 * CompositionLocal for providing ExoPlayerPool to video composables.
 */
val LocalExoPlayerPool = staticCompositionLocalOf<ExoPlayerPool?> { null }
```

### 2. Update AttachmentContent.kt
1.  Remove the `LocalExoPlayerPool` definition from `AttachmentContent.kt` (it is now in `AttachmentCommon.kt`).
2.  Add the import for `LocalExoPlayerPool` and `AttachmentInteractions`.
3.  (Optional) Create a helper to build the `AttachmentInteractions` object from the current parameters.

## Verification
- Ensure `AttachmentContent.kt` still compiles.
- Ensure `VideoAttachment` (Plan 2) and `ImageAttachment` (Plan 3) can import these shared types.
