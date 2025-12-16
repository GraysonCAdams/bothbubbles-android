# Phase 1 — Documentation Alignment (Docs Match Code)

## Layman’s explanation

Documentation is our shared map. If the map is wrong, you can still reach your destination, but every new contributor (including “future us”) gets lost and makes mistakes.

This phase updates docs so they describe the architecture we *actually* have today, and sets rules for keeping docs from drifting.

## Goals

- Remove doc/code mismatches that mislead refactors.
- Make “source of truth” locations obvious.
- Define a lightweight maintenance routine to prevent drift.

## Scope

- Markdown/doc changes only.
- No behavior changes.
- This phase is intentionally **non-blocking**: it can be done in parallel with Phases 2–4.

## Primary artifacts to align

- Root overview: [README.md](../../../README.md)
- Compose rules: [docs/COMPOSE_BEST_PRACTICES.md](../../COMPOSE_BEST_PRACTICES.md)
- Chat delegate architecture doc: [app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/README.md](../../../app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/README.md)
- Data migration doc: [app/schemas/README.md](../../../app/schemas/README.md)

## Operations

1. Identify and fix mismatches
   - Example known mismatch: docs recommending `MessageSender` while delegates depend on `MessageSendingService`.
2. Replace “hardcoded facts” that drift (like database version numbers) with links to code sources.
3. Add a small “Architecture Quicklinks” section to docs referencing the ADRs from Phase 0.

## Execution note (avoid “docs drift”)

- Prefer updating documentation *in the same PR* as the code changes that affect it.
- Use this phase as a checklist and staging area for doc work, not as a gate that must complete before coding starts.

## Parallelizable work items

- Work item A: Chat documentation accuracy
  - Update delegate docs to match the *chosen* direction (interface usage + lifecycle rules).
- Work item B: Schema/migrations doc drift
  - Remove hardcoded DB version from docs; point to the DB class.
- Work item C: Root README architecture summary
  - Ensure the “Services layer” definition matches reality (plain injected singletons vs Android `Service`).

## Definition of Done

- Docs do not claim patterns that the codebase does not follow.
- Each doc links to the relevant ADR decisions.
- “Drift-prone” constants are removed from docs.

## Risks

- Minimal. This phase should not impact runtime behavior.
