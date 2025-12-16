# Workflow / Use-Case Guidelines (Phase 4)

## Layman’s explanation

When we remove delegate-to-delegate calls, it’s tempting to move that logic into the ViewModel. That can make the ViewModel explode in size.

A workflow/use-case is a small “recipe” class that performs a multi-step operation (send, retry, reconcile) in a predictable way. The ViewModel triggers it; delegates and services provide inputs/outputs.

## When to create a workflow

Create a workflow if any of these are true:

- The coordination spans 2+ delegates *and* 2+ steps.
- The flow has branching behavior (success/failure, fallback modes, retries).
- The same flow is needed outside the Chat screen.
- Moving the logic into the ViewModel would add a lot of imperative glue.

## When NOT to create a workflow

- The “coordination” is trivial (one extra call).
- The work is purely UI rendering concerns.
- The flow is already encapsulated in a service/repository and UI just triggers it.

## Shape of a good workflow

- Depends on interfaces/contracts (per ADR 0003).
- Has explicit inputs and outputs:
  - `suspend fun run(input): Result<Output>` for one-shot flows, or
  - `fun results(): Flow<Output>` for ongoing streams.
- Does not hold references to UI classes, ViewModels, or Composables.
- Uses injected dispatchers/scopes where needed; avoids unbounded global jobs.

## If using localized flows (example shape)

If we choose a localized event stream instead of direct delegate references, it should be explicit and owned:

- Producer: the component that performs the work (delegate/workflow) emits outcomes.
- Consumer: the coordinator (`ChatViewModel`) collects outcomes in `viewModelScope`.
- Unsubscribe: collection is automatically cancelled when the ViewModel is cleared.

Example outcome type (illustrative only):

```kotlin
sealed interface SendOutcome {
  data class Queued(val tempGuid: String) : SendOutcome
  data class Sent(val messageGuid: String) : SendOutcome
  data class Failed(val tempGuid: String, val reason: String) : SendOutcome
}
```

Rules:

- Keep this stream feature-scoped (Chat only), not global.
- Prefer `SharedFlow` with a small buffer; avoid replay unless the UX requires it.
- Avoid “magic” listeners scattered across the app.

## Example candidates

- Send message + optimistic insert + error reconciliation
- Retry queue processing orchestration
- Socket reconnect + resync sequencing
