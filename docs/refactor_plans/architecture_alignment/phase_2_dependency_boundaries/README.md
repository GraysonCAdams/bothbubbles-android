# Phase 2 — Dependency Boundaries (UI → Interfaces)

## Layman’s explanation

We want the UI to depend on “what something does,” not “how it’s implemented.”

That means the Chat UI should talk to a `MessageSender` contract, not a specific `MessageSendingService` class. It makes testing easier and prevents the UI from becoming glued to one internal implementation.

## Goals

- Replace UI dependencies on concrete service implementations with interfaces.
- Keep DI bindings in one place.
- Make testing and refactoring safer.

## Scope

- Refactor types and injection only (no feature behavior change).
- Start with Chat send flow; expand outward.

## Execution note (ordering)

For Chat delegates, prefer combining Phase 2 changes with Phase 3 lifecycle refactors.

Reason: if we move a delegate to AssistedInject/factory construction, we will already be editing its constructor. That is the best time to also switch its dependencies from concrete implementations to interfaces (avoids touching the same delegate twice).

## Core operations

1. Promote interfaces as the primary dependency
   - Example: delegates depend on `MessageSender` instead of `MessageSendingService`.
2. Ensure DI binds interface → implementation
   - Validate Hilt modules provide the correct implementation.
3. Remove “leakage” where UI pulls in DB/DAO/network types indirectly.

## Parallelizable work items

- Work item A: Chat send + operations delegates
  - Swap `MessageSendingService` usage to `MessageSender`.
- Work item B: Socket usage boundaries
  - Decide if UI should depend on `SocketService` or a narrower `SocketConnection`/`SocketEvents` interface.
- Work item C: Introduce narrow interfaces where needed
  - Keep interfaces minimal, targeted, and stable.
- Work item D: Singleton façade interfaces
  - Wrap `ActiveConversationManager`, `SocketService`, `SettingsDataStore`, etc. with feature-facing interfaces so UI/delegates no longer import framework-aware singletons directly.

## Candidate hot spots

- Chat send delegate: [app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatSendDelegate.kt](../../../app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatSendDelegate.kt)
- Chat operations delegate: [app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatOperationsDelegate.kt](../../../app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatOperationsDelegate.kt)
- Sender contract: [app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSender.kt](../../../app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSender.kt)
- Implementation: [app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendingService.kt](../../../app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendingService.kt)

## Definition of Done

- Chat UI layer has no direct dependency on `MessageSendingService`.
- Interfaces are documented and have stable ownership.
- DI modules compile and unit tests (where present) still pass.

## Risks

- Medium: refactor touches many call sites.
- Risk is controlled if changes are mechanical and covered by targeted tests.
