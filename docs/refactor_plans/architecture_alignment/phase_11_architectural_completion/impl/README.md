# Phase 11 Implementation Tracking

## Status: Not Started

## Progress Tracker

### Phase 7 Completion: Interface Extraction

| Interface | Status | PR | Notes |
|-----------|--------|-----|-------|
| `PendingMessageSource` | ☐ Not Started | — | Extract from `PendingMessageRepository` |
| `VCardExporter` | ☐ Not Started | — | Extract from `VCardService` |
| `ContactBlocker` | ☐ Not Started | — | Extract from `ContactBlockingService` |
| ServiceModule bindings | ☐ Not Started | — | Add `@Binds` for new interfaces |

### Phase 8 Completion: Conversations Architecture

| Delegate | Status | PR | Notes |
|----------|--------|-----|-------|
| `ConversationLoadingDelegate` | ☐ Not Started | — | AssistedInject factory |
| `ConversationActionsDelegate` | ☐ Not Started | — | AssistedInject + explicit APIs |
| `ConversationObserverDelegate` | ☐ Not Started | — | SharedFlow events |
| `UnifiedGroupMappingDelegate` | ☐ Not Started | — | Factory pattern |
| `ConversationsViewModel` coordination | ☐ Not Started | — | Use factories, remove callback web |

### Phase 9 Completion: Setup Architecture

| Delegate | Status | PR | Notes |
|----------|--------|-----|-------|
| `PermissionsDelegate` audit | ☐ Not Started | — | Check ADR compliance |
| `ServerConnectionDelegate` audit | ☐ Not Started | — | AssistedInject if needed |
| `SmsSetupDelegate` audit | ☐ Not Started | — | StateFlow migration |

### Phase 10 Completion: Service Modernization

| Task | Status | PR | Notes |
|------|--------|-----|-------|
| Add AndroidX Startup dependency | ☐ Not Started | — | |
| Create `CoilInitializer` | ☐ Not Started | — | |
| Create `WorkManagerInitializer` | ☐ Not Started | — | |
| Reduce `BothBubblesApp.onCreate()` | ☐ Not Started | — | Target < 20 lines |
| Foreground service type audit | ☐ Not Started | — | Android 14 compliance |

### Technical Debt

| Task | Status | PR | Notes |
|------|--------|-----|-------|
| Fix `ChatSendDelegate.kt:337` TODO | ☐ Not Started | — | Optimistic message cleanup |
| Fix `ChatSendDelegate.kt:584` TODO | ☐ Not Started | — | Attachment table check |
| `collectAsStateWithLifecycle` migration | ☐ Not Started | — | 14 instances |
| Remove deprecated `ChatBanners` | ☐ Not Started | — | Line 322 |
| Decompose `ConversationsScreen.kt` | ☐ Not Started | — | Target < 600 LOC |
| Decompose `ChatMessageList.kt` | ☐ Not Started | — | Target < 600 LOC |
| Move GIF API key to secure storage | ☐ Not Started | — | `GifRepository.kt:29` |

## Files Modified

_To be updated as implementation progresses_

## Verification Checklist

Before marking Phase 11 complete:

- [ ] All Phase 7 interfaces extracted and bound
- [ ] All ConversationsViewModel delegates use AssistedInject
- [ ] No `lateinit var` or `initialize()` in ConversationsViewModel delegates
- [ ] SetupViewModel delegates audited
- [ ] AndroidX Startup configured
- [ ] All critical TODOs resolved
- [ ] `collectAsStateWithLifecycle` migration complete
- [ ] Large files decomposed
- [ ] App builds successfully
- [ ] Manual regression testing passed
- [ ] Conversation list functions correctly
- [ ] Setup wizard functions correctly
