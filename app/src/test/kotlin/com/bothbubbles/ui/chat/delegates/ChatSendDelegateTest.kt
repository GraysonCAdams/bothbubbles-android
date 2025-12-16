package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.fakes.FakeMessageSender
import com.bothbubbles.fakes.FakePendingMessageRepository
import com.bothbubbles.fakes.FakeSocketConnection
import com.bothbubbles.fakes.FakeSoundManager
import com.bothbubbles.services.messaging.MessageDeliveryMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Safety Net Test for ChatSendDelegate
 *
 * PURPOSE: This test establishes baseline behavior verification for the message send flow.
 * It serves as a "safety net" during architecture refactoring to ensure we don't break
 * core message sending functionality.
 *
 * ARCHITECTURE ALIGNMENT STATUS:
 * - Phase 0: Test structure established ✓
 * - Phase 2+3: Interface migration for UI layer dependencies complete ✓
 *   - MessageSender ✓ (MessageSendingService implements interface)
 *   - SocketConnection ✓ (SocketService implements interface)
 *   - SoundPlayer ✓ (SoundManager implements interface)
 *
 * REMAINING WORK:
 * - PendingMessageRepository needs interface extraction to enable full delegate testing
 *   Currently it's a concrete class, so we can't inject FakePendingMessageRepository
 *   into ChatSendDelegate for complete unit testing.
 *
 * CURRENT TEST SCOPE:
 * These tests validate that our fake implementations correctly implement the interfaces
 * used by ChatSendDelegate. This ensures the test infrastructure is ready for when
 * PendingMessageRepository is extracted to an interface.
 *
 * BEHAVIORS TO PRESERVE (when full testing is enabled):
 * 1. sendMessage() queues message via PendingMessageRepository
 * 2. Typing indicator starts/stops via SocketConnection
 * 3. SMS send triggers sound via SoundPlayer
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

    // Fakes
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
    // INTERFACE CONTRACT TESTS
    // These tests validate that our fake implementations match the interfaces
    // they're meant to simulate, ensuring our test infrastructure is sound.
    // =========================================================================

    /**
     * Verify FakeSoundManager implements SoundPlayer interface correctly.
     *
     * After Phase 2+3, SoundManager implements SoundPlayer, and ChatSendDelegate
     * depends on the interface. This test ensures our fake works for testing.
     */
    @Test
    fun `FakeSoundManager implements SoundPlayer interface`() = testScope.runTest {
        // ACT - Call interface methods
        fakeSound.playSendSound()
        fakeSound.playReceiveSound("test-chat-guid")

        // ASSERT - Verify calls were recorded
        assertEquals(1, fakeSound.sendSoundPlayCount)
        assertEquals(1, fakeSound.receiveSoundPlayCount)
        assertEquals("test-chat-guid", fakeSound.receiveSoundChatGuids[0])
    }

    /**
     * Verify FakeSocketConnection implements SocketConnection interface correctly.
     *
     * ChatSendDelegate uses SocketConnection for typing indicators. This test
     * ensures our fake properly records those calls.
     */
    @Test
    fun `FakeSocketConnection implements typing indicator methods`() = testScope.runTest {
        // ACT - Call typing indicator methods
        fakeSocket.sendStartedTyping("test-chat-guid")
        fakeSocket.sendStoppedTyping("test-chat-guid")

        // ASSERT - Verify calls were recorded
        assertEquals(1, fakeSocket.startedTypingCalls.size)
        assertEquals("test-chat-guid", fakeSocket.startedTypingCalls[0])
        assertEquals(1, fakeSocket.stoppedTypingCalls.size)
        assertEquals("test-chat-guid", fakeSocket.stoppedTypingCalls[0])
    }

    /**
     * Verify FakeMessageSender records all send operations.
     *
     * ChatSendDelegate uses MessageSender for retries and forwards.
     * This test ensures our fake properly records those calls.
     */
    @Test
    fun `FakeMessageSender records send operations`() = testScope.runTest {
        // ARRANGE
        fakeMessageSender.sendUnifiedResult = Result.success(createTestMessageEntity())
        fakeMessageSender.forwardMessageResult = Result.success(createTestMessageEntity())
        fakeMessageSender.retryMessageResult = Result.success(createTestMessageEntity())

        // ACT - Call various MessageSender methods
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

        fakeMessageSender.forwardMessage("msg-guid", "target-chat")
        fakeMessageSender.retryMessage("failed-guid")

        // ASSERT
        assertEquals(1, fakeMessageSender.sendUnifiedCalls.size)
        assertEquals("Hello", fakeMessageSender.sendUnifiedCalls[0].text)
        assertEquals(1, fakeMessageSender.forwardMessageCalls.size)
        assertEquals("msg-guid", fakeMessageSender.forwardMessageCalls[0].messageGuid)
        assertEquals(1, fakeMessageSender.retryMessageCalls.size)
        assertEquals("failed-guid", fakeMessageSender.retryMessageCalls[0])
    }

    /**
     * Verify FakePendingMessageRepository records queue operations.
     *
     * This validates the fake works correctly. Full delegate testing requires
     * PendingMessageRepository to be extracted to an interface.
     */
    @Test
    fun `FakePendingMessageRepository records queued messages`() = testScope.runTest {
        // ACT
        fakePendingRepo.queueMessage(
            chatGuid = "test-chat",
            text = "Test message",
            subject = null,
            replyToGuid = null,
            effectId = null,
            attachments = emptyList(),
            deliveryMode = MessageDeliveryMode.IMESSAGE,
            forcedLocalId = null
        )

        // ASSERT
        assertEquals(1, fakePendingRepo.queuedMessages.size)
        assertEquals("Test message", fakePendingRepo.queuedMessages[0].text)
        assertEquals("test-chat", fakePendingRepo.queuedMessages[0].chatGuid)
        assertEquals(MessageDeliveryMode.IMESSAGE, fakePendingRepo.queuedMessages[0].deliveryMode)
    }

    /**
     * Verify FakePendingMessageRepository records retry and cancel operations.
     */
    @Test
    fun `FakePendingMessageRepository records retry and cancel`() = testScope.runTest {
        // ACT
        fakePendingRepo.retryMessage("msg-1")
        fakePendingRepo.cancelMessage("msg-2")

        // ASSERT
        assertEquals(1, fakePendingRepo.retryMessageCalls.size)
        assertEquals("msg-1", fakePendingRepo.retryMessageCalls[0])
        assertEquals(1, fakePendingRepo.cancelMessageCalls.size)
        assertEquals("msg-2", fakePendingRepo.cancelMessageCalls[0])
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
