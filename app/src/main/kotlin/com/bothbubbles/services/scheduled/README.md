# Scheduled Messages

## Purpose

Schedule messages for future delivery. Uses WorkManager for reliable scheduling across device restarts.

## Files

| File | Description |
|------|-------------|
| `ScheduledMessageWorker.kt` | WorkManager worker for sending scheduled messages |

## Architecture

```
Scheduled Message Flow:

User Schedules Message → ScheduledMessageRepository
                      → Store in database
                      → Schedule WorkManager job

At Scheduled Time:
WorkManager → ScheduledMessageWorker
           → Load message from database
           → Send via MessageSender
           → Update status / delete from queue
```

## Required Patterns

### Worker Implementation

```kotlin
@HiltWorker
class ScheduledMessageWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduledMessageDao: ScheduledMessageDao,
    private val messageSender: MessageSender
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val messageId = inputData.getLong(KEY_MESSAGE_ID, -1)
        if (messageId == -1L) return Result.failure()

        val scheduledMessage = scheduledMessageDao.getById(messageId)
            ?: return Result.failure()

        return try {
            messageSender.sendMessage(
                chatGuid = scheduledMessage.chatGuid,
                text = scheduledMessage.text,
                attachments = scheduledMessage.attachments
            )
            scheduledMessageDao.delete(messageId)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry()
            else Result.failure()
        }
    }

    companion object {
        const val KEY_MESSAGE_ID = "message_id"

        fun schedule(context: Context, message: ScheduledMessageEntity) {
            val delay = message.scheduledTime - System.currentTimeMillis()
            val request = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_MESSAGE_ID to message.id))
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "scheduled_${message.id}",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }
}
```

## Best Practices

1. Use WorkManager for reliability (survives restarts)
2. Store scheduled messages in database (not just WorkManager)
3. Allow cancellation before send time
4. Show pending scheduled messages in UI
5. Handle timezone changes gracefully
