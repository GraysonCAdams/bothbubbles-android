# Phase 1: Documentation Alignment — Unified Implementation Plan

> **Status**: Ongoing (Parallel with Phases 2-4)
> **Blocking**: Nothing (runs concurrently)
> **Code Changes**: Documentation only

## Overview

Documentation must match the codebase. This phase ensures docs accurately describe the architecture and sets up processes to prevent drift. Unlike other phases, this runs **continuously** alongside code changes.

## Core Principle

> **Update docs in the same PR as code changes.**
>
> Do not treat documentation as a separate phase that can be done "later."

## Objectives

1. **Fix existing mismatches** — Docs claiming patterns that code doesn't follow
2. **Remove drift-prone constants** — Replace hardcoded values with links to source
3. **Add ADR references** — Link all architectural docs to Phase 0 ADRs
4. **Establish doc maintenance workflow** — PR templates, CI guards

## Implementation Tasks

### Task 1: Audit and Fix Known Mismatches

| Document | Issue | Fix |
|----------|-------|-----|
| `CLAUDE.md` | Shows `initialize()` pattern | Update to show AssistedInject factories |
| `ui/chat/delegates/README.md` | References `initialize()` | Document factory pattern |
| `app/schemas/README.md` | Hardcoded DB version number | Link to `BothBubblesDatabase.kt` |

**Example Fix — CLAUDE.md Delegate Section:**

```markdown
<!-- BEFORE -->
class ChatViewModel @Inject constructor(
    private val chatSendDelegate: ChatSendDelegate,
) : ViewModel() {
    init {
        chatSendDelegate.initialize(chatGuid, viewModelScope)
    }
}

<!-- AFTER -->
class ChatViewModel @Inject constructor(
    private val sendDelegateFactory: ChatSendDelegate.Factory,
) : ViewModel() {
    private val send = sendDelegateFactory.create(chatGuid, viewModelScope)
    // No initialize() needed - delegate is "born ready"
}
```

### Task 2: Remove Hardcoded Values

Replace values that change frequently with links to source:

```markdown
<!-- BEFORE - Will drift -->
The database is currently at version 47.

<!-- AFTER - Links to source -->
See `BothBubblesDatabase.kt` for the current schema version.
Database version is defined in the `@Database(version = ...)` annotation.
```

### Task 3: Add ADR References

Add "Architecture Standards" section to relevant docs:

```markdown
## Architecture Standards

This codebase follows specific Architecture Decision Records (ADRs).
Review these before refactoring or creating new components:

- [ADR 0001: Coordinator vs Delegates](../../refactor_plans/architecture_alignment/phase_0_shared_vision/ADR_0001_coordinator_vs_delegate.md)
- [ADR 0002: No Global Event Bus](../../refactor_plans/architecture_alignment/phase_0_shared_vision/ADR_0002_no_global_event_bus.md)
- [ADR 0003: UI Depends on Interfaces](../../refactor_plans/architecture_alignment/phase_0_shared_vision/ADR_0003_ui_depends_on_interfaces.md)
- [ADR 0004: Delegate Lifecycle Rules](../../refactor_plans/architecture_alignment/phase_0_shared_vision/ADR_0004_delegate_lifecycle_rules.md)
```

### Task 4: Create Doc Tracking Spreadsheet

Track which docs need updates during refactor:

```markdown
<!-- docs_alignment_tracking.md -->

# Documentation Alignment Tracking

| Document | Owner | Trigger | Status |
|----------|-------|---------|--------|
| `CLAUDE.md` | Team | Any delegate change | Pending |
| `ui/chat/README.md` | Chat owner | ChatViewModel changes | Pending |
| `ui/chat/delegates/README.md` | Chat owner | Delegate migrations | Pending |
| `docs/COMPOSE_BEST_PRACTICES.md` | Team | Architecture changes | Pending |
| `app/schemas/README.md` | DB owner | Migration changes | Complete |
```

### Task 5: Update PR Template for Docs

Add to `.github/PULL_REQUEST_TEMPLATE.md`:

```markdown
## Documentation

- [ ] I have updated relevant documentation for this change
- [ ] OR: No documentation changes needed (explain why)
- [ ] Links to ADRs added where architecture patterns are used
```

### Task 6: Create CI Guard (Optional)

Simple script to detect drift after Phase 3 ships:

```bash
#!/bin/bash
# tools/check_docs_drift.sh

# After Phase 3, this should find NO matches
MATCHES=$(grep -r "fun initialize(" docs/refactor_plans/architecture_alignment/ 2>/dev/null || true)
if [ -n "$MATCHES" ]; then
    echo "ERROR: Documentation still references initialize() pattern"
    echo "$MATCHES"
    exit 1
fi

# Check for hardcoded DB versions (should use relative links instead)
DB_HARDCODE=$(grep -rE "version [0-9]+" docs/ 2>/dev/null | grep -v "version control" || true)
if [ -n "$DB_HARDCODE" ]; then
    echo "WARNING: Possible hardcoded version numbers in docs"
    echo "$DB_HARDCODE"
fi

echo "Documentation drift check passed"
```

## Files to Update (Per Migration)

When migrating delegates in Phase 3, update these files **in the same PR**:

| When You Change | Update These Docs |
|-----------------|-------------------|
| Any Chat delegate | `ui/chat/delegates/README.md` |
| ChatViewModel | `ui/chat/README.md`, `CLAUDE.md` |
| Database schema | `app/schemas/README.md` |
| DI modules | `di/README.md` |
| Service interfaces | `services/README.md` |

## Doc Update Template

Use this template when updating docs after code changes:

```markdown
## [Component Name] Architecture

### Pattern
Uses AssistedInject factory pattern per [ADR 0004](link).

### Creation
```kotlin
// Factory injected via Hilt
private val delegate = factory.create(chatGuid, viewModelScope)
```

### Dependencies
- Depends on interfaces only (per [ADR 0003](link))
- `MessageSender` - not `MessageSendingService`
- `SocketConnection` - not `SocketService`

### Lifecycle
- No `initialize()` method
- All required state provided at construction
- Cancelled when parent scope (viewModelScope) is cancelled
```

## Exit Criteria (Ongoing)

This phase is never "complete" but tracks continuous progress:

- [ ] All known mismatches documented above are fixed
- [ ] No hardcoded DB version in docs
- [ ] ADR references added to main architecture docs
- [ ] PR template includes documentation checkbox
- [ ] Doc tracking spreadsheet created and maintained

## Verification Commands

```bash
# Check for stale initialize() references in docs (run after Phase 3)
grep -r "\.initialize(" docs/ app/src/main/kotlin/**/README.md

# Check for hardcoded version numbers
grep -rE "version [0-9]{2,}" docs/

# Verify ADR links are valid
for f in docs/refactor_plans/architecture_alignment/phase_0_shared_vision/ADR_*.md; do
    echo "Checking: $f"
    test -f "$f" && echo "  OK" || echo "  MISSING!"
done
```

## Integration with Other Phases

| Phase | Doc Action |
|-------|------------|
| Phase 2 (Interfaces) | Update docs showing interface usage |
| Phase 3 (Lifecycle) | Remove all `initialize()` from docs |
| Phase 4 (Coupling) | Document coordinator patterns |
| Phase 5 (Services) | Clarify "Service" naming |
| Phase 6 (Modules) | Update module diagrams |
| Phase 7 (Future) | Add new patterns to docs |

---

**Note**: This phase is intentionally lightweight and ongoing. The key is to **never merge code without corresponding doc updates**.
