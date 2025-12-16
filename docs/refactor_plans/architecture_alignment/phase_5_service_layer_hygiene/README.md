# Phase 5 — Service Layer Hygiene (Framework vs Pure Logic)

## Layman’s explanation

Some code needs Android (permissions, background execution). Some code should be “pure” business logic (send message, parse socket events). If we mix these too much, testing becomes harder and background behavior becomes fragile.

This phase audits and tightens the boundary between:

- Android framework components (real `Service`, receivers, WorkManager workers)
- Injected singleton “services” (application/domain services)

## Goals

- Ensure singleton services don’t accidentally depend on UI or short-lived contexts.
- Ensure framework components stay thin and delegate to testable logic.
- Make naming/lifecycle expectations explicit.

## Operations

1. Inventory Android framework components (`android.app.Service`, receivers, workers).
2. Confirm they delegate to injected singletons/use-cases.
3. Audit singleton services for:
   - Use of non-application `Context`
   - Direct UI references
   - Unbounded scopes or uncancelled jobs
4. Document service ownership and responsibilities.

## Parallelizable work items

- Work item A: Socket lifecycle
  - Confirm separation between foreground service and `SocketService` singleton.
- Work item B: SMS/MMS sending
  - Ensure framework pieces are thin and logic is testable.

## Definition of Done

- Framework components are thin wrappers.
- Singleton services are safe (no leaking contexts, no UI coupling).
- Clear doc on what “Service” means in this repo.

## Risks

- Medium: can touch background execution behavior.
- Keep changes minimal and well-tested.
