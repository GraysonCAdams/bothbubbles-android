# ChatViewModel Refactoring Roadmap

## Goal

Reduce `ChatViewModel.kt` from ~3,000 lines to < 1,000 lines by extracting distinct domains into specialized delegates.

## Current State

- **Lines:** ~3,016
- **Constructor Params:** ~26
- **Responsibilities:** Message loading, Composer state, Connection management, Scheduled messages, UI State coordination.

## Phase 1: The Composer Delegate (High Impact, Low Risk)

The composer logic (input field, attachments, drafts) is largely independent of the message stream.

### Responsibilities to Extract

- Draft management (`_draftText`)
- Pending attachment management (`_pendingAttachments`)
- Attachment quality settings (`_attachmentQuality`)
- Composer panel state (`_activePanel`)
- The complex `composerState` derivation logic (currently ~100 lines of `combine` and memoization).

### Dependencies to Move

- `PendingMessageRepository`
- `AttachmentLimitsProvider`
- `DraftRepository` (if applicable)
- `AttachmentPreloader`

### Estimated Reduction

- **Lines:** ~400-500
- **Constructor Params:** ~4

## Phase 2: Message List Delegate (High Impact, Medium Risk)

The logic for paging, loading, and updating the message list is complex and bulky.

### Responsibilities to Extract

- `MessagePagingController` management
- `RoomMessageDataSource` coordination
- `_messagesState` flow
- Handling `SocketEvent.Message` updates (insert/update/remove)
- Optimistic message insertion

### Dependencies to Move

- `MessageRepository`
- `ChatRepository` (partial)
- `SmartReplyService`

### Estimated Reduction

- **Lines:** ~600-800
- **Constructor Params:** ~3

## Phase 3: Connection & Availability Delegate (Medium Impact, High Risk)

Logic related to server connection, iMessage availability, and SMS fallback is currently scattered.

### Responsibilities to Extract

- `IMessageAvailabilityService` observation
- `SocketService` state observation
- Flip-flop detection (server stability tracking)
- SMS Fallback triggering logic
- `ChatSendMode` determination

### Dependencies to Move

- `SocketService`
- `IMessageAvailabilityService`
- `ChatFallbackTracker`
- `ActiveConversationManager`

### Estimated Reduction

- **Lines:** ~300-400
- **Constructor Params:** ~4

## Phase 4: Scheduled Messages Delegate (Low Impact, Low Risk)

Scheduled messages are a self-contained feature.

### Responsibilities to Extract

- Loading scheduled messages
- Creating/Updating/Deleting scheduled messages
- Listening for worker completion

### Dependencies to Move

- `ScheduledMessageRepository`
- `WorkManager`

### Estimated Reduction

- **Lines:** ~150-200
- **Constructor Params:** ~2

## Final Architecture Target

`ChatViewModel` becomes a thin coordinator that:

1.  Initializes delegates.
2.  Exposes delegate states to the UI.
3.  Routes UI events to the appropriate delegate.
4.  Handles navigation (which requires `Context` or `NavController`).

**Target Size:** ~800-1000 lines.
