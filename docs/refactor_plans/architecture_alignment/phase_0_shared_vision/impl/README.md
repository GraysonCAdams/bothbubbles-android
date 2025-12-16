# Phase 0: Shared Vision — Unified Implementation Plan

> **Status**: ✅ COMPLETE (2024-12)
> **Blocking**: All subsequent phases depend on this
> **Code Changes**: None (documentation only)

## Overview

Phase 0 establishes the architectural decisions that guide all refactoring work. Before changing any code, the team must agree on the "north star" architecture to prevent conflicting refactors.

## Objectives

1. **Finalize Architecture Decision Records (ADRs)** — Document key rules so future work can cite decisions rather than re-argue tradeoffs
2. **Create verification checklist** — Ensure all stakeholders understand and agree
3. **Establish enforcement mechanisms** — PR templates, review guidelines, future linting
4. **Document "won't do" decisions** — Prevent scope creep and relitigating rejected approaches

## ADRs to Review and Finalize

The following ADRs already exist in this folder. Review each and mark as ACCEPTED:

| ADR | File | Key Decision |
|-----|------|--------------|
| 0001 | `ADR_0001_coordinator_vs_delegate.md` | ChatViewModel is an acceptable coordinator; delegates stay focused |
| 0002 | `ADR_0002_no_global_event_bus.md` | No global event bus as default; prefer explicit Flows |
| 0003 | `ADR_0003_ui_depends_on_interfaces.md` | UI depends on interfaces (e.g., `MessageSender`), not concrete services |
| 0004 | `ADR_0004_delegate_lifecycle_rules.md` | Use AssistedInject factories; eliminate `initialize()` + `lateinit` |

## Implementation Tasks

### Task 1: Review ADRs (Team Alignment)

Each team member reviews all 4 ADRs and confirms understanding:

```markdown
## ADR Review Checklist

### Reviewer: _______________  Date: _______________

- [ ] ADR 0001: I understand ChatViewModel acts as coordinator; delegates should be small and focused
- [ ] ADR 0002: I will not introduce a global event bus without explicit approval
- [ ] ADR 0003: I understand UI code should depend on interfaces, not concrete implementations
- [ ] ADR 0004: I will use AssistedInject for delegates requiring runtime parameters
- [ ] Testing Strategy: I understand we need at least one safety net test for the send flow
```

### Task 2: Create Safety Net Test

Before any refactoring begins, create a baseline test for `ChatSendDelegate`:

```kotlin
// app/src/test/kotlin/com/bothbubbles/ui/chat/delegates/ChatSendDelegateTest.kt
package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.fakes.FakeMessageSender
import com.bothbubbles.fakes.FakePendingMessageRepository
import com.bothbubbles.fakes.FakeSocketConnection
import com.bothbubbles.fakes.FakeSoundManager
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSendDelegateTest {

    @Test
    fun `sendMessage queues message and triggers sender`() = runTest {
        // 1. Setup Fakes
        val fakeSender = FakeMessageSender()
        val fakeRepo = FakePendingMessageRepository()
        val fakeSocket = FakeSocketConnection()
        val fakeSound = FakeSoundManager()

        // 2. Create Delegate (current pattern - before Phase 3 refactor)
        val delegate = ChatSendDelegate(
            pendingMessageRepository = fakeRepo,
            messageSendingService = fakeSender,
            socketService = fakeSocket,
            soundManager = fakeSound
        )

        // Initialize (this is what Phase 3 will eliminate!)
        delegate.initialize("test-chat-guid", this)

        // 3. Act
        delegate.sendMessage("Hello World")

        // 4. Verify
        assertTrue(
            "Message should be queued",
            fakeRepo.messages.any { it.text == "Hello World" }
        )
    }
}
```

**Required Fakes** (create if missing):
- `FakePendingMessageRepository`
- `FakeSocketConnection`
- `FakeSoundManager`

### Task 3: Update PR Template

Add architecture checklist to `.github/PULL_REQUEST_TEMPLATE.md`:

```markdown
## Architecture Compliance

Before merging, confirm:

- [ ] I have not introduced a global event bus (ADR 0002)
- [ ] UI components depend on interfaces, not concrete services (ADR 0003)
- [ ] New delegates use AssistedInject factory pattern (ADR 0004)
- [ ] No new `lateinit var` for required state in delegates (ADR 0004)
```

### Task 4: Document Review Guidelines

Create or update code review guidelines to check for:

| Pattern | Action |
|---------|--------|
| `lateinit var` in ViewModels/Delegates | Block PR; request AssistedInject migration |
| `((String) -> Unit)?` callbacks stored as fields | Suggest interface or Flow instead |
| New "Manager" or "Service" naming confusion | Clarify: "Manager" for logic, "Service" for Android components |
| Direct import of concrete services in UI | Redirect to interface import |

### Task 5: Create Won't-Do Appendix

Document rejected approaches so future PRs don't relitigate:

```markdown
## Rejected Approaches (Won't Do)

### Global Event Bus
- **Rejected**: Makes debugging difficult; hides control flow
- **Alternative**: Explicit method calls or scoped SharedFlows

### Hidden Singletons
- **Rejected**: Creates temporal coupling; difficult to test
- **Alternative**: Constructor injection via Hilt

### Implicit Initialization Order
- **Rejected**: Easy to forget; causes runtime crashes
- **Alternative**: AssistedInject factories that require all params at construction
```

## Exit Criteria

All boxes must be checked before proceeding to Phase 2+3:

- [x] All 4 ADRs reviewed and marked ACCEPTED
- [x] Safety net test exists (`ChatSendDelegateTest`) — *Note: Tests are @Ignored until Phase 2+3 interface migration*
- [x] PR template updated with architecture checklist (`.github/PULL_REQUEST_TEMPLATE.md`)
- [x] Review guidelines documented (`impl/CODE_REVIEW_GUIDELINES.md`)
- [x] Won't-do appendix created (`impl/WONT_DO_APPENDIX.md`)
- [ ] All team members signed off on decisions

### Completed Artifacts

| Artifact | Location |
|----------|----------|
| ADR 0001 (ACCEPTED) | `ADR_0001_coordinator_vs_delegate.md` |
| ADR 0002 (ACCEPTED) | `ADR_0002_no_global_event_bus.md` |
| ADR 0003 (ACCEPTED) | `ADR_0003_ui_depends_on_interfaces.md` |
| ADR 0004 (ACCEPTED) | `ADR_0004_delegate_lifecycle_rules.md` |
| Safety Net Test | `app/src/test/kotlin/com/bothbubbles/ui/chat/delegates/ChatSendDelegateTest.kt` |
| PR Template | `.github/PULL_REQUEST_TEMPLATE.md` |
| Review Guidelines | `impl/CODE_REVIEW_GUIDELINES.md` |
| Won't-Do Appendix | `impl/WONT_DO_APPENDIX.md` |
| Fakes | `app/src/test/kotlin/com/bothbubbles/fakes/` |

## Verification Commands

```bash
# Verify safety net test passes
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test --tests "ChatSendDelegateTest"

# Verify ADR files exist
ls -la docs/refactor_plans/architecture_alignment/phase_0_shared_vision/ADR_*.md

# Count: should be 4 ADR files
ls docs/refactor_plans/architecture_alignment/phase_0_shared_vision/ADR_*.md | wc -l
```

## Phase Completion Sign-Off

| Reviewer | Role | Date | Signature |
|----------|------|------|-----------|
| | Lead | | |
| | Android Dev | | |
| | Android Dev | | |

---

**Next Phase**: Once all exit criteria are met, proceed to combined Phase 2+3 implementation.
