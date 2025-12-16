# Architecture Alignment Plan (Claude + Gemini + Team)

This plan is a structured set of refactor phases to align the app’s real architecture with the concerns raised by external reviews (Claude/Gemini) and our own constraints.

## Layman’s summary (what/why)

Right now, the app is fast-moving and pragmatic: the Chat screen uses a “delegate” approach where one ViewModel wires together many helpers. That keeps the UI performant, but it creates a different kind of risk:

- Some objects exist in an “uninitialized” state until we manually call `initialize(...)`.
- Some UI code depends directly on concrete “service” implementations, making tests and refactors harder.
- The Chat screen has a lot of manual wiring between components (“delegate web”), which can become fragile.

These phases focus on making the architecture **safer and easier to evolve** without changing UX.

## Shared goals

- Keep UX and feature behavior unchanged.
- Reduce “temporal coupling” (`lateinit` + `initialize(...)` required ordering).
- Strengthen boundaries (UI depends on interfaces/contracts, not concrete implementations).
- Keep orchestration explicit and debuggable (avoid invisible global event buses unless necessary).
- Preserve Compose performance rules from [docs/COMPOSE_BEST_PRACTICES.md](../../COMPOSE_BEST_PRACTICES.md).

## How to use these plans

- Each phase has its own folder with:
  - A layman explanation at the top
  - Technical scope, operations, and definition of done
  - Parallelizable work items with dependency notes

## Phases

- Phase 0: Shared vision and decision records
- Phase 1: Documentation alignment (continuous; update docs as code changes)
- Phase 2: Dependency boundaries (UI → interfaces)
- Phase 3: Delegate lifecycle (remove `initialize()` footguns)
- Phase 4: Delegate coupling reduction (reduce cross-delegate references)
- Phase 5: Service-layer hygiene (framework vs pure logic)
- Phase 6 (optional): Modularization to enforce boundaries

## Recommended execution order (practical)

- Do Phase 0 first.
- For Chat: combine Phase 2 + Phase 3 where possible (avoid touching the same delegate twice).
- Then Phase 4.
- Treat Phase 1 as ongoing documentation updates during Phases 2–4 (not a blocking prerequisite).
- Phase 5 and Phase 6 are optional unless there is active pain (background regressions, repeated boundary breaks, build-time issues).

## Safety & testing expectations

This repo currently has minimal test coverage; plan to add a small number of high-value tests/fixtures as part of the refactor (especially around send/queue/reconcile flows).

Each phase should include a rollback-friendly strategy:
- migrate one delegate/workflow at a time
- keep changes mechanical
- avoid mixing behavior changes with architecture changes

## Cross-phase “guardrails”

- Don’t introduce a global event bus as a default solution.
- Prefer explicit dependencies and unidirectional data flow.
- Add tests/fixtures only where they directly support the refactor.
