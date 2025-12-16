# Auto Responder Service

## Purpose

Automatically responds to incoming messages when the user is unavailable (driving, in meetings, sleeping, etc.).

## Files

| File | Description |
|------|-------------|
| `AutoResponderService.kt` | Core auto-responder logic and scheduling |

## Architecture

```
Auto Responder Flow:

IncomingMessage → AutoResponderService
                  ├── Check if enabled
                  ├── Check schedule (time-based)
                  ├── Check sender (not already responded)
                  ├── Check chat type (DM only, or include groups)
                  └── Send auto-reply message
```

## Required Patterns

### Auto-Response Logic

```kotlin
class AutoResponderService @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val messageSender: MessageSender,
    private val autoRespondedSenderDao: AutoRespondedSenderDao
) {
    suspend fun handleIncomingMessage(message: Message, chat: Chat) {
        if (!shouldAutoRespond(message, chat)) return

        val template = getResponseTemplate()
        messageSender.sendMessage(chat.guid, template)

        // Track that we responded to this sender (avoid spam)
        autoRespondedSenderDao.markResponded(message.senderHandle)
    }
}
```

### Schedule-Based Activation

```kotlin
// Check if current time is within auto-respond schedule
fun isWithinSchedule(): Boolean {
    val now = LocalTime.now()
    val start = settings.autoRespondStartTime
    val end = settings.autoRespondEndTime
    return now.isAfter(start) && now.isBefore(end)
}
```

## Best Practices

1. Rate limit responses per sender (don't spam)
2. Respect chat type preferences (DM vs group)
3. Allow schedule customization
4. Store response templates
5. Track responded senders to avoid duplicates
