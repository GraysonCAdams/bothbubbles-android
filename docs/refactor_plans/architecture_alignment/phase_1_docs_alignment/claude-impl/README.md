# Phase 1: Implementation Guide (Docs Alignment)

## Status: ONGOING / PARALLEL

This phase runs **in parallel** with Phases 2-4. Update docs as you change code, not as a prerequisite.

## What to Do

### 1. Update Docs When You Change Code

When you migrate a delegate to AssistedInject (Phase 3), update the corresponding README:

```markdown
// BEFORE (in delegates/README.md)
Delegates require `initialize(chatGuid, scope)` to be called before use.

// AFTER
Delegates are created via factory with runtime parameters.
No initialize() method - delegates are "born ready".
```

### 2. Remove Drift-Prone Constants

Don't hardcode values that change. Link to source instead.

```markdown
// BAD - will drift
The database is currently at version 47.

// GOOD - links to source
See `AppDatabase.kt` for current schema version.
```

### 3. Fix Known Mismatches

Update these files as the refactor progresses:

| File | What to Fix |
|------|-------------|
| `CLAUDE.md` | Update delegate usage examples to show factory pattern |
| `ui/chat/delegates/README.md` | Remove `initialize()` documentation |
| `ui/chat/README.md` | Update architecture diagram |

### 4. Example Doc Update

When ChatSendDelegate is migrated, update its documentation:

```markdown
// BEFORE
class ChatViewModel @Inject constructor(
    private val chatSendDelegate: ChatSendDelegate,
) : ViewModel() {
    init {
        chatSendDelegate.initialize(chatGuid, viewModelScope)
    }
}

// AFTER
class ChatViewModel @Inject constructor(
    private val sendDelegateFactory: ChatSendDelegate.Factory,
) : ViewModel() {
    private val send = sendDelegateFactory.create(chatGuid, viewModelScope)
}
```

## Files to Update (As Refactor Progresses)

- [ ] `CLAUDE.md` - Root instructions
- [ ] `app/src/main/kotlin/com/bothbubbles/ui/chat/README.md`
- [ ] `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/README.md`
- [ ] `docs/COMPOSE_BEST_PRACTICES.md` (if delegate patterns affect Compose)

## Exit Criteria

- Docs match code after each PR
- No documentation describes patterns that no longer exist
