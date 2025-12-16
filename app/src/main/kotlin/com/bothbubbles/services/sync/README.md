# Sync Service

## Purpose

Data synchronization between the app and BlueBubbles server. Handles initial sync, incremental sync, and background sync.

## Files

| File | Description |
|------|-------------|
| `BackgroundSyncWorker.kt` | Periodic background sync via WorkManager |
| `ChatSyncHelper.kt` | Chat synchronization utilities |
| `CounterpartSyncService.kt` | Sync iMessage/SMS counterpart chats |
| `MessageSyncHelper.kt` | Message synchronization utilities |
| `SyncBackoffStrategy.kt` | Exponential backoff for failed syncs |
| `SyncOperations.kt` | Core sync operations |
| `SyncProgressTracker.kt` | Track sync progress for UI |
| `SyncRangeTracker.kt` | Track synced date ranges per chat |
| `SyncService.kt` | Main sync orchestration service |
| `SyncState.kt` | Sync state models |

## Architecture

```
Sync Mechanisms:

1. PRIMARY: Socket.IO Push (real-time)
   └── SocketEventHandler → MessageEventHandler

2. SECONDARY: FCM Push (backup)
   └── BothBubblesFirebaseService → FcmMessageHandler

3. FALLBACK: Adaptive Polling (ChatViewModel)
   └── Poll every 2s when socket quiet for >5s

4. FALLBACK: Foreground Resume Sync
   └── Sync when app returns from background

5. FALLBACK: Background Sync Worker
   └── Every 15 minutes via WorkManager
```

## Required Patterns

### Background Sync Worker

```kotlin
@HiltWorker
class BackgroundSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncService: SyncService,
    private val notificationService: NotificationService
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val newMessages = syncService.syncRecentChats(
                chatLimit = 10,
                messageLimit = 20
            )

            // Show notifications for new messages
            newMessages.forEach { message ->
                notificationService.showMessageNotification(message)
            }

            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry()
            else Result.failure()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(
                15, TimeUnit.MINUTES
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "background_sync",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }
    }
}
```

### Sync Range Tracking

```kotlin
class SyncRangeTracker @Inject constructor(
    private val syncRangeDao: SyncRangeDao
) {
    suspend fun getSyncedRange(chatGuid: String): SyncRange? {
        return syncRangeDao.getRange(chatGuid)?.let {
            SyncRange(it.oldestDate, it.newestDate)
        }
    }

    suspend fun updateSyncedRange(chatGuid: String, oldestDate: Long, newestDate: Long) {
        syncRangeDao.upsert(SyncRangeEntity(
            chatGuid = chatGuid,
            oldestDate = oldestDate,
            newestDate = newestDate
        ))
    }
}
```

### Progress Tracking

```kotlin
class SyncProgressTracker @Inject constructor() {
    private val _progress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    fun startSync(totalChats: Int) {
        _progress.value = SyncProgress.InProgress(0, totalChats)
    }

    fun updateProgress(currentChat: Int) {
        val current = _progress.value
        if (current is SyncProgress.InProgress) {
            _progress.value = current.copy(current = currentChat)
        }
    }

    fun complete() {
        _progress.value = SyncProgress.Complete
    }
}
```

## Best Practices

1. Use multiple sync mechanisms for reliability
2. Track sync ranges to avoid re-syncing
3. Respect battery and network constraints
4. Show progress for long syncs
5. Implement exponential backoff for failures
6. Sync recent chats first for better UX
