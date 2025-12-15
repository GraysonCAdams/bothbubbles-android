# Refactor Plan: ChatScreen Structural Components

**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatScreen.kt`
**Goal:** Extract high-level structural components to declutter the main screen file.

## Design Philosophy: Composition

- **Separation of Concerns:** The main screen should handle layout and state passing, not the implementation details of the TopBar or Dialogs.
- **Reusability:** Components like `ChatTopBar` could potentially be reused or tested in isolation.

## Instructions

### 1. Extract ChatTopBar

Create: `app/src/main/kotlin/com/bothbubbles/ui/chat/components/ChatTopBar.kt`

- Move the `TopAppBar` implementation here.
- Include the Back button, Avatar, Title, and Action buttons (Video/Call/Details).
- **Props:** `chatTitle`, `chatAvatar`, `onBackClick`, `onDetailsClick`, `onVideoClick`, `onAudioClick`.

### 2. Extract ChatDialogs

Create: `app/src/main/kotlin/com/bothbubbles/ui/chat/components/ChatDialogs.kt`

- Move the various `AlertDialog` definitions (e.g., "Delete Chat", "Share Location") into separate composables or a container composable.
- **Props:** `showDeleteDialog`, `onDismissDelete`, `onConfirmDelete`, etc.

### 3. Update ChatScreen

1.  Replace the inline `TopAppBar` with `ChatTopBar(...)`.
2.  Replace the inline dialog logic with calls to the extracted dialog components.

## Verification

- **Visual:** The Top Bar looks identical.
- **Behavior:** Buttons in the Top Bar work. Dialogs appear and dismiss correctly.
