# Anti-Pattern Analysis Report

**Generated:** December 2024
**Scope:** Full codebase scan of BothBubbles Android app

## Overview

This document indexes all identified anti-patterns across the codebase, organized by category.

## Summary Statistics

| Category | Critical | High | Medium | Low | Total |
|----------|----------|------|--------|-----|-------|
| UI Layer | 1 | 3 | 5 | 0 | 9 |
| Data Layer | 1 | 5 | 4 | 2 | 12 |
| Services Layer | 2 | 2 | 6 | 4 | 14 |
| DI & Utilities | 2 | 0 | 4 | 4 | 10 |
| Core Modules | 1 | 1 | 2 | 3 | 7 |
| Kotlin-Specific | 0 | 0 | 0 | 5 | 5 |
| Android-Specific | 0 | 0 | 4 | 1 | 5 |
| Performance | 0 | 1 | 4 | 3 | 8 |
| Code Quality | 0 | 2 | 3 | 5 | 10 |
| Security (Extended) | 2 | 4 | 3 | 0 | 9 |
| Concurrency | 4 | 3 | 3 | 0 | 10 |
| Resource & Memory | 0 | 4 | 2 | 1 | 7 |
| Error Handling | 3 | 4 | 5 | 0 | 12 |
| API Design | 0 | 6 | 5 | 0 | 11 |
| Database (Extended) | 0 | 0 | 4 | 5 | 9 |
| Logging & Observability | 2 | 5 | 2 | 2 | 11 |
| Testing | 2 | 1 | 7 | 1 | 11 |
| Accessibility | 0 | 3 | 3 | 4 | 10 |
| Build Configuration | 0 | 1 | 3 | 2 | 6 |
| Navigation | 0 | 3 | 6 | 1 | 10 |
| Process Death / State | 0 | 2 | 6 | 1 | 9 |
| WorkManager | 0 | 0 | 6 | 4 | 10 |
| **Total** | **20** | **50** | **87** | **48** | **205** |

## Report Files

### Layer-Based Reports
1. [UI Layer Anti-Patterns](./01-UI-ANTI-PATTERNS.md) - Compose, ViewModel, and presentation issues
2. [Data Layer Anti-Patterns](./02-DATA-ANTI-PATTERNS.md) - Repository, DAO, and entity issues
3. [Services Layer Anti-Patterns](./03-SERVICES-ANTI-PATTERNS.md) - Service, handler, and background work issues
4. [DI & Utilities Anti-Patterns](./04-DI-UTIL-ANTI-PATTERNS.md) - Dependency injection and utility class issues
5. [Core Modules Anti-Patterns](./05-CORE-ANTI-PATTERNS.md) - Multi-module architecture and network issues

### Category-Based Reports
6. [Kotlin-Specific Anti-Patterns](./06-KOTLIN-ANTI-PATTERNS.md) - Idioms, coroutines, null safety, collections
7. [Android-Specific Anti-Patterns](./07-ANDROID-ANTI-PATTERNS.md) - Context leaks, lifecycle, resources
8. [Performance Anti-Patterns](./08-PERFORMANCE-ANTI-PATTERNS.md) - Memory, CPU, database, network
9. [Code Quality Anti-Patterns](./09-CODE-QUALITY-ANTI-PATTERNS.md) - Dead code, duplication, naming, complexity
10. [Security Anti-Patterns](./10-SECURITY-ANTI-PATTERNS.md) - Data exposure, authentication, IPC
11. [Concurrency Anti-Patterns](./11-CONCURRENCY-ANTI-PATTERNS.md) - Race conditions, thread safety, deadlocks

### Deep-Dive Reports
12. [Resource & Memory Anti-Patterns](./12-RESOURCE-MEMORY-ANTI-PATTERNS.md) - Streams, bitmaps, native resources, caches
13. [Error Handling Anti-Patterns](./13-ERROR-HANDLING-ANTI-PATTERNS.md) - Crashes, recovery, timeouts, retries, error UI
14. [API Design Anti-Patterns](./14-API-DESIGN-ANTI-PATTERNS.md) - Parameters, return values, naming, interfaces
15. [Database Anti-Patterns (Extended)](./15-DATABASE-ANTI-PATTERNS.md) - Queries, schema, migrations, transactions
16. [Logging & Observability Anti-Patterns](./16-LOGGING-ANTI-PATTERNS.md) - Sensitive data, debug code, log levels

