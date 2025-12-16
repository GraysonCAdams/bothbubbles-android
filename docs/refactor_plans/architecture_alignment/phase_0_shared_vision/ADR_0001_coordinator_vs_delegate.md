# ADR 0001 — Coordinator vs Delegates vs Use-Cases

## Layman’s explanation

Think of the Chat feature like a restaurant kitchen. A “delegate” is a station (grill, salads, desserts). A “coordinator” is the expediter who calls out orders and ensures the stations work together. A “use-case” is a reusable recipe.

Right now, `ChatViewModel` behaves like the expediter and we have many delegate “stations.” That’s okay — but we want the stations to be safe to use and not depend on each other in fragile ways.

## Decision

- We accept `ChatViewModel` acting as an explicit coordinator.
- Delegates should be small, focused, and avoid holding cross-references to other delegates.
- We explicitly guard against a “mega coordinator” outcome: coordination should not grow unbounded inside the ViewModel.
- Business workflows (especially those spanning multiple delegates/services) should be extracted into **workflow/use-case classes** when they would otherwise make `ChatViewModel` balloon.

## Why

- Explicit coordination is easier to debug than implicit event-bus wiring.
- Delegates help Compose performance by isolating state.

## Consequences

- Some wiring remains in the ViewModel, but we’ll reduce it.
- We will not introduce a global event bus as the primary coupling mechanism.
- We may introduce a small number of feature-scoped workflow classes (e.g., `SendMessageWorkflow`, `SyncConversationWorkflow`) that:
	- depend on interfaces (not concrete implementations)
	- expose explicit inputs/outputs (return values or `Flow`)
	- keep ordering and control flow traceable
