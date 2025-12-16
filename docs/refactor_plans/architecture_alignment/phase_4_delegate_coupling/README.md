# Phase 4 — Delegate Coupling Reduction (Tame the “Delegate Web”)

## Layman’s explanation

Having delegates is fine. The problem starts when delegates need to know about each other. That creates a spider web where small changes in one place break another.

This phase reduces cross-delegate references and keeps coordination explicit in one place.

## Goals

- Minimize delegate-to-delegate references.
- Keep `ChatViewModel` as an explicit coordinator (per ADR 0001) without letting it grow into a mega-class.
- Prefer passing immutable state/flows over passing delegate objects.
- When coupling removal would otherwise bloat the ViewModel, extract a feature-scoped workflow/use-case class.

## Operations

1. Inventory cross-wiring in `ChatViewModel` (e.g., `send.setDelegates(...)`).
2. Choose the smallest option that keeps control flow explicit:
  - Coordinator calls both delegates explicitly (simple cases).
  - Extract a workflow/use-case class when the coordination is multi-step or reused.
  - Delegates expose a narrow `Flow` of outputs (localized event stream) when it’s truly a data dependency.
3. Reduce bidirectional dependencies and remove stored cross-references.

## Parallelizable work items

- Work item A: Send flow coupling
  - Replace `send.setDelegates(...)` pattern.
- Work item B: Operations/search coupling
  - Remove `setMessageListDelegate(...)` patterns if possible.
- Work item C: Workflow extraction
  - Create feature-scoped workflows for multi-step coordination (send, sync, message reconciliation) so `ChatViewModel` doesn’t balloon.

## Definition of Done

- Delegates do not store references to each other (or do so only in exceptional, documented cases).
- Coordination points are explicit and easy to trace.

## Risks

- Medium: refactor impacts control flow and requires careful regression testing.
