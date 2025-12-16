# Phase 6 (Optional) — Modularization to Enforce Boundaries

> **Implementation Plan**: See [impl/README.md](impl/README.md) for module structure and Gradle setup.
>
> **Status**: ✅ **COMPLETE** (2024-12-16) — `:core:model` extracted. Further modularization deferred.

## Layman's Explanation

Right now everything is in one Gradle module (`:app`). That's like having a house where every room connects directly to every other room — convenient, but it makes it easy to accidentally route plumbing through the bedroom.

Modules add doors and hallways: they create boundaries the compiler can enforce.

## Connection to Shared Vision

This phase provides **compiler enforcement** of the boundaries established in earlier phases:

- ADR 0003: UI cannot import concrete services
- Separation: data layer cannot import UI
- Testability: core modules can be tested in isolation

## When to Do This

**Only pursue if:**
- [ ] Build times are painful (>2-3 minutes incremental)
- [ ] Multiple contributors keep breaking architectural boundaries
- [ ] You want compiler-enforced "UI cannot touch DB directly"

**Skip if:**
- Solo developer with small codebase
- Build times are acceptable (<2 minutes)
- Phases 2-4 not yet complete

## Target Module Structure

```
project/
├── app/                      # Application shell
├── core/
│   ├── model/                # Data classes, no dependencies
│   ├── data/                 # Room, DAOs, Repositories
│   └── network/              # Retrofit, API interfaces
└── feature/                  # Optional feature modules
    ├── chat/
    └── conversations/
```

## Dependency Graph

```
        ┌─────────────────┐
        │      :app       │
        └────────┬────────┘
                 │
    ┌────────────┼────────────┐
    │            │            │
    ▼            ▼            ▼
┌────────┐  ┌────────┐  ┌──────────┐
│:core:  │  │:core:  │  │:core:    │
│ data   │  │network │  │ model    │
└────────┘  └────────┘  └──────────┘
```

## Exit Criteria

- [x] At least `:core:model` extracted and building
- [x] Build times improved (or at least not worse)
- [x] Architectural boundaries are compiler-enforced
- [x] All tests pass

## Risks

- High: Gradle + DI churn
- Not required to address core delegate lifecycle and interface boundary issues

## Recommendation

**For BothBubbles, defer modularization until Phases 2-4 are complete AND you're experiencing actual build time pain.**
