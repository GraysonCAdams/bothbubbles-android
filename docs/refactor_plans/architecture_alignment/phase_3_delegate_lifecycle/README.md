# Phase 3 — Delegate Lifecycle (Remove `initialize()` Footguns)

## Layman’s explanation

Today, some delegates are created “empty” and only become safe after `initialize(chatGuid, scope)` is called. That’s like buying a car where the brakes only work if you remember to pull a hidden lever first.

This phase makes delegates *always safe to use* by construction, so future changes can’t accidentally forget initialization.

## Goals

- Eliminate `lateinit` state required for correctness in delegates.
- Make delegate APIs enforce correctness at compile time.
- Keep the performance benefits of state isolation.

## Two acceptable patterns

Pick one per delegate (mixing is fine if consistent):

### Pattern A — Assisted injection / factories

- Create delegate instances with runtime parameters (like `chatGuid`).
- No `initialize(...)` method needed.

This should be the **default** for Chat delegates.

### Pattern B — Pass runtime parameters per operation

- Delegate does not store `chatGuid` or `scope`.
- Each method takes them as parameters.

Use this only when Pattern A becomes awkward (example: the delegate is already stateless and only needs `chatGuid` for a single call).

## Operations

1. Inventory delegates using `initialize()` + `lateinit` state.
2. Choose Pattern A or B per delegate (document the choice).
3. Refactor callers (primarily `ChatViewModel`) to match.
4. Add minimal tests for “delegate can be used without init” where practical.

**Special case — Send mode timing:** When migrating `ChatConnectionDelegate` and `ChatSendModeManager`, ensure the send mode can be injected via the factory rather than set later; otherwise the UI risks showing the wrong delivery mode while delegates finish initializing.

## Testing note

This repo currently has minimal automated test coverage. For high-risk delegates (send, list, sync), plan to add at least one focused test or fake-driven harness per refactor slice.

## Parallelizable work items

- Work item A: Chat delegates (highest risk)
  - Convert `ChatSendDelegate`, `ChatMessageListDelegate`, `ChatConnectionDelegate`, etc.
- Work item B: Other screens’ delegates
  - Convert scope-only initialization patterns (e.g., conversations delegates).

## Candidate files

- Coordinator: [app/src/main/kotlin/com/bothbubbles/ui/chat/ChatViewModel.kt](../../../app/src/main/kotlin/com/bothbubbles/ui/chat/ChatViewModel.kt)
- Example delegate with `initialize`: [ChatSendDelegate](../../../app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatSendDelegate.kt)

## Definition of Done

- Delegates no longer crash because `initialize(...)` was not called.
- The new lifecycle is documented and matches ADR 0004.

## Risks

- High: touches many call sites.
- Requires careful sequencing with Phase 4 (coupling reduction).
