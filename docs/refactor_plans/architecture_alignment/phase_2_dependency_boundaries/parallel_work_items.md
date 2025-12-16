# Phase 2 Parallel Work Items

## Shared vision

All work items must follow ADR 0003 (UI depends on interfaces) and avoid introducing a global event bus.

## Work item A — Messaging boundaries

- Replace `MessageSendingService` injection in UI with `MessageSender`.
- Ensure `MessageSender` is the only type exported to UI from messaging.

Primary targets:
- [ChatSendDelegate](../../../app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatSendDelegate.kt)
- [ChatOperationsDelegate](../../../app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatOperationsDelegate.kt)

## Work item B — Socket boundaries

- Decide whether UI should depend on:
  - `SocketService` directly, or
  - a narrower interface (`SocketEvents`, `ConnectionStateProvider`, etc.).
- If narrowing, introduce a small interface and bind it to `SocketService`.

Primary targets:
- [SocketService](../../../app/src/main/kotlin/com/bothbubbles/services/socket/SocketService.kt)
- Chat delegates referencing socket

## Work item C — Enforce via conventions

- Add a short doc section listing “allowed imports” for the UI layer.
- (Optional) Add a lightweight lint/CI check later (Phase 5/6) to catch regressions.
