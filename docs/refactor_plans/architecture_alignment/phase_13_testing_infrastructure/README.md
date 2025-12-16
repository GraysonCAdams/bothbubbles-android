# Phase 13 — Testing Infrastructure

> **Status**: Planned
> **Prerequisite**: Phase 11 (Architectural Completion) — interfaces must exist for contract tests

## Layman's Explanation

Right now, the app has only 5 test files with 279 lines of code. That's a very thin safety net for a production app. When we make changes, we have no automated way to catch regressions before they ship.

This phase builds comprehensive testing infrastructure: unit tests for business logic, UI tests for critical flows, screenshot tests for visual regressions, and contract tests for service interfaces.

## Connection to Shared Vision

Testing directly supports our "Testable by design" principle. The interface-based architecture from Phase 2-3 enables easy test fake injection. Phase 7's interface extraction makes contract testing possible.

## Goals

1. **Unit Tests**: Cover all delegates and services with unit tests using fakes
2. **Compose UI Tests**: Test critical user flows (message sending, conversation navigation)
3. **Screenshot Tests**: Catch visual regressions with Paparazzi/Roborazzi
4. **Contract Tests**: Verify interface implementations match contracts
5. **Database Migration Tests**: Ensure upgrade paths work correctly

## Current State

| Category | Current | Target |
|----------|---------|--------|
| Unit Test Files | 5 | 50+ |
| Test LOC | 279 | 5,000+ |
| Code Coverage | <5% | 60%+ for services/delegates |
| UI Tests | 0 | 10+ critical flows |
| Screenshot Tests | 0 | 20+ key screens |
| Contract Tests | 0 | All service interfaces |
| Migration Tests | 0 | All 37 migrations |

## Current Test Assets

```
app/src/test/kotlin/com/bothbubbles/
├── fakes/
│   ├── FakeMessageSender.kt
│   ├── FakePendingMessageRepository.kt
│   ├── FakeSoundManager.kt
│   └── FakeSocketConnection.kt
└── ui/chat/delegates/
    └── ChatSendDelegateTest.kt  (Safety net test)
```

## Implementation Steps

### Step 1: Add Testing Dependencies

```kotlin
// build.gradle.kts (app)
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
testImplementation("app.cash.turbine:turbine:1.0.0")
testImplementation("io.mockk:mockk:1.13.8")
testImplementation("com.google.truth:truth:1.1.5")

// UI Testing
androidTestImplementation("androidx.compose.ui:ui-test-junit4")
androidTestImplementation("androidx.test.ext:junit:1.1.5")
androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
debugImplementation("androidx.compose.ui:ui-test-manifest")

// Screenshot Testing
testImplementation("app.cash.paparazzi:paparazzi:1.3.1")

// Mock Web Server
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

### Step 2: Create Test Utilities

```kotlin
// src/test/kotlin/com/bothbubbles/util/TestDispatcherRule.kt
class TestDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }
    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

// src/test/kotlin/com/bothbubbles/util/FlowTestExtensions.kt
suspend fun <T> Flow<T>.testCollect(
    scope: CoroutineScope,
    block: suspend (List<T>) -> Unit
) {
    val emissions = mutableListOf<T>()
    val job = scope.launch { collect { emissions.add(it) } }
    block(emissions)
    job.cancel()
}
```

### Step 3: Unit Tests for Delegates

**Priority Order** (by business impact):
1. `ChatSendDelegate` — Message sending logic
2. `ChatMessageListDelegate` — Message display and pagination
3. `ChatComposerDelegate` — Input handling
4. `ConversationLoadingDelegate` — Conversation list loading
5. `ConversationActionsDelegate` — Archive, delete, pin

**Test Pattern:**

```kotlin
class ChatSendDelegateTest {
    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val fakeMessageSender = FakeMessageSender()
    private val fakePendingRepo = FakePendingMessageRepository()
    private lateinit var delegate: ChatSendDelegate

    @Before
    fun setup() {
        delegate = ChatSendDelegate(
            messageSender = fakeMessageSender,
            pendingMessageRepository = fakePendingRepo,
            chatGuid = "test-chat-guid",
            scope = TestScope()
        )
    }

