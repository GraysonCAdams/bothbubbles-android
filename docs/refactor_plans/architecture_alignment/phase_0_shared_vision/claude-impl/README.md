# Phase 0: Implementation Guide

## Overview

Phase 0 is **documentation only** - no code changes. The goal is to establish Architecture Decision Records (ADRs) that guide all subsequent phases.

## What to Do

### 1. Review and Finalize ADRs

The ADRs already exist in this folder:
- `adr_0001_coordinator_vs_delegates.md`
- `adr_0002_no_global_event_bus.md`
- `adr_0003_ui_depends_on_interfaces.md`
- `adr_0004_delegate_lifecycle.md`
- `testing_strategy.md`

### 2. Verification Checklist

Before proceeding to Phase 2+3, confirm:

- [ ] ADR 0001: Team agrees ChatViewModel is an acceptable coordinator
- [ ] ADR 0002: No one plans to introduce a global event bus
- [ ] ADR 0003: Delegates should depend on interfaces (MessageSender, not MessageSendingService)
- [ ] ADR 0004: Pattern A (AssistedInject) is the default for Chat delegates
- [ ] Testing strategy: Minimum safety net defined (at least one test for send flow)

### 3. No Code Changes Required

Phase 0 is complete when ADRs are reviewed and agreed upon.

## Exit Criteria

- All ADRs have been read and understood
- Any disagreements have been resolved
- Ready to start Phase 2+3 combined work
