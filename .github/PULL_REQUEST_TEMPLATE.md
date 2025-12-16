## Summary

<!-- Brief description of what this PR does -->

## Changes

<!-- List the main changes in this PR -->
-

## Test Plan

<!-- How was this tested? -->
- [ ] Manual testing
- [ ] Unit tests added/updated
- [ ] Existing tests pass

## Screenshots (if applicable)

<!-- Add screenshots for UI changes -->

---

## Architecture Compliance

Before merging, confirm adherence to our architectural decisions:

### ADR Compliance

- [ ] **No global event bus** — I have not introduced a global event bus for cross-component communication (ADR 0002)
- [ ] **UI depends on interfaces** — UI components (ViewModels, Delegates) depend on interfaces, not concrete implementations (ADR 0003)
- [ ] **Delegate lifecycle** — New delegates use AssistedInject factory pattern, not `initialize()` + `lateinit` (ADR 0004)
- [ ] **No new `lateinit var`** — Required state for delegates is injected at construction time (ADR 0004)

### Compose Guidelines

- [ ] State is collected at the lowest possible level (leaf nodes)
- [ ] Collections passed to Composables use `ImmutableList`/`ImmutableMap`
- [ ] No logging or I/O in composition path
- [ ] Method references used for callbacks where possible

### General

- [ ] Changes are focused — no unrelated refactoring mixed in
- [ ] Documentation updated if public APIs changed
- [ ] No secrets or credentials in code

---

_For ADR details, see [Architecture Decision Records](docs/refactor_plans/architecture_alignment/phase_0_shared_vision/)_
