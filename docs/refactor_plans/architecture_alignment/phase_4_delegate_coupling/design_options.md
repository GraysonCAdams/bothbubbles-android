# Phase 4 Design Options

## Option 1 — Coordinator-only orchestration (recommended default)

- `ChatViewModel` calls into delegates explicitly.
- Delegates do not know about each other.

Pros:
- Very traceable
- Simple mental model

Cons:
- ViewModel stays “busy” (but that’s acceptable for a coordinator)

## Option 1.5 — Feature-scoped workflows / use-cases (recommended for complex flows)

- Extract multi-step coordination into a dedicated class (e.g., `SendMessageWorkflow`).
- `ChatViewModel` becomes the caller and state holder, not the place where every step is implemented.

Pros:
- Avoids a 2,000-line coordinator
- Keeps control flow explicit and testable
- Makes reuse across screens feasible

Cons:
- Adds more classes/files
- Requires careful API design (inputs/outputs)

## Option 2 — Localized flows (targeted)

- A delegate exposes output as `Flow` (e.g., `sendResults`).
- Another delegate may consume the `Flow` if it is a pure dependency (not a mutable reference).

Pros:
- Less direct orchestration

Cons:
- Ordering and lifecycle require care

## Option 3 — Global event bus (not default)

- Disallowed as default by ADR 0002.
- Only allowed with explicit lifecycle + ordering rules.
