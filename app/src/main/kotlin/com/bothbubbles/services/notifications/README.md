# Notification Service

## Purpose

Handle all notification operations including message notifications, notification channels, and bubble support.

## Files

| File | Description |
|------|-------------|
| `BubbleMetadataHelper.kt` | Build bubble metadata for conversation bubbles |
| `NotificationBuilder.kt` | Construct notification objects |
| `NotificationChannelManager.kt` | Manage notification channels |
| `NotificationReceiver.kt` | Handle notification actions (reply, mark read) |
| `NotificationService.kt` | Main notification service implementation |
| `Notifier.kt` | Interface for notification operations |

## Architecture

```
Notification Flow:

IncomingMessage → NotificationService
               → Check if chat is active (suppress if yes)
               → Check chat notification settings
               → NotificationBuilder.build()
               → BubbleMetadataHelper (if bubbles enabled)
               → NotificationManager.notify()

Notification Actions:

User Action → NotificationReceiver
           → Reply: Send message via MessageSender
           → Mark Read: Update message status
           → Cancel notification
```

## Required Patterns

### Notifier Interface

```kotlin
interface Notifier {
    suspend fun showMessageNotification(message: Message, chat: Chat)
    fun cancelNotification(chatGuid: String)
    fun cancelAllNotifications()
}
```

### Notification Building

```kotlin
class NotificationBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bubbleMetadataHelper: BubbleMetadataHelper
) {
    fun buildMessageNotification(
        message: Message,
        chat: Chat,
        contact: ContactInfo?
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contact?.displayName ?: chat.displayName)
            .setContentText(message.text)
            .setStyle(messagingStyle)
            .addAction(replyAction)
            .addAction(markReadAction)
            .setBubbleMetadata(bubbleMetadataHelper.build(chat))
            .build()
    }
}
```

### Notification Receiver

```kotlin
class NotificationReceiver : BroadcastReceiver() {
    @Inject lateinit var messageSender: MessageSender

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REPLY -> handleReply(intent)
            ACTION_MARK_READ -> handleMarkRead(intent)
        }
    }

    private fun handleReply(intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence(KEY_REPLY)?.toString()
        val chatGuid = intent.getStringExtra(EXTRA_CHAT_GUID)

        if (replyText != null && chatGuid != null) {
            scope.launch {
                messageSender.sendMessage(chatGuid, replyText)
            }
        }
    }
}
```

## Best Practices

1. Suppress notifications for active chat
2. Respect per-chat notification settings
3. Group notifications by conversation
4. Support direct reply from notification
5. Use messaging style for conversations
6. Support Android bubbles for quick access
