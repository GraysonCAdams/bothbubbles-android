# Phase 7 — Copilot Implementation Plan (Future Scope)

## Objectives
- Apply the architecture guardrails to remaining legacy areas (Conversations, Setup, app startup services).
- Keep a prioritized backlog so post-chat work does not stall once phases 1‑5 wrap.

## Target 1: `ConversationsViewModel`
1. **Decompose like Chat**
   - Introduce delegate/workflow structure using the AssistedInject factory pattern established earlier.
   - Port over interface boundaries so the ViewModel no longer references repositories or services directly.
2. **Trim initialization callbacks**
   - Replace `ConversationObserverDelegate.initialize(...callbacks...)` with explicit methods returning Flows/Results.
   - Add tests for paging + badge calculations using fakes.

## Target 2: `SetupViewModel`
1. **Adopt Hilt injection**
   - Move manual `Delegate(context)` constructions into DI modules.
   - Ensure configuration survives process death by storing state in delegates instead of the ViewModel constructor.
2. **Document onboarding dependencies**
   - Record any remaining singletons that still expect manual `initialize()` calls and schedule fixes.

## Target 3: Service bootstrapping
- Replace `BothBubblesApp.onCreate()` manual wiring with AndroidX Startup or lazy injection.
- Add startup metrics (systrace or Firebase) to ensure changes do not regress cold start.

## Process
- Keep a `future_scope_board.md` kanban (Todo / In Progress / Done) with owners.
- Require ADR/decision references for any new architectural choices.
- Treat each target as its own mini-phase with discovery, implementation, and documentation tasks.

## Definition of Done
- Conversations + Setup patterns match the Chat architecture (factories, interfaces, decoupled delegates).
- App startup no longer contains manual singleton initialization footguns.
- Future-scope backlog is empty or clearly prioritized with timelines.
