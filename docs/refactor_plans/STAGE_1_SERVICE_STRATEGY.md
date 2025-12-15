# Refactor Plan: MessageSendingService Strategy Pattern

**Target File:** `app/src/main/kotlin/com/bothbubbles/services/messaging/MessageSendingService.kt`
**Goal:** Implement the Strategy Pattern to handle different sending methods (SMS vs iMessage).

## Design Philosophy: Strategy Pattern & DI
- **Interface Segregation:** Define a clear contract for "Sending a Message".
- **Open/Closed Principle:** New sending methods (e.g., "Scheduled") can be added without modifying the core service.
- **Dependency Injection:** Use Hilt to bind strategies into a Map or Set, allowing dynamic selection.

## Instructions

### 1. Define the Interface
Create: `app/src/main/kotlin/com/bothbubbles/services/messaging/sender/MessageSenderStrategy.kt`
```kotlin
interface MessageSenderStrategy {
    // Returns true if this strategy can handle the given message/chat type
    fun canHandle(message: MessageEntity, chat: ChatEntity): Boolean
    
    // Executes the send operation
    suspend fun send(message: MessageEntity, options: SendOptions): SendResult
}
```

### 2. Implement Strategies
**SmsSenderStrategy.kt:**
- Move `SmsManager` logic here.
- `canHandle` returns true if `chat.isSms`.

**IMessageSenderStrategy.kt:**
- Move API/Socket sending logic here.
- `canHandle` returns true if `!chat.isSms`.

### 3. Configure Hilt Module
Create: `app/src/main/kotlin/com/bothbubbles/di/MessageSenderModule.kt`
```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class MessageSenderModule {
    @Binds
    @IntoSet
    abstract fun bindSmsStrategy(impl: SmsSenderStrategy): MessageSenderStrategy

    @Binds
    @IntoSet
    abstract fun bindIMessageStrategy(impl: IMessageSenderStrategy): MessageSenderStrategy
}
```

### 4. Update Service
1.  Inject `Set<MessageSenderStrategy>` into `MessageSendingService`.
2.  In `sendMessage`, iterate through the set to find the matching strategy:
    ```kotlin
    val strategy = strategies.firstOrNull { it.canHandle(message, chat) }
        ?: throw IllegalStateException("No strategy found for message")
    strategy.send(message, options)
    ```

## Verification
- **Unit Tests:** Mock the strategies and verify the Service selects the correct one.
- **Integration:** Send an SMS and an iMessage to verify end-to-end flow.
