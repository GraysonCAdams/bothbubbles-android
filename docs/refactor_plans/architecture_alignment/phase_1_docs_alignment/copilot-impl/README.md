# Phase 1 — Copilot Implementation Plan (Docs Alignment)

## Objectives
- Keep written guidance synchronized with the delegate refactors landing in Phases 2‑4.
- Make architecture references discoverable (single click from docs → ADR/code).
- Bake doc updates into the PR flow so drift cannot reappear.

## Execution Flow
1. **Create a living checklist**
   - Add `docs_alignment_tracking.md` next to this file if it does not exist.
   - Columns: Doc, Owner, Trigger (what code change demands an update), Status.
2. **Wire docs to code**
   - Replace stale narratives with code pointers (`ui/chat/delegates/README.md` → actual factories, etc.).
   - For constants (DB version, feature flags) embed hyperlinks to the source file/line using GitHub permalinks.
3. **Bundle docs with code PRs**
   - Update the PR template to include "Docs updated?" with checkboxes referencing Phase 1.
   - Reject PRs touching delegates unless matching docs are included or a TODO is filed in the tracking sheet.
4. **Add verification bots**
   - Create a simple CI job that greps for forbidden phrases (e.g., `initialize(` inside delegate README once Phase 3 ships).
   - Fail the job if drift is detected so authors fix it immediately.
5. **Run a weekly doc sweep**
   - 15‑minute async review where owners skim diff of tracked docs vs main to ensure nothing regressed.

## Parallel Tracks
- Chat architecture doc updates can run alongside schema README cleanup.
- Root README + COMPOSE guide editing can be split by contributor (no shared files).

## Definition of Done
- Every doc called out in the Phase 1 README links to the matching ADR and current code.
- Tracking sheet shows no "Unknown" or "Stale" entries.
- CI guard or PR template prevents merging code without doc acknowledgement.