### Comprehensive Scan Reports
17. [Testing Anti-Patterns](./17-TESTING-ANTI-PATTERNS.md) - Coverage, isolation, test doubles, assertions
18. [Accessibility Anti-Patterns](./18-ACCESSIBILITY-ANTI-PATTERNS.md) - Screen readers, touch targets, semantics
19. [Build Configuration Anti-Patterns](./19-BUILD-CONFIG-ANTI-PATTERNS.md) - Gradle, ProGuard, dependencies, lint
20. [Navigation Anti-Patterns](./20-NAVIGATION-ANTI-PATTERNS.md) - Deep links, back stack, state, routes
21. [Process Death Anti-Patterns](./21-PROCESS-DEATH-ANTI-PATTERNS.md) - SavedStateHandle, rememberSaveable, config changes
22. [WorkManager Anti-Patterns](./22-WORKMANAGER-ANTI-PATTERNS.md) - Background jobs, constraints, retries

---

## Top Priority Issues

### Critical (Fix Immediately)

| # | Issue | Location | Description |
|---|-------|----------|-------------|
| 1 | **Hardcoded API Key** | `GifRepository.kt:28` | Tenor API key committed to source control |
| 2 | **Unsafe SSL/TLS** | `CoreNetworkModule.kt:63-98` | All certificates accepted without validation |
| 3 | **Auth Key in Logs** | `AuthInterceptor.kt:100` | Full URLs with auth tokens logged |
| 4 | **Password Logged** | `SocketIOConnection.kt:113` | First 4 chars of server password exposed |
| 5 | **PII in Logs** | 8+ files | Phone numbers and emails logged to logcat |
| 6 | **SimpleDateFormat Thread-Safety** | `DateFormatters.kt` | Shared across threads, causes data corruption |
| 7 | **ExoPlayerPool Race** | `ExoPlayerPool.kt:58-81` | Players can be orphaned, memory leak |
| 8 | **runBlocking Deadlock** | `NotificationBuilder.kt:92` | Blocks notification thread |
| 9 | **Mutable Collections in State** | `SearchState.kt:14,17` | Violates Compose stability rules |
| 10 | **Force Unwrap Crash** | `ConversationDetailsLife360.kt:287` | `minOrNull()!!` on empty list crashes |
| 11 | **Unsafe Flow Casts** | `SmsSettingsViewModel.kt:52` | `as Boolean` crashes when StateFlow is null |
| 12 | **maxByOrNull()!! Crash** | `MessageCategorizer.kt` | Force unwrap on potentially empty map |
| 13 | **Missing Service Tests** | `services/` (150 files) | Zero unit test coverage for services |
| 14 | **Missing Repository Tests** | `repository/` (16 classes) | Zero unit test coverage for data layer |

### High Priority (Fix Soon)

| # | Issue | Location | Description |
|---|-------|----------|-------------|
| 13 | HTTP Response Leak | `OpenGraphParser.kt:29-90` | Connection pool exhaustion |
| 14 | InputStream Leaks | `AttachmentEditor.kt:104-106` | File descriptors not closed on exception |
| 15 | Paint Over-Allocation | `DrawingCanvas.kt:147-185` | New Paint every frame causes jank |
| 16 | 108 Debug Traces | `[SEND_TRACE]` in 6 files | Development logs in production |
| 17 | 15-Parameter Method | `Notifier.kt:35-51` | Needs parameter object |
| 18 | Non-Idempotent Retry | `RetryHelper.kt:124-171` | Rate limit 429 worsened by retry |
| 19 | Oversized Repositories | `AttachmentRepository.kt` (808 lines) | Should be decomposed |
| 20 | runBlocking on OkHttp | `AuthInterceptor.kt:114` | Blocks network dispatcher |
| 21 | Lambda Capturing State | `ChatScreen.kt:505-536` | 15+ unstable callback parameters |
| 22 | android:allowBackup=true | `AndroidManifest.xml:84` | All app data extractable |
| 23 | Exported Components | `AndroidManifest.xml` | Missing permission protection |
| 24 | Missing Result Error Handling | Multiple repositories | Mutations fail silently |
| 25 | N+1 Query Patterns | `ChatParticipantOperations.kt:109` | Sequential DB updates |
| 26 | Uncancelled Flow Collectors | `SocketEventHandler.kt:92` | Memory leak |
| 27 | Duplicate Detection Race | `PendingMessageRepository.kt:125` | Duplicate messages sent |
| 28 | Non-Atomic State Updates | `ActiveConversationManager.kt:46` | Inconsistent reads |
| 29 | Missing ProGuard Rules | `proguard-rules.pro` | Hilt, Firebase, CameraX, ML Kit unprotected |
| 30 | Unsafe Back Stack Entry | `NavHost.kt, ChatNavigation.kt` | Entry may not exist after navigate() |
| 31 | Dialog State Not Persisted | `ChatScreenState.kt` | 15 dialogs lost on rotation |
| 32 | Form Input Lost | `ComposerTextField.kt` | Message lost on configuration change |
| 33 | Missing contentDescription | 55+ Compose files | Icons inaccessible to screen readers |

