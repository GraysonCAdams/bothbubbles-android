# Phase 11 — Architectural Completion & Technical Debt

> **Status**: Planned
> **Prerequisite**: Phases 0-6 complete (Phase 4 just completed)

## Layman's Explanation

Before adding new infrastructure (CI/CD, more modules, testing frameworks), we need to finish the architectural work started in Phases 0-10. Several phases are still "Planned" with incomplete items, and there's accumulated technical debt from TODO comments and anti-patterns discovered during development.

This phase consolidates and completes all remaining architectural work to establish a clean foundation.

## Connection to Shared Vision

This phase ensures we don't build new infrastructure on top of incomplete architecture. It completes the ADR implementations and removes known anti-patterns.

## Scope: What Was NOT Done

### From Phase 7 (Interface Extraction) — Still Planned

| Interface | Current State | Action |
|-----------|---------------|--------|
| `PendingMessageSource` | Not extracted | Extract interface from `PendingMessageRepository` |
| `VCardExporter` | Not extracted | Extract interface from `VCardService` |
| `ContactBlocker` | Not extracted | Extract interface from `ContactBlockingService` |
| Additional repository interfaces | Mixed | Audit and extract where testability requires |

### From Phase 8 (Conversations Architecture) — Still Planned

| Delegate | Issue | Action |
|----------|-------|--------|
| `ConversationLoadingDelegate` | `initialize()` + `lateinit` scope | Convert to AssistedInject factory |
| `ConversationActionsDelegate` | Manual wiring | AssistedInject + explicit APIs |
| `ConversationObserverDelegate` | Stores callbacks, `lateinit` | `SharedFlow` events, factory |
| `UnifiedGroupMappingDelegate` | `initialize()` dependency | Factory + lifecycle-safe caches |
| `ConversationsViewModel` | God object | Coordinate via factories |

### From Phase 9 (Setup Architecture) — Still Planned

| Delegate | Issue | Action |
|----------|-------|--------|
| `PermissionsDelegate` | Legacy patterns | Audit and align with ADRs |
| `ServerConnectionDelegate` | Manual initialization | AssistedInject if needed |
| `SmsSetupDelegate` | Callback-based | Modernize to StateFlow |

### From Phase 10 (Service Modernization) — Still Planned

| Area | Current State | Action |
|------|---------------|--------|
| `BothBubblesApp` startup | 40+ lines of init ordering | Move to AndroidX Startup |
| Coil/ImageLoader setup | Built inline | Create `CoilInitializer` |
| WorkManager config | `Configuration.Provider` | Custom `WorkManagerInitializer` |
| Foreground services | Need Android 14 audit | Update `foregroundServiceType` |

### Critical TODOs to Fix

| Location | Issue | Priority |
|----------|-------|----------|
| `ChatSendDelegate.kt:337` | Optimistic message not cleaned on DB failure | **High** |
| `ChatSendDelegate.kt:584` | Check attachments table for incomplete | Medium |
| `GifRepository.kt:29` | API key should be in secure storage | Medium |
| `ChatComposer.kt:91-93` | File picker, location, contact sharing not implemented | Low (feature) |

### Anti-patterns to Fix

| Pattern | Instances | Action |
|---------|-----------|--------|
| `collectAsState()` instead of `collectAsStateWithLifecycle()` | 14 | Migrate all |
| Deprecated `@Composable` in `ChatBanners.kt:322` | 1 | Remove and migrate callers |
| Large files (>800 LOC) needing decomposition | 2 | Extract sub-composables |

## Implementation Steps

### Step 1: Complete Phase 7 — Interface Extraction (4-6h)

1. Extract `PendingMessageSource` interface from `PendingMessageRepository`
2. Extract `VCardExporter` interface from `VCardService`
3. Extract `ContactBlocker` interface from `ContactBlockingService`
4. Add bindings to `ServiceModule.kt`

### Step 2: Complete Phase 8 — Conversations Architecture (20-30h)

1. Create `AssistedInject` factories for all 5 ConversationsViewModel delegates
2. Remove all `initialize()` methods and `lateinit var` in delegates
3. Replace callback interfaces with `SharedFlow<ConversationEvent>`
4. Update `ConversationsViewModel` to use factories and explicit coordination
5. Test conversation list functionality end-to-end

### Step 3: Complete Phase 9 — Setup Architecture (8-12h)

1. Audit SetupViewModel delegates against ADRs
2. Convert to AssistedInject where beneficial
3. Replace callbacks with StateFlow/SharedFlow
4. Ensure setup wizard functions correctly

### Step 4: Complete Phase 10 — Service Modernization (8-12h)

