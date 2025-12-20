# Testing Anti-Patterns

**Scope:** Test coverage, test isolation, test doubles, assertions

---

## Critical Issues

### 1. Missing Service Layer Test Coverage

**Issue:** ~150 service files with essentially zero unit tests.

**Untested Services:**
- 11 messaging service files (MessageSendingService, IncomingMessageHandler)
- 9 socket service files (SocketEventHandler, handlers)
- No tests for socket event routing or message processing

**Impact:** Critical business logic completely untested.

**Fix:** Create integration test suites for MessageSendingService, SocketEventHandler, IncomingMessageHandler.

---

### 2. Missing Repository Test Coverage

**Issue:** 16 Repository classes with zero unit tests.

**Untested:**
- MessageRepository, ChatRepository, AttachmentRepository
- PendingMessageRepository (blocks delegate testing)
- Life360Repository, ScheduledMessageRepository

**Fix:** Use Room's in-memory database for repository tests.

---

### 3. Missing Database Migration Tests

**Severity:** HIGH

**Location:** `DatabaseMigrations.kt` (1018 lines)

**Issue:** 40+ schema versions with no migration tests. `fallbackToDestructiveMigrationOnDowngrade()` is dangerous.

**Fix:**
```kotlin
class DatabaseMigrationTest {
    @Test
    fun testMigrationFrom20To21() {
        // Create database at version 20
        // Trigger migration to 21
        // Verify data integrity
    }
}
```

---

## High Severity Issues

### 4. State Cleanup Anti-Pattern in Fakes

**Location:** `ChatSendDelegateTest.kt` (lines 66-78)

**Issue:** Test fakes have `reset()` methods that are NEVER CALLED:
```kotlin
@Before
fun setup() {
    fakePendingRepo = FakePendingMessageRepository()
    // reset() never called on reused instances
}
```

**Problem:** State leaks between tests if fakes are reused.

**Fix:** Call `reset()` in `@Before` or create fresh instances.

---

## Medium Severity Issues

### 5. Unsafe Type Casts in Tests

**Location:** `MessageSegmentParserTest.kt` (lines 39, 66)

**Issue:**
```kotlin
segments[0] as MessageSegment.YouTubeVideoSegment  // Crashes if wrong type
```

**Fix:**
```kotlin
assertTrue(segments[0] is MessageSegment.YouTubeVideoSegment)
val segment = segments[0] as MessageSegment.YouTubeVideoSegment
```

---

### 6. Weak Assertions - Only Checking Size

**Location:** `ChatSendDelegateTest.kt` (line 117)

**Issue:**
```kotlin
assertEquals(1, fakeSocket.startedTypingCalls.size)  // Doesn't verify chat GUID
```

**Fix:**
```kotlin
assertEquals(1, fakeSocket.startedTypingCalls.size)
assertEquals("test-chat-guid", fakeSocket.startedTypingCalls[0])
```

---

### 7. Test Isolation Violation - Shared Dispatcher

**Location:** `ChatSendDelegateTest.kt` (lines 68, 77)

**Issue:** `Dispatchers.setMain()` affects ALL tests globally. If test crashes before `@After`, subsequent tests fail.

**Fix:** Use `runTest {}` which handles dispatcher setup automatically.

---

### 8. Time-Dependent Test Data

**Location:** `ChatSendDelegateTest.kt` (line 212)

**Issue:**
```kotlin
guid = "test-guid-${System.currentTimeMillis()}"  // Non-deterministic
```

**Fix:** Use fixed timestamps or UUIDs for reproducibility.

---

### 9. No Feature Module Tests

**Issue:** `feature/chat`, `feature/settings`, `feature/conversations` have no test directories.

---

### 10. No Core Module Tests

**Issue:** `core/network`, `core/data`, `core/model` have no tests.

---

## Low Severity Issues

### 11. Mutable Test Double Lists

**Location:** `FakeMessageSender.kt` (lines 58-67)

**Issue:**
```kotlin
val sendUnifiedCalls = mutableListOf<SendUnifiedCall>()  // Public mutable
```

**Fix:**
```kotlin
private val _sendUnifiedCalls = mutableListOf<SendUnifiedCall>()
val sendUnifiedCalls: List<SendUnifiedCall> = _sendUnifiedCalls
```

---

## Summary Table

| Issue | Severity | Category |
|-------|----------|----------|
| Missing service tests | CRITICAL | Coverage |
| Missing repository tests | CRITICAL | Coverage |
| Missing migration tests | HIGH | Coverage |
| State cleanup in fakes | HIGH | Isolation |
| Unsafe casts | MEDIUM | Assertions |
| Weak assertions | MEDIUM | Assertions |
| Shared dispatcher | MEDIUM | Isolation |
| Time-dependent data | MEDIUM | Determinism |
| No feature module tests | MEDIUM | Coverage |
| No core module tests | MEDIUM | Coverage |
| Mutable fake lists | LOW | Encapsulation |
