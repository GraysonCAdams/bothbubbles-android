# Phase 5 — Copilot Implementation Plan (Service Layer Hygiene)

## Objectives
- Clarify boundaries between Android framework components and injectable "service" singletons.
- Prevent UI/business layers from leaking framework concerns (Context, lifecycle, broadcast receivers).
- Document ownership + lifecycle expectations for every long-lived service.

## Work Breakdown
1. **Inventory services + framework touchpoints**
   - Script: `./tools/list_framework_components.sh` to print all classes extending `Service`, `BroadcastReceiver`, `Worker`.
   - Mirror list for singleton services in `com.bothbubbles.services.*`.
2. **Assess layering risks**
   - For each entry, answer: Does it depend on Activity/Fragment? Does it hold a reference to `Context` beyond `Application`? Does it spawn unmanaged coroutines?
   - Record findings in `service_layer_audit.md` with severity + owner.
3. **Thin framework classes**
   - Move heavy logic from Android components into injected use-cases or managers.
   - Ensure entry points marshal work onto background dispatchers as needed, then immediately delegate.
4. **Harden singleton services**
   - Inject `Application` context explicitly.
   - Replace manual `initialize()` calls with `@Singleton @Inject` constructors or `AppStartup` initializers.
   - Add cancellation hooks for any long-running coroutine scopes.
5. **Observability & docs**
   - Document each service: purpose, lifecycle, owning team, key entry points.
   - Add a section to `docs/COMPOSE_BEST_PRACTICES.md` (or new Service guide) describing "Framework vs Service" expectations with links to ADRs.
6. **Validation**
   - Add tests (instrumented or Robolectric) for at least the socket/service boundary to ensure the thin wrapper delegates correctly.
   - Run LeakCanary (or baseline memory check) after changes to confirm no new leaks.

## Parallelization
- Socket lifecycle, SMS/MMS stack, and notification/services can be audited independently.
- Documentation updates can proceed once each area lands its fixes.

## Definition of Done
- Every framework component is under 150 LOC and delegates to injected logic.
- No singleton service keeps UI references or mutable Context leaks.
- Ownership doc exists and is linked from the Phase 5 README.
