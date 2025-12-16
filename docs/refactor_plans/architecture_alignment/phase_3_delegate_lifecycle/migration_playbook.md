# Phase 3 Migration Playbook

## Layman’s steps

1. Pick one delegate.
2. Make it safe without special setup.
3. Update the one place that constructs/uses it.
4. Repeat.

## Important sequencing rule (Phase 3 vs Phase 4)

Phase 3 is a **lifecycle/injection** refactor. Phase 4 is a **coupling/control-flow** refactor.

- During Phase 3, it is intentional to **keep** cross-wiring methods like `setDelegates()` temporarily so behavior stays unchanged.
- Do **not** remove delegate-to-delegate coupling as part of Phase 3 unless it is a purely mechanical type narrowing (see below).

## Technical playbook

1. Find `lateinit` + `initialize(...)` usage.
2. Choose Pattern A (factory) or Pattern B (parameters).
3. Refactor the delegate:
   - Remove `lateinit` fields.
   - Remove or minimize `initialize`.
4. Refactor call sites in `ChatViewModel`.
5. Add a small regression test if feasible.

### Allowed “opportunistic” improvement (only if trivial)

If a delegate only uses a tiny slice of another delegate (example: only calls `scrollToBottom()`), it is allowed to narrow the coupling to a minimal interface **without changing behavior**.

Example intent:

- Before: `setDelegates(messageList: ChatMessageListDelegate, ...)`
- After:  `setDelegates(scroller: MessageListScroller, ...)` where `MessageListScroller` has 1–2 methods.

Rules:

- No new control-flow decisions.
- No timing/ordering changes.
- No new async subscriptions.
- If it’s not obviously mechanical, defer to Phase 4.

## Rollback strategy (keep this refactor safe)

- Prefer migrating **one delegate at a time**.
- Keep each PR mechanical: lifecycle changes only (no feature changes).
- If a change is risky, introduce a temporary compatibility layer at the call site rather than rewriting multiple delegates at once.
- Use fakes (e.g., a fake `MessageSender`) to validate behavior in isolation.

## Suggested order (Chat)

1. `ChatConnectionDelegate` (state sources)
2. `ChatInfoDelegate` / `ChatMessageListDelegate` (data flows)
3. `ChatSendDelegate` (highest surface area)
4. Remaining delegates

## Notes

- Avoid changing behavior while changing lifecycle.
- Keep the refactor mechanical (one delegate at a time).
