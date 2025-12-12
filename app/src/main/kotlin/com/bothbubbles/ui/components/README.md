# UI Components Directory Organization

This directory contains reusable UI components. Components should be organized into subdirectories by their purpose.

## Directory Structure

```
ui/components/
├── common/          # Generic UI components used across screens
│   ├── Avatar.kt
│   ├── ShimmerPlaceholder.kt
│   ├── EmptyStates.kt
│   ├── PullToRefresh.kt
│   ├── ConnectionStatusBanner.kt
│   └── AnimationUtils.kt
│
├── message/         # Message-related components
│   ├── MessageBubble.kt
│   ├── MessageSegment.kt
│   ├── MessageTransformations.kt
│   ├── MessagePlaceholder.kt
│   ├── MessageModels.kt
│   ├── LinkPreview.kt
│   ├── LinkPreviewCard.kt
│   └── ThreadOverlay.kt
│
├── attachment/      # Attachment display and picking
│   ├── AttachmentContent.kt
│   └── AttachmentPickerPanel.kt
│
├── dialogs/         # Modal dialogs and popups
│   ├── ForwardMessageDialog.kt
│   ├── SnoozeDurationDialog.kt
│   ├── VCardOptionsDialog.kt
│   ├── TapbackMenu.kt
│   ├── ContactQuickActionsPopup.kt
│   └── EmojiPickerPanel.kt
│
├── input/           # Input-related components
│   ├── SmartReplyChips.kt
│   └── QrCodeScanner.kt
│
└── conversation/    # Conversation list components
    ├── SwipeableConversationTile.kt
    ├── ChatIndicators.kt
    └── SpamSafetyBanner.kt
```

## Migration Guide

When moving a component to its proper subdirectory:

1. Move the file to the new location
2. Update the package declaration: `package com.bothbubbles.ui.components.{subdirectory}`
3. Search for imports: `grep -r "import.*{ClassName}" --include="*.kt"`
4. Update all imports to use the new package path
5. Run the build to verify no broken imports

Example:
```bash
# Move file
mv Avatar.kt common/

# Update package (at top of file)
# package com.bothbubbles.ui.components.common

# Find all files importing this component
grep -r "import.*Avatar" --include="*.kt"

# Update imports in each file
# import com.bothbubbles.ui.components.common.Avatar
```

## Current Status

Directories created, files pending migration. Components currently remain in root `ui/components/` for backwards compatibility.

To complete the migration:
1. Move files to their proper subdirectories
2. Update package declarations
3. Update imports across codebase
4. Run `./gradlew assembleDebug` to verify