1. Add AndroidX Startup dependency
2. Create initializers: `CoilInitializer`, `WorkManagerInitializer`
3. Reduce `BothBubblesApp.onCreate()` to minimal logic
4. Audit foreground services for Android 14 compliance
5. Update manifest with `foregroundServiceType` attributes

### Step 5: Fix Critical TODOs (4-6h)

```kotlin
// ChatSendDelegate.kt:337 - Fix optimistic message cleanup
// BEFORE: Message stays in UI if DB insert fails
// AFTER: Remove optimistic message on failure
private suspend fun insertMessageToDatabase(message: PendingMessageEntity): Result<Unit> {
    return try {
        pendingMessageRepository.insert(message)
        Result.success(Unit)
    } catch (e: Exception) {
        // NEW: Clean up optimistic message
        _optimisticMessages.update { it - message.guid }
        Result.failure(DatabaseError.InsertFailed(cause = e))
    }
}
```

### Step 6: Fix Anti-patterns (4-6h)

**collectAsStateWithLifecycle migration:**
```kotlin
// BEFORE
val state by viewModel.state.collectAsState()

// AFTER
val state by viewModel.state.collectAsStateWithLifecycle()
```

**Files to update (14 instances):**
- Search for `collectAsState()` and replace with `collectAsStateWithLifecycle()`
- Add import: `import androidx.lifecycle.compose.collectAsStateWithLifecycle`

**Remove deprecated composable:**
- Delete `@Deprecated` composable at `ChatBanners.kt:322`
- Update any callers to use `SendModeHelperText`

### Step 7: Decompose Large Files (8-10h)

**ConversationsScreen.kt (961 LOC):**
- Extract `ConversationsSearchBar` composable
- Extract `ConversationsEmptyState` composable
- Extract `ConversationsFAB` composable
- Extract `PullToRefreshWrapper` composable

**ChatMessageList.kt (834 LOC):**
- Extract `MessageListHeader` composable
- Extract `MessageListLoadingIndicator` composable
- Extract `TypingIndicatorSection` composable

## Exit Criteria

### Phase 7 Completion
- [ ] `PendingMessageSource` interface exists and bound
- [ ] `VCardExporter` interface exists and bound
- [ ] `ContactBlocker` interface exists and bound

### Phase 8 Completion
- [ ] All ConversationsViewModel delegates use AssistedInject
- [ ] No `lateinit var` in any ConversationsViewModel delegate
- [ ] No `initialize()` methods in delegates
- [ ] Callbacks replaced with SharedFlow events
- [ ] Conversation list functions correctly

### Phase 9 Completion
- [ ] SetupViewModel delegates aligned with ADRs
- [ ] Setup wizard functions correctly

### Phase 10 Completion
- [ ] `BothBubblesApp.onCreate()` < 20 lines of logic
- [ ] AndroidX Startup initializers created
- [ ] Foreground services have valid `foregroundServiceType`

### Technical Debt
- [ ] `ChatSendDelegate.kt:337` TODO fixed
- [ ] All `collectAsState()` → `collectAsStateWithLifecycle()`
- [ ] Deprecated `ChatBanners` composable removed
- [ ] `ConversationsScreen.kt` < 600 LOC
- [ ] `ChatMessageList.kt` < 600 LOC

## Inventory

| Task | Effort | Owner | Status |
|------|--------|-------|--------|
| Phase 7: Interface extraction | 4-6h | _Unassigned_ | ☐ |
| Phase 8: ConversationsViewModel delegates | 20-30h | _Unassigned_ | ☐ |
| Phase 9: SetupViewModel audit | 8-12h | _Unassigned_ | ☐ |
| Phase 10: AndroidX Startup migration | 8-12h | _Unassigned_ | ☐ |
| Fix ChatSendDelegate TODO | 2h | _Unassigned_ | ☐ |
| collectAsStateWithLifecycle migration | 2h | _Unassigned_ | ☐ |
| Remove deprecated ChatBanners | 1h | _Unassigned_ | ☐ |
| Decompose ConversationsScreen | 4-5h | _Unassigned_ | ☐ |
| Decompose ChatMessageList | 4-5h | _Unassigned_ | ☐ |

**Total Estimated Effort**: 55-75 hours

## Risks

- **High**: Phase 8 touches critical conversation list — thorough testing required
- **Medium**: AndroidX Startup changes app initialization order
- **Low**: Most changes are mechanical refactoring

## Dependencies

- No dependency on CI/CD
- No dependency on observability (but observability will help debug issues)

## Next Steps

After Phase 11, the architecture is complete and stable. Phase 12 (Observability) can proceed to add production monitoring.
