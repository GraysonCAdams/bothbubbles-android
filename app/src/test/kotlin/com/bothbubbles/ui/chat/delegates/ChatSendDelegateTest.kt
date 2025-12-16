package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.fakes.FakeMessageSender
import com.bothbubbles.fakes.FakePendingMessageRepository
import com.bothbubbles.fakes.FakeSocketConnection
import com.bothbubbles.fakes.FakeSoundManager
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.ui.chat.ChatSendMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * Safety Net Test for ChatSendDelegate
 *
 * PURPOSE: This test establishes baseline behavior verification for the message send flow.
 * It serves as a "safety net" during architecture refactoring to ensure we don't break
 * core message sending functionality.
 *
 * ARCHITECTURE ALIGNMENT STATUS:
 * - Phase 0: Test structure established (this file)
 * - Phase 2+3: Tests will be enabled after interface migration
 *
 * CURRENT STATE:
 * ChatSendDelegate currently depends on concrete implementations:
 * - MessageSendingService (should be MessageSender interface)
 * - SocketService (should be SocketConnection interface)
 * - SoundManager (should have interface extracted)
 *
 * After Phase 2+3 migration, these tests will use:
 * - FakeMessageSender (already exists)
 * - FakeSocketConnection (created in Phase 0)
 * - FakeSoundManager (created in Phase 0)
 * - FakePendingMessageRepository (created in Phase 0)
 *
 * BEHAVIORS TO PRESERVE:
 * 1. sendMessage() queues message via PendingMessageRepository
 * 2. Typing indicator starts/stops via SocketService
 * 3. SMS send triggers sound via SoundManager
 * 4. Reply state is cleared after send
 * 5. Upload progress is observed and propagated to UI state
 *
 * @see ADR_0003_ui_depends_on_interfaces.md
 * @see ADR_0004_delegate_lifecycle_rules.md
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendDelegateTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    // Fakes - available after Phase 0
    private lateinit var fakePendingRepo: FakePendingMessageRepository
    private lateinit var fakeMessageSender: FakeMessageSender
    private lateinit var fakeSocket: FakeSocketConnection
    private lateinit var fakeSound: FakeSoundManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakePendingRepo = FakePendingMessageRepository()
        fakeMessageSender = FakeMessageSender()
        fakeSocket = FakeSocketConnection()
        fakeSound = FakeSoundManager()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // BASELINE BEHAVIOR TESTS
    // These tests document expected behavior. Enable after Phase 2+3 migration.
    // =========================================================================

    /**
     * CRITICAL BEHAVIOR: sendMessage should queue via PendingMessageRepository.
     *
     * This is the core send flow - message is persisted locally first for
     * offline-first delivery, then WorkManager handles actual send.
     *
     * TODO(Phase 2+3): Remove @Ignore after ChatSendDelegate uses interfaces
     */
    @Test
    @Ignore("Requires Phase 2+3: ChatSendDelegate must depend on interfaces, not concrete implementations")
    fun `sendMessage queues message via PendingMessageRepository`() = runTest {
        // ARRANGE
        // After Phase 2+3, delegate will accept interfaces in constructor:
        // val delegate = ChatSendDelegate(
        //     pendingMessageRepository = fakePendingRepo,
        //     messageSender = fakeMessageSender,       // Interface, not MessageSendingService
        //     socketConnection = fakeSocket,           // Interface, not SocketService
        //     soundManager = fakeSound                 // May need interface extraction
        // )
        // delegate.initialize("test-chat-guid", this)

        // ACT
        // delegate.sendMessage(
        //     text = "Hello World",
        //     attachments = emptyList(),
        //     currentSendMode = ChatSendMode.IMESSAGE,
        //     isLocalSmsChat = false,
        //     onClearInput = {},
        //     onDraftCleared = {}
        // )
        // testScheduler.advanceUntilIdle()

        // ASSERT
        // assertEquals(1, fakePendingRepo.queuedMessages.size)
        // assertEquals("Hello World", fakePendingRepo.queuedMessages[0].text)
        // assertEquals("test-chat-guid", fakePendingRepo.queuedMessages[0].chatGuid)
    }

    /**
     * BEHAVIOR: SMS sends should trigger sound feedback.
     *
     * When sending via LOCAL_SMS mode, the app plays a confirmation sound
     * immediately (optimistic) since SMS doesn't have delivery confirmation
     * in the same way iMessage does.
     *
     * TODO(Phase 2+3): Remove @Ignore after interface migration
     */
    @Test
    @Ignore("Requires Phase 2+3: ChatSendDelegate must depend on interfaces")
    fun `sendMessage plays sound for SMS delivery mode`() = runTest {
        // ARRANGE - create delegate with fakes

        // ACT
        // delegate.sendMessage(
        //     text = "SMS Message",
        //     attachments = emptyList(),
        //     currentSendMode = ChatSendMode.SMS,
        //     isLocalSmsChat = true,
        //     onClearInput = {},
        //     onDraftCleared = {}
        // )
        // testScheduler.advanceUntilIdle()

        // ASSERT
        // assertEquals(1, fakeSound.sendSoundPlayCount)
    }

    /**
     * BEHAVIOR: Typing indicator starts when user types.
     *
     * Private API feature: notify server when user is composing a message.
     * Debounced to avoid spamming the server.
     *
     * TODO(Phase 2+3): Remove @Ignore after interface migration
     */
    @Test
    @Ignore("Requires Phase 2+3: ChatSendDelegate must depend on interfaces")
    fun `startTyping sends started-typing event when enabled`() = runTest {
        // ARRANGE - create delegate with fakes
        // delegate.initialize("test-chat-guid", this)

        // ACT
        // delegate.startTyping(isPrivateApiEnabled = true, isTypingIndicatorsEnabled = true)

        // ASSERT
        // assertEquals(1, fakeSocket.startedTypingCalls.size)
        // assertEquals("test-chat-guid", fakeSocket.startedTypingCalls[0])
    }

    /**
     * BEHAVIOR: Typing indicator stops when message is sent.
     *
     * Cancel the typing indicator immediately when user sends, rather than
     * waiting for the debounce timeout.
     *
     * TODO(Phase 2+3): Remove @Ignore after interface migration
     */
    @Test
    @Ignore("Requires Phase 2+3: ChatSendDelegate must depend on interfaces")
    fun `sendMessage cancels typing indicator`() = runTest {
        // ARRANGE - create delegate with fakes
        // delegate.initialize("test-chat-guid", this)
        // delegate.startTyping(isPrivateApiEnabled = true, isTypingIndicatorsEnabled = true)

        // ACT
        // delegate.sendMessage(...)

        // ASSERT
        // assertTrue(fakeSocket.stoppedTypingCalls.isNotEmpty())
    }

    /**
     * BEHAVIOR: Reply state is cleared after send.
     *
     * When sending a reply, the replyingToGuid state should be cleared
     * so the next message isn't accidentally a reply.
     *
     * TODO(Phase 2+3): Remove @Ignore after interface migration
     */
    @Test
    @Ignore("Requires Phase 2+3: ChatSendDelegate must depend on interfaces")
    fun `sendMessage clears reply state`() = runTest {
        // ARRANGE - create delegate with fakes
        // delegate.initialize("test-chat-guid", this)
        // delegate.setReplyTo("original-message-guid")

        // ACT
        // delegate.sendMessage(...)
        // testScheduler.advanceUntilIdle()

        // ASSERT
        // assertNull(delegate.state.value.replyingToGuid)
    }

    // =========================================================================
    // INTEGRATION TESTS - Run immediately (no delegate instantiation needed)
    // =========================================================================

    /**
     * Verify fake implementations work correctly.
     * This test runs NOW to ensure our test infrastructure is solid.
     */
    @Test
    fun `fakes record calls correctly`() = runTest {
        // Test FakePendingMessageRepository
        fakePendingRepo.queueMessage(
            chatGuid = "test-chat",
            text = "Test message",
            deliveryMode = MessageDeliveryMode.IMESSAGE
        )
        assertEquals(1, fakePendingRepo.queuedMessages.size)
        assertEquals("Test message", fakePendingRepo.queuedMessages[0].text)

        // Test FakeSocketConnection
        fakeSocket.sendStartedTyping("test-chat")
        assertEquals(1, fakeSocket.startedTypingCalls.size)

        // Test FakeSoundManager
        fakeSound.playSendSound()
        assertEquals(1, fakeSound.sendSoundPlayCount)
    }

    /**
     * Verify FakeMessageSender records send calls.
     * This ensures the existing fake is working properly.
     */
    @Test
    fun `FakeMessageSender records unified send calls`() = runTest {
        fakeMessageSender.sendUnifiedResult = Result.success(
            createTestMessageEntity()
        )

        fakeMessageSender.sendUnified(
            chatGuid = "test-chat",
            text = "Hello",
            replyToGuid = null,
            effectId = null,
            subject = null,
            attachments = emptyList(),
            deliveryMode = MessageDeliveryMode.IMESSAGE,
            subscriptionId = -1,
            tempGuid = null
        )

        assertEquals(1, fakeMessageSender.sendUnifiedCalls.size)
        assertEquals("Hello", fakeMessageSender.sendUnifiedCalls[0].text)
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /**
     * Create a minimal MessageEntity for test assertions.
     */
    private fun createTestMessageEntity() = com.bothbubbles.data.local.db.entity.MessageEntity(
        guid = "test-guid-${System.currentTimeMillis()}",
        chatGuid = "test-chat",
        text = "Test",
        dateCreated = System.currentTimeMillis(),
        isFromMe = true
    )
}
