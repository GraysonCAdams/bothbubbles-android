# Phase 4 — Copilot Implementation Plan (Delegate Coupling)

## Objectives
- Remove hidden delegate-to-delegate dependencies (e.g., `send.setDelegates(...)`).
- Keep coordination explicit inside `ChatViewModel` or a purpose-built workflow class.
- Make each delegate own a single responsibility with clear inputs/outputs (Flows, callbacks, or return values).

## Execution Steps
1. **Map current coupling**
   - Generate a quick diagram (Mermaid or Excalidraw) listing every `set*Delegate` or `delegateA.delegateB` usage.
   - Store the artifact as `delegate_coupling_map.md` for change tracking.
2. **Define coordination surfaces**
   - For each coupling, decide whether to:
     - (a) Have the ViewModel orchestrate both delegates sequentially.
     - (b) Extract a workflow (e.g., `SendMessageWorkflow`) injected into the VM.
     - (c) Convert the dependency into a Flow or callback parameter.
3. **Refactor send flow first**
   - Replace `ChatSendDelegate.setDelegates(...)` with a workflow that consumes factories for message list + composer operations, returning state updates via Flow.
   - Update tests to assert the workflow is invoked rather than cross-calling delegates.
4. **Unwind secondary couplings**
   - `ChatOperationsDelegate` and `ChatSearchDelegate` only need message lookups → pass `Flow<StableList<MessageUiModel>>` instead of the whole delegate.
   - Document any intentional coupling that remains (e.g., shared caches) with TODO + justification.
5. **Guardrails**
   - Add detekt rule or ktlint custom check that forbids storing other delegates as fields (allowed exceptions listed in config).
   - Update `app/src/main/kotlin/com/bothbubbles/ui/chat/README.md` to show new architecture diagram.

## Parallelization
- Handle each workflow independently (send, operations/search, sync).
- Documentation updates can trail by one PR but must finish before closing the phase.

## Definition of Done
- No delegate retains references to other delegates.
- Cross-component interactions are modeled as method calls, workflows, or Flows owned by the coordinator.
- The coupling map shows only intentional, documented dependencies.
