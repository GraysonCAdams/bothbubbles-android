# Phase 14 Implementation Tracking

## Status: Not Started

## Progress Tracker

### Module Creation

| Module | Status | PR | Notes |
|--------|--------|-----|-------|
| `:core:network` directory | ☐ Not Started | — | |
| `:core:network` build.gradle.kts | ☐ Not Started | — | |
| `:core:data` directory | ☐ Not Started | — | |
| `:core:data` build.gradle.kts | ☐ Not Started | — | |
| `:core:design` directory | ☐ Not Started | — | |
| `:core:design` build.gradle.kts | ☐ Not Started | — | |
| settings.gradle.kts update | ☐ Not Started | — | Include new modules |

### :core:network Migration

| Component | Status | PR | Notes |
|-----------|--------|-----|-------|
| BothBubblesApi | ☐ Not Started | — | |
| AuthInterceptor | ☐ Not Started | — | |
| All DTOs | ☐ Not Started | — | ~20 files |
| ServerCapabilities | ☐ Not Started | — | |
| CoreNetworkModule (DI) | ☐ Not Started | — | |
| OkHttp configuration | ☐ Not Started | — | |
| Moshi configuration | ☐ Not Started | — | |

### :core:data Migration

| Component | Status | PR | Notes |
|-----------|--------|-----|-------|
| BothBubblesDatabase | ☐ Not Started | — | |
| DatabaseMigrations | ☐ Not Started | — | 906 LOC |
| ChatDao | ☐ Not Started | — | |
| MessageDao | ☐ Not Started | — | |
| HandleDao | ☐ Not Started | — | |
| AttachmentDao | ☐ Not Started | — | |
| (20 more DAOs) | ☐ Not Started | — | |
| MessageRepository | ☐ Not Started | — | |
| ChatRepository | ☐ Not Started | — | |
| AttachmentRepository | ☐ Not Started | — | |
| (other repositories) | ☐ Not Started | — | |
| PreferencesManager | ☐ Not Started | — | |
| CoreDataModule (DI) | ☐ Not Started | — | |

### :core:design Migration

| Component | Status | PR | Notes |
|-----------|--------|-----|-------|
| Theme.kt | ☐ Not Started | — | |
| Color.kt | ☐ Not Started | — | |
| Typography.kt | ☐ Not Started | — | |
| Shape.kt | ☐ Not Started | — | |
| Avatar.kt | ☐ Not Started | — | |
| Shimmer.kt | ☐ Not Started | — | |
| ErrorView.kt | ☐ Not Started | — | |
| LinkPreview.kt | ☐ Not Started | — | |
| MessageBubble.kt | ☐ Not Started | — | |
| ReactionChip.kt | ☐ Not Started | — | |
| TypingIndicator.kt | ☐ Not Started | — | |
| ConfirmationDialog.kt | ☐ Not Started | — | |
| InfoDialog.kt | ☐ Not Started | — | |

### App Module Updates

| Task | Status | PR | Notes |
|------|--------|-----|-------|
| Update dependencies | ☐ Not Started | — | |
| Remove migrated code | ☐ Not Started | — | |
| Update imports | ☐ Not Started | — | Many files |
| Update DI modules | ☐ Not Started | — | |

### Verification

| Check | Status | Notes |
|-------|--------|-------|
| `:core:network` builds | ☐ | |
| `:core:data` builds | ☐ | |
| `:core:design` builds | ☐ | |
| `:app` builds | ☐ | |
| Room schema exports | ☐ | Check path |
| No circular dependencies | ☐ | |
| App runs correctly | ☐ | |
| All tests pass | ☐ | |

## Files Moved

### To :core:network

_To be tracked during implementation_

### To :core:data

_To be tracked during implementation_

### To :core:design

_To be tracked during implementation_

## Import Updates Required

_Generate list after moves complete_

## Build Time Comparison

| Metric | Before | After |
|--------|--------|-------|
| Clean build | | |
| Incremental (UI) | | |
| Incremental (data) | | |
| Unit tests | | |

## Verification Checklist

Before marking Phase 14 complete:

- [ ] All three core modules created
- [ ] All code moved to appropriate modules
- [ ] DI modules created for each core module
- [ ] No code duplication between modules
- [ ] settings.gradle.kts includes all modules
- [ ] App module depends on all core modules
- [ ] Room schema still exports to correct location
- [ ] No circular dependencies
- [ ] Clean build succeeds
- [ ] All existing tests pass
- [ ] Manual regression testing passed
- [ ] Build time metrics recorded
