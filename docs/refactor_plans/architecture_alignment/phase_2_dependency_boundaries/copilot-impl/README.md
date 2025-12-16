# Phase 2 — Copilot Implementation Plan (Dependency Boundaries)

## Objectives
- Ensure UI/delegate layers depend exclusively on stable contracts (interfaces or facades).
- Consolidate DI bindings so interface ↔ implementation mappings live in one module.
- Lay groundwork for mockable delegates before lifecycle refactors in Phase 3.

## Step-by-step
1. **Catalog concrete leaks**
   - Run `rg "SocketService" app/src/main/kotlin/com/bothbubbles/ui -n` and `rg "MessageSendingService" ...`.
   - Log each hit with owner + suggested interface in `dependency_boundary_audit.md`.
2. **Design or confirm interfaces**
   - Prefer existing contracts (`MessageSender`, `SocketConnection`).
   - When no interface exists, add one under `services/api/` with the minimum methods the UI actually needs.
3. **Refactor constructors during Phase 3 work**
   - When migrating a delegate to AssistedInject, swap constructor params to interfaces in the same commit.
   - Update Hilt modules once per feature (e.g., `ChatBindingsModule`) instead of per file.
4. **Introduce façade wrappers for singletons**
   - Example: wrap `ActiveConversationManager` with `ActiveConversationTracker` interface exposing only `setActiveConversation` + `clear`.
   - Keep wrappers in `com.bothbubbles.services.facade` so tests can provide fakes.
5. **Verification**
   - Add a lint script (`tools/no_concrete_services.sh`) that fails CI if UI imports forbidden classes.
   - Write at least one delegate unit test using a fake `MessageSender` to prove the abstraction works.

## Parallelization
- Interface authoring and DI binding updates can happen ahead of delegate migrations.
- Socket vs repository façades can be owned by different engineers since they touch separate files.

## Definition of Done
- No files under `app/src/main/kotlin/com/bothbubbles/ui/**` import `MessageSendingService`, `SocketService`, or other banned concretes.
- All new interfaces are documented (KDoc + link to ADR 0003).
- CI lint passes and at least one fake-backed test validates the new boundary.