    @Test
    fun `sendMessage queues message and updates state`() = runTest {
        delegate.sendMessage("Hello")

        assertThat(fakePendingRepo.queuedMessages).hasSize(1)
        assertThat(delegate.sendState.value.isSending).isFalse()
    }

    @Test
    fun `sendMessage with attachment includes attachment metadata`() = runTest {
        val attachment = PendingAttachmentInput(uri = Uri.parse("content://test"))
        delegate.sendMessage("Photo", listOf(attachment))

        assertThat(fakePendingRepo.queuedMessages.first().attachments).hasSize(1)
    }
}
```

### Step 4: Create Additional Test Fakes

```kotlin
// Fakes needed for comprehensive testing
src/test/kotlin/com/bothbubbles/fakes/
├── FakeChatRepository.kt
├── FakeMessageRepository.kt
├── FakeAttachmentRepository.kt
├── FakeNotifier.kt
├── FakeIncomingMessageProcessor.kt
├── FakePendingMessageSource.kt      // After Phase 11
├── FakeVCardExporter.kt              // After Phase 11
├── FakeContactBlocker.kt             // After Phase 11
└── FakeBothBubblesApi.kt
```

### Step 5: Compose UI Tests

```kotlin
// src/androidTest/kotlin/com/bothbubbles/ui/chat/ChatScreenTest.kt
@HiltAndroidTest
class ChatScreenTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<TestActivity>()

    @Inject
    lateinit var fakeMessageSender: FakeMessageSender

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun sendingMessage_showsInMessageList() {
        composeRule.setContent {
            ChatScreen(chatGuid = "test-guid")
        }

        composeRule.onNodeWithTag("messageInput").performTextInput("Hello")
        composeRule.onNodeWithTag("sendButton").performClick()

        composeRule.onNodeWithText("Hello").assertIsDisplayed()
    }

    @Test
    fun longPressMessage_showsContextMenu() {
        // Setup: Add a message to the chat
        composeRule.setContent {
            ChatScreen(chatGuid = "test-guid")
        }

        composeRule.onNodeWithTag("message-0").performLongClick()

        composeRule.onNodeWithText("Copy").assertIsDisplayed()
        composeRule.onNodeWithText("Reply").assertIsDisplayed()
    }
}
```

### Step 6: Screenshot Tests with Paparazzi

```kotlin
// src/test/kotlin/com/bothbubbles/ui/screenshots/MessageBubbleScreenshotTest.kt
class MessageBubbleScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material3.DayNight"
    )

    @Test
    fun messageBubble_sent() {
        paparazzi.snapshot {
            BothBubblesTheme {
                MessageBubble(
                    message = testSentMessage,
                    isFromMe = true,
                    showTimestamp = true
                )
            }
        }
    }

    @Test
    fun messageBubble_received() {
        paparazzi.snapshot {
            BothBubblesTheme {
                MessageBubble(
                    message = testReceivedMessage,
                    isFromMe = false,
                    showTimestamp = true
                )
            }
        }
    }

    @Test
    fun conversationTile_unread() {
        paparazzi.snapshot {
            BothBubblesTheme {
                ConversationTile(
                    conversation = testConversationWithUnread,
                    onClick = {}
                )
            }
        }
    }
}
```

### Step 7: Contract Tests for Interfaces

```kotlin
// src/test/kotlin/com/bothbubbles/contracts/MessageSenderContractTest.kt
abstract class MessageSenderContractTest {
    abstract fun createMessageSender(): MessageSender

