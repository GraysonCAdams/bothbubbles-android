# Phase 6 (Optional) — Modularization to Enforce Boundaries

## Layman’s explanation

Right now everything is in one Gradle module (`:app`). That’s like having a house where every room connects directly to every other room — convenient, but it makes it easy to accidentally route plumbing through the bedroom.

Modules add doors and hallways: they create boundaries the compiler can enforce.

## When to do this

- Build times are becoming painful.
- Multiple contributors keep breaking boundaries.
- You want strong enforcement of “UI cannot touch DB/network directly.”

## Goals

- Create minimal modules that enforce boundaries without exploding complexity.

## Strategy (incremental)

1. Extract shared model types
   - `:core:model`
2. Extract data access
   - `:core:data` (Room, repositories, DataStore)
3. Extract networking
   - `:core:network`
4. Optional: feature modules
   - `:feature:chat`, `:feature:conversations`

## Parallelizable work items

- Work item A: Identify module seams and ownership
- Work item B: Move code with minimal API surface first (models)

## Definition of Done

- At least one boundary is compiler-enforced.
- Build remains maintainable.

## Risks

- High: Gradle + DI churn.
- Not required to address the core delegate lifecycle and interface boundary issues.
