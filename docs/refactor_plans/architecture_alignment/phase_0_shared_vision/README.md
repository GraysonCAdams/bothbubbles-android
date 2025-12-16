# Phase 0 — Shared Vision & Decisions

## Layman’s explanation

Before we change architecture, we need agreement on *what we’re aiming for*. Otherwise refactors can accidentally “fix” one problem by creating another (for example, replacing direct wiring with a global event bus can make bugs harder to trace).

This phase creates a small set of written decisions (a checklist of rules) so multiple refactor tasks can run in parallel while staying consistent.

## Outcomes

- A single “north star” architecture statement.
- A few key decisions documented as ADRs (Architecture Decision Records).
- A map of what we will *not* do (to avoid scope creep).

## Scope

- No code changes.
- Only documentation and agreed-upon rules.

## Operations

1. Write ADR: “Delegates vs UseCases vs Coordinators”
2. Write ADR: “No Global Event Bus by Default”
3. Write ADR: “UI Depends on Interfaces”
4. Write ADR: “Lifecycle and Scope Rules for Delegates”

## Definition of Done

- ADRs exist and are referenced by later phases.
- Later plans can cite decisions instead of re-arguing tradeoffs.
