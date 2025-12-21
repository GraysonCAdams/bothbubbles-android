# Architecture Alignment — Roadmap

Outstanding phases and tasks for the BothBubbles architecture refactoring.

**Note**: For completed phases (0-15), see [COMPLETED.md](./COMPLETED.md).

## Phase Overview

| Phase | Focus | Status | Effort |
|-------|-------|--------|--------|
| [Phase 13](#phase-13-testing-infrastructure) | Testing Infrastructure | 🔜 Planned | 95-105h |
| Phase 14 | Core Module Extraction | ✅ Complete | - |
| Phase 15 | Feature Module Extraction | ✅ Complete | - |
| [Phase 16](#phase-16-security--polish) | Security & Polish | 🔜 Planned | 28-38h |
| [Phase 17](#phase-17-cicd-pipeline) | CI/CD Pipeline | 🔜 Planned | 16-18h |

**Total Remaining Effort**: ~140-160 hours

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

## Phase 16: Security & Polish

> **Status**: Planned
> **Prerequisite**: Phase 15 complete

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

## Phase 17: CI/CD Pipeline

> **Status**: Planned
> **Prerequisite**: Phases 13, 16 complete

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
│   └── design/             # ✅ Theme, shared components
├── feature/
│   ├── chat/               # ✅ Structure complete
│   ├── conversations/      # ✅ Structure complete
│   ├── settings/           # ✅ Structure complete
│   └── setup/              # ✅ Structure complete
└── navigation/             # ✅ Route contracts
```

### Priority Order

1. **Phase 13** (Testing) — Build test infrastructure
2. **Phase 16** (Security) — Before release
3. **Phase 17** (CI/CD) — Sustains quality
