# Phase -1 Implementation: The Safety Net Test

Before touching any code, we create this test to ensure `ChatSendDelegate` works as expected.

## `app/src/test/kotlin/com/bothbubbles/ui/chat/delegates/ChatSendDelegateTest.kt`

```kotlin
package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.fakes.FakeMessageSender
import com.bothbubbles.fakes.FakePendingMessageRepository
import com.bothbubbles.fakes.FakeSocketService
import com.bothbubbles.fakes.FakeSoundManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSendDelegateTest {

    @Test
    fun `sendMessage triggers optimistic insert and calls sender`() = runTest {
        // 1. Setup Fakes
        val fakeSender = FakeMessageSender()
        val fakeRepo = FakePendingMessageRepository()
        val fakeSocket = FakeSocketService()
        val fakeSound = FakeSoundManager()

        // 2. Create Delegate (Old way - before refactor)
        val delegate = ChatSendDelegate(
            pendingMessageRepository = fakeRepo,
            messageSendingService = fakeSender, // Note: This requires Fake to implement the Service type or Interface
            socketService = fakeSocket,
            soundManager = fakeSound
        )
        
        // Initialize (The thing we want to remove later!)
        delegate.initialize("test-chat-guid", this)

        // 3. Act
        delegate.sendMessage("Hello World")

        // 4. Verify
        assertTrue("Message should be sent via sender", fakeSender.sentMessages.any { it.text == "Hello World" })
        assertTrue("Pending message should be added", fakeRepo.messages.any { it.text == "Hello World" })
    }
}
```

**Note:** You might need to create `FakePendingMessageRepository` etc. if they don't exist, but `FakeMessageSender` is already there.
