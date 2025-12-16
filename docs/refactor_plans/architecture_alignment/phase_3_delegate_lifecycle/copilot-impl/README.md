# Phase 3 — Copilot Implementation Plan (Delegate Lifecycle)

## Objectives
- Remove `initialize(...)` + `lateinit` hazards from every delegate.
- Instantiate delegates with the runtime parameters they need (or pass params per call) so they are "born ready".
- Document lifecycle guarantees for future contributors.

## Migration Blueprint
1. **Choose lifecycle pattern per delegate**
   - Default to AssistedInject factories (`@Assisted chatGuid: String`, `@Assisted scope: CoroutineScope`).
   - Use stateless pattern only when the delegate truly has no retained state.
   - Record the choice in `delegate_lifecycle_matrix.md` to avoid re‑deciding later.
2. **Refactor constructors**
   - Move required fields from `initialize` arguments into constructor parameters.
   - Delete `lateinit` vars; replace with `val` injected dependencies.
   - Example diff target: `ChatSendDelegate`, `ChatMessageListDelegate`, `ChatConnectionDelegate`.
3. **Update `ChatViewModel` wiring**
   - Inject delegate factories instead of delegate singletons.
   - Create delegates lazily inside `init` or as `val send = sendFactory.create(chatGuid, viewModelScope)`.
4. **Add compile-time safeguards**
   - Introduce simple interface `ChatDelegate` with a `ChatDelegateFactory<T>` typealias to standardize signatures.
   - Write a lint (or ktlint rule) flagging any new `initialize(` definitions inside `ui/chat/delegates`.
5. **Regression harness**
   - For high-risk delegates, add unit tests that instantiate the delegate via the new factory and exercise one happy path (e.g., optimistic send, paging restore).
   - Use fake dependencies from Phase 2 to keep tests JVM‑only.

## Parallelization Strategy
- Group delegates by feature: `send`, `list`, `mode`, etc., allowing multiple engineers to convert them concurrently.
- Keep each PR focused on one delegate + ViewModel wiring to ease review.

## Definition of Done
- No delegate contains `lateinit` fields for required dependencies.
- `initialize()` is removed (or retained only for optional, idempotent setup with justification in code comments).
- Tests and docs reference AssistedInject/factory construction, aligned with ADR 0004.
