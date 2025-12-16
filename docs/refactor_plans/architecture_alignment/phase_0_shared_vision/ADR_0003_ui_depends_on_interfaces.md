# ADR 0003 — UI Depends on Interfaces

## Layman’s explanation

If the UI talks directly to a concrete implementation, it becomes hard to swap implementations, test, or refactor. Interfaces let us replace the engine without rewriting the dashboard.

Example: a Chat send delegate should depend on a `MessageSender` contract, not a specific `MessageSendingService` implementation.

## Decision

- UI layer classes (Compose, ViewModels, Delegates) depend on contracts/interfaces when the dependency is business logic or orchestration.
- Concrete implementations stay in the service/data layers and are provided by DI.

## Consequences

- We may add small interfaces where they don’t exist.
- DI bindings must be maintained so production uses the same concrete implementations.
