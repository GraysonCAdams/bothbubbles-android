# ADR 0004 — Delegate Lifecycle & Initialization Rules

**Status: ACCEPTED** (2024-12)

## Layman's explanation

A delegate shouldn’t be “half built.” If an object can exist but will crash unless you remembered to call `initialize()`, it’s easy for future changes to introduce bugs.

## Decision

- Avoid `lateinit` state required for correctness (e.g., `chatGuid`, `scope`) whenever practical.
- Prefer one of:
  1) Construct delegates with required runtime parameters via a factory/AssistedInject
  2) Pass required runtime parameters into methods instead of storing them

## Consequences

- Some delegate APIs will change.
- We’ll update call sites in `ChatViewModel` and related tests.
