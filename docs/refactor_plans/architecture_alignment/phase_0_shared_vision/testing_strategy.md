# Testing & Safety Strategy (Cross-Phase)

## Layman’s explanation

Refactors are safest when you can prove you didn’t break the product. Since the current repo has minimal automated tests, we’ll use a pragmatic approach:

- Add a small number of high-value checks that protect the most fragile flows.
- Keep refactor PRs small and mechanical.
- Prefer fakes over full integration where possible.

## Current state (as of today)

- `app/src/test` and `app/src/androidTest` are mostly empty.
- A fake exists: [app/src/test/kotlin/com/bothbubbles/fakes/FakeMessageSender.kt](../../../app/src/test/kotlin/com/bothbubbles/fakes/FakeMessageSender.kt)

## Minimum safety net (recommended)

### 1) “Send flow doesn’t regress” harness (highest value)

Goal: verify that a send attempt produces the expected state transitions/outcomes.

Options (pick one):

- Unit-level: test a workflow/use-case with fakes (`FakeMessageSender`, fake repositories).
- ViewModel-level: instantiate `ChatViewModel` with fakes and validate:
  - optimistic insert happens
  - errors propagate
  - send mode selection doesn’t crash

### 2) “Delegate lifecycle” regression check

Goal: ensure a migrated delegate cannot crash due to missing initialization.

- After migrating a delegate to AssistedInject/factory, add a test that constructs it via the factory and calls a representative method.

### 3) Smoke checks for build + compose compiler reports

Goal: ensure we don’t degrade stability/performance tooling while refactoring.

- `./gradlew assembleDebug`
- Optional: compose compiler report generation if already used by the team.

## Refactor PR rules

- One PR should migrate 1–2 delegates max.
- No behavior changes mixed into lifecycle/boundary changes.
- Always keep a clear rollback path (revert PR cleanly).
