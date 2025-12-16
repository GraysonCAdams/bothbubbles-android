# Phase 0 — Copilot Implementation Plan (Shared Vision)

## Objectives
- Capture the "north star" architecture statement in writing.
- Turn implicit rules (delegate usage, no event bus, UI→interface) into signed ADRs.
- Give downstream phases a single place to reference when tradeoffs come up.

## Approach
1. **Audit existing guidance**
   - Read current ADRs + phase READMEs and note gaps (e.g., delegate lifecycle nuance, global service usage).
   - Log gaps in `phase_0_shared_vision/decisions_backlog.md` (create if missing) grouped by topic.
2. **Draft ADRs using a shared template**
   - Use `phase_0_shared_vision/README.md` scope bullets as the outline for ADR sections.
   - Write in pairs: one person authors, another reviews for neutrality and connection to real code.
3. **Run async sign‑off**
   - Share ADR MR/PR links in #architecture channel with TL + feature owners tagged.
   - Require at least two approvals (feature + infra) before marking ADR FINAL.
4. **Back‑link everywhere**
   - Update downstream docs (phase READMEs, `docs/COMPOSE_BEST_PRACTICES.md`) to link to the finalized ADR IDs instead of restating rules.
5. **Create a "won't do" appendix**
   - Document rejected options (global event bus, hidden singletons) so future PRs can reference the decision record instead of relitigating.

## Parallelizable Work
- Draft ADR skeletons while reviewers audit docs (no ordering dependency).
- Run terminology alignment (naming of "delegate", "workflow") separately from lifecycle decisions.

## Tooling / Automation
- Add a lightweight ADR lint (pre-commit) that checks for required front‑matter fields.
- Use GitHub issue templates for "Propose ADR" to capture context before writing full markdown.

## Definition of Done
- All four ADRs listed in Phase 0 README exist under `phase_0_shared_vision` with FINAL status.
- Later phases cite ADR IDs instead of duplicating rationale.
- Open questions (if any) live in the backlog doc with an owner + due date.
