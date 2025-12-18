# Architecture Alignment — Roadmap

Outstanding phases and tasks for the BothBubbles architecture refactoring.

## Phase Overview

| Phase | Focus | Status | Effort |
|-------|-------|--------|--------|
| [Phase 13](#phase-13-testing-infrastructure) | Testing Infrastructure | 🔜 Planned | 95-105h |
| [Phase 14](#phase-14-feature-module-extraction) | Feature Module Extraction | 🔄 Structure Complete | 60-70h |
| [Phase 15](#phase-15-security--polish) | Security & Polish | 🔜 Planned | 28-38h |
| [Phase 16](#phase-16-cicd-pipeline) | CI/CD Pipeline | 🔜 Planned | 16-18h |

**Total Remaining Effort**: ~200-230 hours

---

## Phase 13: Testing Infrastructure

> **Status**: Planned
> **Prerequisite**: None (can start now)

Build comprehensive testing infrastructure for the codebase.

### Checklist

- [ ] **Add Testing Dependencies**
  - [ ] Add mockk (1.13.8)
  - [ ] Add truth (1.1.5)
  - [ ] Add mockwebserver (4.12.0)
  - [ ] Add Paparazzi (1.3.4) for screenshot tests

- [ ] **Create Test Utilities**
  - [ ] `TestDispatcherRule.kt` — JUnit rule for test dispatchers
  - [ ] `FlowTestExtensions.kt` — Extensions for testing Flows
  - [ ] `TestDataFactory.kt` — Generate test entities

- [ ] **Create Test Fakes**
  - [ ] `FakeChatRepository.kt`
  - [ ] `FakeMessageRepository.kt`
  - [ ] `FakeAttachmentRepository.kt`
  - [ ] `FakeNotifier.kt`
  - [ ] `FakeIncomingMessageProcessor.kt`

- [ ] **Unit Tests: ChatViewModel Delegates (14)**
  - [ ] `ChatSendDelegateTest.kt` (exists — expand)
  - [ ] `ChatComposerDelegateTest.kt`
  - [ ] `ChatMessageListDelegateTest.kt`
  - [ ] `ChatSearchDelegateTest.kt`
  - [ ] `ChatOperationsDelegateTest.kt`
  - [ ] `ChatEffectsDelegateTest.kt`
  - [ ] `ChatThreadDelegateTest.kt`
  - [ ] `ChatSyncDelegateTest.kt`
  - [ ] `ChatSendModeManagerTest.kt`
  - [ ] ... (remaining delegates)

- [ ] **Unit Tests: ConversationsViewModel Delegates (5)**
  - [ ] `ConversationLoadingDelegateTest.kt`
  - [ ] `ConversationActionsDelegateTest.kt`
  - [ ] `ConversationObserverDelegateTest.kt`
  - [ ] `UnifiedGroupMappingDelegateTest.kt`

- [ ] **Unit Tests: Services**
  - [ ] `MessageSendingServiceTest.kt`
  - [ ] `IncomingMessageHandlerTest.kt`
  - [ ] `SocketServiceTest.kt`

- [ ] **Contract Tests**
  - [ ] `MessageSenderContractTest.kt`
  - [ ] `SocketConnectionContractTest.kt`
  - [ ] `NotifierContractTest.kt`

- [ ] **Screenshot Tests (Paparazzi)**
  - [ ] `MessageBubbleScreenshotTest.kt`
  - [ ] `ConversationTileScreenshotTest.kt`
  - [ ] Key screens (light + dark theme)

- [ ] **Database Migration Tests**
  - [ ] Test all 37+ migrations
  - [ ] Full migration path test (1 → latest)

### Exit Criteria
- Test coverage > 60% for services/delegates
- All interface contracts have tests
- All fakes pass contract tests
- Screenshot baselines established

---

## Phase 14: Feature Module Extraction

> **Status**: Structure Complete (migration remaining)
> **Prerequisite**: Phase 6 ✅ Complete

Migrate code to feature modules for better build times and encapsulation.

### Completed ✅
- [x] `:navigation` module created with Routes.kt
- [x] `:core:data` module with interfaces
- [x] Feature module structures created
- [x] Build configuration complete
- [x] **File decomposition** (ConversationsScreen, ChatMessageList)

### Remaining Tasks

- [ ] **Migrate Settings Module**
  - [ ] Move `AboutScreen.kt` + `AboutViewModel.kt` to `:feature:settings`
  - [ ] Update imports and verify build
  - [ ] Move remaining settings screens (15+)

- [ ] **Migrate Setup Module**
  - [ ] Move `SetupScreen.kt` + `SetupViewModel.kt` to `:feature:setup`
  - [ ] Move setup delegates
  - [ ] Update navigation wiring

- [ ] **Migrate Conversations Module**
  - [ ] Move `ConversationsScreen.kt` + `ConversationsViewModel.kt`
  - [ ] Move conversation delegates (5)
  - [ ] Move conversation components

- [ ] **Migrate Chat Module** (largest, do last)
  - [ ] Move `ChatScreen.kt` + `ChatViewModel.kt`
  - [ ] Move chat delegates (14)
  - [ ] Move chat components

- [ ] **Navigation Wiring**
  - [ ] Update `AppNavHost.kt` to use feature navigation extensions
  - [ ] Each feature provides `NavGraphBuilder` extension

- [ ] **Cleanup**
  - [ ] Move tests to appropriate modules
  - [ ] Remove entity aliases in app module
  - [ ] Verify all features work

### Exit Criteria
- All features in separate modules
- No feature module depends on another
- App module < 500 LOC
- Build time reduced for incremental changes

---

## Phase 15: Security & Polish

> **Status**: Planned
> **Prerequisite**: Phase 14 complete

Security audit and final polish for production.

### Checklist

- [ ] **Secrets Management**
  - [ ] Move API keys to BuildConfig
  - [ ] Ensure no secrets in version control
  - [ ] Add `key.properties` template

- [ ] **Security Audit**
  - [ ] OWASP Mobile Top 10 review
  - [ ] SQL injection prevention (Room handles this)
  - [ ] XSS prevention in WebView (if any)
  - [ ] Certificate pinning for server connection

- [ ] **Accessibility Audit**
  - [ ] TalkBack testing
  - [ ] Touch target sizes (48dp minimum)
  - [ ] Color contrast ratios
  - [ ] Content descriptions on icons

- [ ] **Performance Baseline**
  - [ ] Measure app startup time (cold/warm)
  - [ ] Profile memory usage
  - [ ] Identify jank in scrolling
  - [ ] Baseline battery impact

- [ ] **Module Documentation**
  - [ ] README for each module
  - [ ] API documentation for public interfaces
  - [ ] Architecture diagram updates

### Exit Criteria
- No secrets in code
- OWASP audit passed
- Accessibility compliant
- Performance baselines documented

---

## Phase 16: CI/CD Pipeline

> **Status**: Planned
> **Prerequisite**: Phases 13, 15 complete

Automate quality gates for sustainable development.

### Checklist

- [ ] **PR Checks**
  - [ ] Lint check (Android Lint)
  - [ ] Detekt (Kotlin static analysis)
  - [ ] Unit tests
  - [ ] Build verification

- [ ] **Nightly Builds**
  - [ ] Full test suite
  - [ ] Release build
  - [ ] Dependency audit (Dependabot)

- [ ] **Release Pipeline**
  - [ ] Tag-based releases
  - [ ] Changelog generation
  - [ ] APK signing
  - [ ] Play Store upload (optional)

- [ ] **Branch Protection**
  - [ ] Required status checks
  - [ ] Require PR reviews
  - [ ] No direct push to main

### Exit Criteria
- All PRs run automated checks
- Nightly builds catch regressions
- Release process is automated
- Branch protection enforced

---

## Quick Reference

### Build Commands

```bash
# Set JAVA_HOME (macOS)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Debug build
./gradlew assembleDebug

# Run tests
./gradlew test

# Lint
./gradlew lint

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Module Structure

```
bothbubbles-app/
├── app/                    # Application shell
├── core/
│   ├── model/              # ✅ Domain models
│   ├── network/            # ✅ API layer
│   ├── data/               # ✅ Interfaces
│   └── design/             # Theme, shared components
├── feature/
│   ├── chat/               # 🔄 Structure ready
│   ├── conversations/      # 🔄 Structure ready
│   ├── settings/           # 🔄 Structure ready
│   └── setup/              # 🔄 Structure ready
└── navigation/             # ✅ Route contracts
```

### Priority Order

1. **Phase 14** (Feature Module Extraction) — Improves build times
2. **Phase 13** (Testing) — Can run in parallel
3. **Phase 15** (Security) — Before release
4. **Phase 16** (CI/CD) — Sustains quality