---

## Action Items by Sprint

### Sprint 1 - Security & Logging (URGENT)
- [ ] Rotate Tenor API key and move to BuildConfig
- [ ] Implement certificate pinning for SSL/TLS
- [ ] Sanitize auth keys from all Timber logs
- [ ] Remove password logging from `SocketIOConnection.kt:113`
- [ ] Sanitize PII (phone/email) from logs in 8+ files
- [ ] Remove all `[SEND_TRACE]` logs (108 statements!)
- [ ] Remove `[LOCATION_DEBUG]`, `[FCM_DEBUG]`, `[VM_SEND]` debug tags
- [ ] Set `android:allowBackup="false"`
- [ ] Add permissions to exported components

### Sprint 2 - Crash Prevention
- [ ] Fix force unwrap `minOrNull()!!` in `ConversationDetailsLife360.kt:287`
- [ ] Fix unsafe `as Boolean` casts in `SmsSettingsViewModel.kt:52`
- [ ] Fix `maxByOrNull()!!` in `MessageCategorizer.kt`
- [ ] Replace `Flow.first()` with `firstOrNull()` in `ConversationDetailsViewModel.kt`
- [ ] Add null checks before `.use {}` blocks in attachment handling

### Sprint 3 - Thread Safety
- [ ] Replace SimpleDateFormat with DateTimeFormatter
- [ ] Fix ExoPlayerPool.acquire() synchronization
- [ ] Convert runBlocking to suspend functions
- [ ] Fix duplicate detection race condition
- [ ] Make ActiveConversationManager state updates atomic

### Sprint 4 - Performance & Resources
- [ ] Cache SimpleDateFormat instances as companions
- [ ] Reduce collection operation passes in CursorChatMessageListDelegate
- [ ] Fix Handler lifecycle leaks in ContentObservers
- [ ] Cache Calendar field values instead of repeated .get()
- [ ] Fix HTTP Response leak in `OpenGraphParser.kt` with `.use {}`
- [ ] Fix InputStream leaks in `AttachmentEditor.kt` with `.use {}`
- [ ] Cache Paint objects in `DrawingCanvas.kt` instead of per-frame allocation

### Sprint 5 - Architecture
- [ ] Decompose AttachmentRepository (808 lines)
- [ ] Refactor ChatScreen callbacks to method references
- [ ] Split AvatarGenerator god object (758 lines)
- [ ] Add Result<T> error handling to repositories
- [ ] Refactor `Notifier.showMessageNotification()` (15 params) to use data class

### Sprint 6 - Code Quality & API Design
- [ ] Add logging to empty catch blocks
- [ ] Fix FileStreamingRequestBody buffer bug
- [ ] Replace boolean parameters with enums
- [ ] Move stateful utilities to services package
- [ ] Remove duplicate DAO methods (`deleteMessage`/`deleteMessageByGuid`)
- [ ] Remove duplicate repository methods (`observeAllChats`/`getAllChats`)
- [ ] Fix non-idempotent retry logic in `RetryHelper.kt`

