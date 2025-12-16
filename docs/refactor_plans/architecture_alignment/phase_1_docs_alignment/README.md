# Phase 1 — Documentation Alignment (Docs Match Code)

> **Implementation Plan**: See [impl/README.md](impl/README.md) for detailed tasks and templates.
>
> **Execution**: Runs **in parallel** with Phases 2-4. Update docs in the same PR as code changes.

## Layman's Explanation

Documentation is our shared map. If the map is wrong, every new contributor (including "future us") gets lost and makes mistakes.

This phase updates docs to describe the architecture we're building toward (per the [Shared Vision](../phase_0_shared_vision/README.md)) and sets rules for keeping docs from drifting.

## Connection to Shared Vision

Documentation must reflect these principles:

- **AssistedInject factories** — Document factory pattern, not `initialize()`
- **Interface dependencies** — Show `MessageSender`, not `MessageSendingService`
- **Coordinator orchestration** — Document ViewModel coordination patterns
- **Link to ADRs** — All architecture docs reference Phase 0 decisions

## Goals

- Remove doc/code mismatches that mislead refactors
- Make "source of truth" locations obvious
- Define a lightweight maintenance routine to prevent drift

## Scope

- Markdown/doc changes only
- No behavior changes
- **Non-blocking**: runs in parallel with Phases 2-4

## Primary Artifacts to Align

| Document | Update Needed |
|----------|---------------|
| `CLAUDE.md` | Show factory pattern, interface usage |
| `ui/chat/delegates/README.md` | Remove `initialize()` docs |
| `app/schemas/README.md` | Link to DB version, don't hardcode |
| `docs/COMPOSE_BEST_PRACTICES.md` | Add ADR references |

## Operations

1. **Fix mismatches** — Update delegate docs as code migrates
2. **Remove drift-prone constants** — Replace hardcoded values with links
3. **Add ADR references** — Link architectural docs to Phase 0 ADRs

## Key Rule

> **Update docs in the same PR as code changes.**

Do not treat documentation as a separate task that can be done "later."

## Exit Criteria

- [ ] Docs do not claim patterns that the codebase does not follow
- [ ] Each doc links to relevant ADR decisions
- [ ] "Drift-prone" constants removed from docs
- [ ] PR template includes documentation checkbox

## Risks

- Minimal. This phase does not impact runtime behavior.

## Next Steps

This phase is ongoing. Continue updating docs alongside Phase 2, 3, and 4 work.
