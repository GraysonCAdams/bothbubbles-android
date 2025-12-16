# Phase 0 — Shared Vision & Decisions

> **Status**: ✅ COMPLETE
> **Implementation Plan**: See [impl/README.md](impl/README.md) for detailed tasks, code examples, and checklists.

## Layman's Explanation

Before we change architecture, we need agreement on *what we're aiming for*. Otherwise refactors can accidentally "fix" one problem by creating another (for example, replacing direct wiring with a global event bus can make bugs harder to trace).

This phase creates a small set of written decisions (a checklist of rules) so multiple refactor tasks can run in parallel while staying consistent.

## The Shared Vision

Our architecture aims for:

1. **Delegates are "born ready"** — No `initialize()` footguns; use AssistedInject factories
2. **UI depends on interfaces** — `MessageSender`, not `MessageSendingService`
3. **Explicit coordination** — ChatViewModel orchestrates; no global event bus
4. **Single responsibility** — Delegates don't know about each other
5. **Testable by design** — Interfaces enable fake injection

## ADRs (Architecture Decision Records)

| ADR | Decision | Status |
|-----|----------|--------|
| [ADR 0001](ADR_0001_coordinator_vs_delegate.md) | ChatViewModel is coordinator; delegates stay focused | ✅ ACCEPTED |
| [ADR 0002](ADR_0002_no_global_event_bus.md) | No global event bus; prefer explicit Flows | ✅ ACCEPTED |
| [ADR 0003](ADR_0003_ui_depends_on_interfaces.md) | UI depends on interfaces, not concrete services | ✅ ACCEPTED |
| [ADR 0004](ADR_0004_delegate_lifecycle_rules.md) | Use AssistedInject; eliminate `initialize()` | ✅ ACCEPTED |

## Outcomes

- A single "north star" architecture statement
- ADRs that later phases can cite
- Safety net test before any refactoring
- PR template with architecture checklist

## Scope

- **No code changes** (except safety net test)
- Documentation and agreed-upon rules only

## Exit Criteria

- [x] All 4 ADRs reviewed and marked ACCEPTED
- [x] Safety net test exists (`ChatSendDelegateTest`)
- [x] PR template updated with architecture checklist
- [ ] Team signed off on decisions

## Next Steps

After Phase 0 is complete, proceed to **combined Phase 2+3** for efficiency (change interface dependencies and lifecycle together).