### Sprint 7 - State Restoration
- [ ] Migrate ChatScreenState dialog flags to SavedStateHandle
- [ ] Use `rememberSaveable` for ComposerTextField text input
- [ ] Persist LazyColumn scroll positions in detail screens
- [ ] Use `rememberSaveable` for form inputs in Life360SettingsScreen
- [ ] Add `isStopped` check in MessageSendWorker confirmation wait
- [ ] Add network constraint to ScheduledMessageWorker

### Sprint 8 - Accessibility
- [ ] Add meaningful contentDescription to all Icon components (55+ files)
- [ ] Ensure touch targets are minimum 48dp
- [ ] Add semantics to clickable elements in MessageBubble
- [ ] Replace hard-coded colors with theme colors
- [ ] Add heading semantics to section titles

### Sprint 9 - Testing Infrastructure
- [ ] Create integration tests for MessageSendingService
- [ ] Create integration tests for SocketEventHandler
- [ ] Add database migration tests for Room
- [ ] Add repository tests with in-memory Room database
- [ ] Add ProGuard rules for Hilt, Firebase, CameraX, ML Kit

---

## Quick Wins (< 1 hour each)

1. Add `@Singleton` to dispatcher providers in `CoroutinesModule.kt`
2. Change `LaunchedEffect(Unit)` to proper keys in `ConversationsScreen.kt`
3. Replace unsafe `!!` casts with safe casts in entity computed properties
4. Move `MessageDeduplicator.kt` from util to services package
5. Add missing `@Transaction` to `ChatRepository.deleteChat()`
6. Cache NotificationManager in SocketForegroundService
7. Fix wildcard import in MessageTransformations.kt
8. Gate HTTP logging with `BuildConfig.DEBUG`
9. Remove duplicate `getChat`/`getChatByGuid` methods
10. Restrict FileProvider paths from root to specific directories
11. Remove password first-4-chars logging in `SocketIOConnection.kt:113`
12. Add `.use {}` to HTTP response in `OpenGraphParser.kt`
13. Replace `as Boolean` with `as? Boolean ?: false` in `SmsSettingsViewModel.kt`
14. Add exception parameter to `Timber.e()` calls (15+ locations)
15. Remove unused `text` parameter from `ChatRepository.updateLastMessage()`
16. Replace `remember` with `rememberSaveable` for dropdown expanded state
17. Add work tags to WorkManager requests for better debugging
18. Move Timber version to version catalog (hardcoded in 2 files)
19. Change `api()` to `implementation()` in core:network dependencies
20. Use `currentBackStackEntry` instead of `getBackStackEntry()` after navigate()

---

## Issue Density by File

Files with most issues (consider refactoring priority):

| File | Issues | Lines | Primary Categories |
|------|--------|-------|-------------------|
| CursorChatMessageListDelegate.kt | 8 | ~600 | Performance, Collections |
| SocketIOConnection.kt | 5 | ~200 | Security, Logging, Credentials |
| ChatRepository.kt | 5 | ~600 | API Design, Duplicates |
| CoreNetworkModule.kt | 4 | ~120 | Security, Threading |
| ChatScreen.kt | 4 | ~800 | UI, Compose |
| FcmMessageHandler.kt | 4 | ~300 | Logging, Debug Code |
| Notifier.kt | 4 | ~114 | API Design, Interface |
| AuthInterceptor.kt | 3 | ~150 | Security, Threading |
| PendingMessageRepository.kt | 3 | ~700 | Concurrency, Data |
| AttachmentRepository.kt | 3 | 808 | Architecture, Size |
| DrawingCanvas.kt | 3 | ~250 | Performance, Memory |
| ConversationDetailsLife360.kt | 3 | ~400 | Error Handling, Crashes |

---

## Positive Findings

The codebase demonstrates many excellent patterns:

- **Architecture:** Excellent module boundaries with no cycles
- **Delegates:** Well-decomposed ViewModel delegates
- **Interfaces:** Good service abstractions for testability
- **StateFlow:** Consistent reactive state management
- **Room:** Proper cursor-based pagination, FTS5 indices
- **Compose:** ImmutableList usage, lifecycle-aware collection
- **Error Framework:** AppError sealed classes
