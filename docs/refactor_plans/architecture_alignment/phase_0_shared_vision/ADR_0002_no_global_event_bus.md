# ADR 0002 — No Global Event Bus by Default

## Layman’s explanation

An event bus is like sending messages to “whoever is listening.” That can feel convenient, but it makes it hard to answer:

- Who reacts to this event?
- In what order?
- Why did this happen?

For a messaging app, those questions matter for reliability.

## Decision

- Do **not** introduce a global app-wide event bus as a default refactor tool.
- Allow small, localized event streams (e.g., a dedicated `Flow` for Chat send outcomes) when they improve separation without hiding control flow.

## Consequences

- Prefer explicit method calls or explicit `Flow` dependencies.
- If an event bus is proposed, it must include:
  - Subscription ownership rules
  - Ordering guarantees (or explicit lack of them)
  - Lifecycle disposal rules
