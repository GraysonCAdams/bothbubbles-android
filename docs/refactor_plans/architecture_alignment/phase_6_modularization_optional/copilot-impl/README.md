# Phase 6 — Copilot Implementation Plan (Optional Modularization)

## Objectives
- Introduce compiler-enforced boundaries without destabilizing daily development.
- Reduce build times and clarify ownership by carving out minimal shared modules.

## Guiding Principles
- Ship incremental slices: add one module at a time, keep wiring changes reviewable.
- Avoid circular dependencies by designing clear dependency flow (`:core:model` → `:core:data` → features → `:app`).
- Keep Hilt component graph simple by centralizing bindings in each new module.

## Execution Steps
1. **Spike build graph**
   - Create `build-logic/modularity/spike.md` capturing the proposed Gradle graph, including plugin + publishing requirements.
   - Validate with `./gradlew :app:dependencies` to ensure no hidden cycles.
2. **Extract `:core:model`**
   - Move shared data classes + sealed types (e.g., `MessageUiModel`, `ChatSendMode`).
   - Add API surface tests to confirm no Android deps leak into the module.
3. **Extract `:core:data`**
   - Relocate Room database, repositories, DataStore abstractions.
   - Provide Hilt modules exporting interfaces to consuming modules.
4. **Optional `:core:network` / feature modules**
   - Only after models/data are stable; migrate socket + retrofit code next.
   - Keep feature modules (e.g., `:feature:chat`) as thin shells referencing delegates + UI pieces.
5. **Tooling + CI**
   - Enable Gradle configuration caching and parallel execution once modules exist.
   - Add a CI job `./gradlew check -p core/model` to ensure leaf modules build in isolation.
6. **Documentation**
   - Update `docs/refactor_plans/.../phase_6_modularization_optional/README.md` with the finalized module diagram + ownership table.

## Parallelization
- Different engineers can own different module extractions (model vs data) as long as they agree on shared package names.
- Test + CI updates run concurrently with code moves.

## Definition of Done
- At least one non-app module builds independently and is consumed by `:app`.
- No feature code reaches across modules via `implementation project(:app)` shortcuts.
- Build/CI scripts updated and documented.