    @Test
    fun `sendMessage returns success for valid message`() = runTest {
        val sender = createMessageSender()
        val result = sender.sendMessage(validSendRequest)

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `sendMessage returns failure for empty text`() = runTest {
        val sender = createMessageSender()
        val result = sender.sendMessage(emptyTextRequest)

        assertThat(result.isFailure).isTrue()
    }
}

// Concrete test for real implementation
class MessageSendingServiceContractTest : MessageSenderContractTest() {
    override fun createMessageSender(): MessageSender {
        return MessageSendingService(
            api = mockApi,
            pendingRepo = fakePendingRepo,
            // ... other dependencies
        )
    }
}

// Verification that fake matches contract
class FakeMessageSenderContractTest : MessageSenderContractTest() {
    override fun createMessageSender(): MessageSender = FakeMessageSender()
}
```

### Step 8: Database Migration Tests

```kotlin
// src/androidTest/kotlin/com/bothbubbles/data/local/db/MigrationTest.kt
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BothBubblesDatabase::class.java
    )

    @Test
    fun migrate1to2() {
        // Create database with version 1
        helper.createDatabase(TEST_DB_NAME, 1).apply {
            execSQL("INSERT INTO chats (guid, displayName) VALUES ('test', 'Test')")
            close()
        }

        // Run migration
        helper.runMigrationsAndValidate(TEST_DB_NAME, 2, true, MIGRATION_1_2)

        // Verify
        val db = helper.openDatabase(TEST_DB_NAME, 2, true)
        db.query("SELECT starred FROM chats WHERE guid = 'test'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0) // Default value
        }
    }

    @Test
    fun migrateAllVersions() {
        helper.createDatabase(TEST_DB_NAME, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            37,
            true,
            *ALL_MIGRATIONS
        )
    }
}
```

## Exit Criteria

### Unit Tests
- [ ] All ChatViewModel delegates have unit tests (14 delegates)
- [ ] All ConversationsViewModel delegates have unit tests (5 delegates)
- [ ] Key services have unit tests (MessageSendingService, IncomingMessageHandler)
- [ ] Test coverage for services/delegates > 60%

### UI Tests
- [ ] Message sending flow tested
- [ ] Conversation navigation tested
- [ ] Setup wizard tested
- [ ] Settings navigation tested
- [ ] Long-press context menus tested

### Screenshot Tests
- [ ] MessageBubble variants captured
- [ ] ConversationTile variants captured
- [ ] Key screens captured (Chat, Conversations, Settings)
- [ ] Light and dark theme variants

### Contract Tests
- [ ] `MessageSender` contract test exists
- [ ] `SocketConnection` contract test exists
- [ ] `Notifier` contract test exists
- [ ] All fakes pass contract tests

### Migration Tests
- [ ] All 37 migrations have tests
- [ ] Full migration path (1 → 37) tested

## Inventory

| Task | Effort | Owner | Status |
|------|--------|-------|--------|
| Add testing dependencies | 1h | _Unassigned_ | ☐ |
| Create test utilities | 2h | _Unassigned_ | ☐ |
| ChatViewModel delegate tests (14) | 20h | _Unassigned_ | ☐ |
| ConversationsViewModel delegate tests (5) | 8h | _Unassigned_ | ☐ |
| Service tests | 10h | _Unassigned_ | ☐ |
| Create additional fakes | 6h | _Unassigned_ | ☐ |
| Compose UI test setup | 4h | _Unassigned_ | ☐ |
| Critical flow UI tests (5) | 10h | _Unassigned_ | ☐ |
| Paparazzi setup | 2h | _Unassigned_ | ☐ |
| Screenshot tests (20) | 8h | _Unassigned_ | ☐ |
| Contract test framework | 4h | _Unassigned_ | ☐ |
| Contract tests for interfaces | 6h | _Unassigned_ | ☐ |
| Migration test framework | 2h | _Unassigned_ | ☐ |
| Migration tests (37) | 12h | _Unassigned_ | ☐ |

**Total Estimated Effort**: 95-105 hours

## Risks

- **Medium**: UI tests can be flaky — use deterministic test data
- **Medium**: Screenshot tests need baseline management
- **Low**: Unit tests are straightforward with existing fakes

## Dependencies

- Phase 11 must complete interface extraction for contract tests
- No dependency on CI/CD (tests can run locally)
- Observability (Phase 12) helps debug test failures

## Test Maintenance

### Golden File Management (Screenshots)

```bash
# Record new golden files
./gradlew recordPaparazziDebug

# Verify against golden files
./gradlew verifyPaparazziDebug
```

### Running Tests

```bash
# Unit tests
./gradlew test

# UI tests (requires emulator/device)
./gradlew connectedAndroidTest

# Screenshot tests
./gradlew verifyPaparazziDebug
```

## Next Steps

Phase 14 (Core Module Extraction) can proceed in parallel. Tests will need to be moved to appropriate modules as extraction happens.
