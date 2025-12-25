# Calendar Events in Chat - Implementation Plan

## Overview

Display calendar events as system-style indicators in chat conversations (like group member changes). These show what contacts with synced calendars are doing, rendered inline with messages.

**Format examples:**
- "Liz now has event WFH - All Day"
- "Liz now has event train home"
- "Liz now has event Coffee with Jessica"

**Key requirements:**
- Do NOT bump conversations to the top
- Do NOT send notifications for these events
- Background sync keeps events updated
- Retroactive loading: On conversation open, if sync hasn't run in 48+ hours for that contact, fetch events from the last 48 hours

---

## Architecture Approach

### Option 1: Virtual Items (Merge at UI Layer) - **RECOMMENDED**

Calendar events are stored separately and merged with messages at display time.

**Pros:**
- Clean separation: Calendar events are not fake "messages"
- No database migration needed for MessageEntity
- Easier to filter/toggle calendar events
- No risk of sync conflicts with real messages

**Cons:**
- More complex merge logic in UI layer
- Need to manage two data sources in message list

### Option 2: Store as Message-like Entities

Insert calendar events as special message types in the messages table.

**Pros:**
- Simpler UI - just render messages
- Automatic ordering with other messages

**Cons:**
- Pollutes message table with non-messages
- Complex deduplication logic
- Risk of appearing in search, exports, etc.

**Decision: Option 1** - Store calendar event occurrences in a separate table and merge at UI layer.

---

## Implementation Steps

### Phase 1: Database Layer

#### 1.1 Create CalendarEventOccurrenceEntity

New entity to track calendar events that have been "logged" into a chat:

```kotlin
// core/model/src/main/kotlin/com/bothbubbles/core/model/entity/CalendarEventOccurrenceEntity.kt

@Entity(
    tableName = "calendar_event_occurrences",
    indices = [
        Index(value = ["chat_address"]),
        Index(value = ["event_start_time"]),
        Index(value = ["chat_address", "event_id", "event_start_time"], unique = true)
    ]
)
data class CalendarEventOccurrenceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // The normalized address this event belongs to (for lookup)
    @ColumnInfo(name = "chat_address")
    val chatAddress: String,

    // Calendar event details
    @ColumnInfo(name = "event_id")
    val eventId: Long,

    @ColumnInfo(name = "event_title")
    val eventTitle: String,

    @ColumnInfo(name = "event_start_time")
    val eventStartTime: Long,

    @ColumnInfo(name = "event_end_time")
    val eventEndTime: Long,

    @ColumnInfo(name = "is_all_day")
    val isAllDay: Boolean,

    // Display name of the contact (cached for rendering)
    @ColumnInfo(name = "contact_display_name")
    val contactDisplayName: String,

    // When this occurrence was recorded
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
```

#### 1.2 Create CalendarEventOccurrenceDao

```kotlin
// app/src/main/kotlin/com/bothbubbles/data/local/db/dao/CalendarEventOccurrenceDao.kt

@Dao
interface CalendarEventOccurrenceDao {

    // Get events for a chat, ordered by start time (for display)
    @Query("""
        SELECT * FROM calendar_event_occurrences
        WHERE chat_address = :address
        ORDER BY event_start_time DESC
    """)
    fun observeForAddress(address: String): Flow<List<CalendarEventOccurrenceEntity>>

    // Get events within a time range for a chat
    @Query("""
        SELECT * FROM calendar_event_occurrences
        WHERE chat_address = :address
        AND event_start_time >= :startTime
        AND event_start_time <= :endTime
        ORDER BY event_start_time DESC
    """)
    suspend fun getForAddressInRange(
        address: String,
        startTime: Long,
        endTime: Long
    ): List<CalendarEventOccurrenceEntity>

    // Check if event already exists (for deduplication)
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM calendar_event_occurrences
            WHERE chat_address = :address
            AND event_id = :eventId
            AND event_start_time = :startTime
        )
    """)
    suspend fun exists(address: String, eventId: Long, startTime: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(occurrence: CalendarEventOccurrenceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(occurrences: List<CalendarEventOccurrenceEntity>)

    // Clean up old events (older than X days)
    @Query("DELETE FROM calendar_event_occurrences WHERE event_start_time < :cutoffTime")
    suspend fun deleteOlderThan(cutoffTime: Long)
}
```

#### 1.3 Database Migration

Add migration to `DatabaseMigrations.kt`:

```kotlin
val MIGRATION_62_63 = object : Migration(62, 63) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS calendar_event_occurrences (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                chat_address TEXT NOT NULL,
                event_id INTEGER NOT NULL,
                event_title TEXT NOT NULL,
                event_start_time INTEGER NOT NULL,
                event_end_time INTEGER NOT NULL,
                is_all_day INTEGER NOT NULL DEFAULT 0,
                contact_display_name TEXT NOT NULL,
                created_at INTEGER NOT NULL DEFAULT 0
            )
        """)
        database.execSQL("""
            CREATE INDEX IF NOT EXISTS index_calendar_event_occurrences_chat_address
            ON calendar_event_occurrences (chat_address)
        """)
        database.execSQL("""
            CREATE INDEX IF NOT EXISTS index_calendar_event_occurrences_event_start_time
            ON calendar_event_occurrences (event_start_time)
        """)
        database.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS index_calendar_event_occurrences_unique
            ON calendar_event_occurrences (chat_address, event_id, event_start_time)
        """)
    }
}
```

---

### Phase 2: Repository Layer

#### 2.1 Create CalendarEventOccurrenceRepository

```kotlin
// app/src/main/kotlin/com/bothbubbles/data/repository/CalendarEventOccurrenceRepository.kt

@Singleton
class CalendarEventOccurrenceRepository @Inject constructor(
    private val dao: CalendarEventOccurrenceDao,
    private val calendarProvider: CalendarContentProvider,
    private val calendarAssociationDao: ContactCalendarDao,
    private val handleDao: HandleDao
) {
    companion object {
        private const val TAG = "CalendarEventOccRepo"
        private const val RETROACTIVE_WINDOW_HOURS = 48
        private const val CLEANUP_DAYS = 7
    }

    /**
     * Observe calendar event occurrences for a chat address.
     */
    fun observeForAddress(address: String): Flow<List<CalendarEventOccurrenceEntity>> =
        dao.observeForAddress(address)

    /**
     * Sync calendar events for a contact (background sync).
     * Fetches events from now to +24 hours and records occurrences.
     */
    suspend fun syncEventsForContact(address: String): Result<Int> = runCatching {
        val association = calendarAssociationDao.getByAddress(address) ?: return@runCatching 0
        val displayName = handleDao.getDisplayNameForAddress(address) ?: address.take(10)

        val now = System.currentTimeMillis()
        val events = calendarProvider.getUpcomingEvents(association.calendarId, windowHours = 24)

        var insertedCount = 0
        for (event in events) {
            if (!dao.exists(address, event.id, event.startTime)) {
                dao.insert(
                    CalendarEventOccurrenceEntity(
                        chatAddress = address,
                        eventId = event.id,
                        eventTitle = event.title,
                        eventStartTime = event.startTime,
                        eventEndTime = event.endTime,
                        isAllDay = event.isAllDay,
                        contactDisplayName = displayName
                    )
                )
                insertedCount++
            }
        }

        Timber.tag(TAG).d("Synced $insertedCount new events for $address")
        insertedCount
    }

    /**
     * Retroactively fetch events for the past 48 hours.
     * Called when opening a conversation if sync hasn't run recently.
     */
    suspend fun retroactiveSyncForContact(address: String): Result<Int> = runCatching {
        val association = calendarAssociationDao.getByAddress(address) ?: return@runCatching 0
        val displayName = handleDao.getDisplayNameForAddress(address) ?: address.take(10)

        val now = System.currentTimeMillis()
        val windowStart = now - (RETROACTIVE_WINDOW_HOURS * 60 * 60 * 1000L)

        // Query past events using Instances API
        val events = calendarProvider.getEventsInRange(
            calendarId = association.calendarId,
            startTime = windowStart,
            endTime = now
        )

        var insertedCount = 0
        for (event in events) {
            if (!dao.exists(address, event.id, event.startTime)) {
                dao.insert(
                    CalendarEventOccurrenceEntity(
                        chatAddress = address,
                        eventId = event.id,
                        eventTitle = event.title,
                        eventStartTime = event.startTime,
                        eventEndTime = event.endTime,
                        isAllDay = event.isAllDay,
                        contactDisplayName = displayName
                    )
                )
                insertedCount++
            }
        }

        Timber.tag(TAG).d("Retroactively synced $insertedCount events for $address")
        insertedCount
    }

    /**
     * Clean up old event occurrences.
     */
    suspend fun cleanupOldEvents() {
        val cutoff = System.currentTimeMillis() - (CLEANUP_DAYS * 24 * 60 * 60 * 1000L)
        dao.deleteOlderThan(cutoff)
    }
}
```

#### 2.2 Add getEventsInRange to CalendarContentProvider

```kotlin
// Add to CalendarContentProvider.kt

/**
 * Get events within a specific time range (for retroactive sync).
 */
suspend fun getEventsInRange(
    calendarId: Long,
    startTime: Long,
    endTime: Long
): List<CalendarEvent> = withContext(Dispatchers.IO) {
    if (!permissionStateMonitor.hasCalendarPermission()) {
        return@withContext emptyList()
    }

    val events = mutableListOf<CalendarEvent>()

    try {
        val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startTime.toString())
            .appendPath(endTime.toString())
            .build()

        contentResolver.query(
            instancesUri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_COLOR
            ),
            "${CalendarContract.Instances.CALENDAR_ID} = ?",
            arrayOf(calendarId.toString()),
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                events.add(
                    CalendarEvent(
                        id = cursor.getLong(0),
                        title = cursor.getString(1) ?: "No title",
                        startTime = cursor.getLong(2),
                        endTime = cursor.getLong(3),
                        isAllDay = cursor.getInt(4) == 1,
                        location = cursor.getString(5),
                        color = cursor.getInt(6).takeIf { !cursor.isNull(6) }
                    )
                )
            }
        }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to query events in range for calendar $calendarId")
    }

    events
}
```

---

### Phase 3: Background Sync Worker

#### 3.1 Create CalendarEventSyncWorker

```kotlin
// app/src/main/kotlin/com/bothbubbles/services/calendar/CalendarEventSyncWorker.kt

@HiltWorker
class CalendarEventSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val calendarEventOccurrenceRepository: CalendarEventOccurrenceRepository,
    private val contactCalendarDao: ContactCalendarDao,
    private val syncPreferences: CalendarEventSyncPreferences
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "CalendarEventSyncWorker"
        private const val WORK_NAME = "calendar_event_sync"

        fun schedule(context: Context, intervalMinutes: Int = 30) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<CalendarEventSyncWorker>(
                intervalMinutes.toLong().coerceAtLeast(15),
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15, TimeUnit.MINUTES
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            Timber.tag(TAG).d("Scheduled calendar event sync every $intervalMinutes minutes")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.tag(TAG).d("Cancelled calendar event sync worker")
        }
    }

    override suspend fun doWork(): Result {
        Timber.tag(TAG).d("Starting calendar event sync")

        return try {
            // Get all contacts with calendar associations
            val associations = contactCalendarDao.getAll()

            if (associations.isEmpty()) {
                Timber.tag(TAG).d("No calendar associations, skipping sync")
                return Result.success()
            }

            var totalSynced = 0
            for (association in associations) {
                val result = calendarEventOccurrenceRepository.syncEventsForContact(
                    association.linkedAddress
                )
                result.onSuccess { count ->
                    totalSynced += count
                    // Record last sync time
                    syncPreferences.setLastSyncTime(association.linkedAddress)
                }
            }

            // Cleanup old events
            calendarEventOccurrenceRepository.cleanupOldEvents()

            Timber.tag(TAG).d("Calendar event sync complete: $totalSynced new events")
            Result.success()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Calendar event sync failed")
            Result.retry()
        }
    }
}
```

#### 3.2 Create CalendarEventSyncPreferences

```kotlin
// app/src/main/kotlin/com/bothbubbles/services/calendar/CalendarEventSyncPreferences.kt

@Singleton
class CalendarEventSyncPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("calendar_event_sync", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PREFIX_LAST_SYNC = "last_sync_"
        const val STALE_THRESHOLD_MS = 48 * 60 * 60 * 1000L // 48 hours
    }

    fun getLastSyncTime(address: String): Long {
        return prefs.getLong(KEY_PREFIX_LAST_SYNC + address, 0L)
    }

    fun setLastSyncTime(address: String, time: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_PREFIX_LAST_SYNC + address, time).apply()
    }

    fun needsRetroactiveSync(address: String): Boolean {
        val lastSync = getLastSyncTime(address)
        if (lastSync == 0L) return true
        return (System.currentTimeMillis() - lastSync) >= STALE_THRESHOLD_MS
    }
}
```

---

### Phase 4: UI Integration

#### 4.1 Create CalendarEventItem Model

```kotlin
// app/src/main/kotlin/com/bothbubbles/ui/components/message/CalendarEventItem.kt

/**
 * UI model for a calendar event occurrence displayed in chat.
 */
data class CalendarEventItem(
    val id: Long,
    val contactName: String,
    val eventTitle: String,
    val eventStartTime: Long,
    val isAllDay: Boolean
) {
    /**
     * Formatted display text: "Liz now has event WFH - All Day"
     */
    val displayText: String
        get() {
            val firstName = contactName.split(" ").firstOrNull() ?: contactName
            val suffix = if (isAllDay) " - All Day" else ""
            return "$firstName now has event $eventTitle$suffix"
        }
}
```

#### 4.2 Create CalendarEventIndicator Composable

```kotlin
// app/src/main/kotlin/com/bothbubbles/ui/components/message/CalendarEventIndicator.kt

/**
 * Calendar event indicator - styled like GroupEventIndicator but with calendar icon.
 */
@Composable
fun CalendarEventIndicator(
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Event,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
```

#### 4.3 Create ChatTimelineItem Sealed Class

Unify messages and calendar events into a single timeline:

```kotlin
// app/src/main/kotlin/com/bothbubbles/ui/chat/ChatTimelineItem.kt

/**
 * Represents an item in the chat timeline - either a message or a calendar event.
 */
sealed class ChatTimelineItem {
    abstract val timestamp: Long
    abstract val key: String

    data class Message(val message: MessageUiModel) : ChatTimelineItem() {
        override val timestamp: Long = message.dateCreated
        override val key: String = message.guid
    }

    data class CalendarEvent(val event: CalendarEventItem) : ChatTimelineItem() {
        override val timestamp: Long = event.eventStartTime
        override val key: String = "cal_${event.id}"
    }
}
```

#### 4.4 Merge Logic in ChatViewModel/Delegate

Create a delegate or add to existing delegate to merge calendar events:

```kotlin
// In CursorChatMessageListDelegate or new CalendarEventDelegate

/**
 * Merge messages and calendar events into a single timeline.
 * Calendar events are interleaved based on their start time.
 */
fun mergeTimeline(
    messages: List<MessageUiModel>,
    calendarEvents: List<CalendarEventItem>
): List<ChatTimelineItem> {
    if (calendarEvents.isEmpty()) {
        return messages.map { ChatTimelineItem.Message(it) }
    }

    val timeline = mutableListOf<ChatTimelineItem>()
    val messageIterator = messages.iterator()
    val eventIterator = calendarEvents.sortedByDescending { it.eventStartTime }.iterator()

    var currentMessage = if (messageIterator.hasNext()) messageIterator.next() else null
    var currentEvent = if (eventIterator.hasNext()) eventIterator.next() else null

    while (currentMessage != null || currentEvent != null) {
        when {
            currentMessage == null -> {
                timeline.add(ChatTimelineItem.CalendarEvent(currentEvent!!))
                currentEvent = if (eventIterator.hasNext()) eventIterator.next() else null
            }
            currentEvent == null -> {
                timeline.add(ChatTimelineItem.Message(currentMessage))
                currentMessage = if (messageIterator.hasNext()) messageIterator.next() else null
            }
            currentMessage.dateCreated >= currentEvent.eventStartTime -> {
                timeline.add(ChatTimelineItem.Message(currentMessage))
                currentMessage = if (messageIterator.hasNext()) messageIterator.next() else null
            }
            else -> {
                timeline.add(ChatTimelineItem.CalendarEvent(currentEvent))
                currentEvent = if (eventIterator.hasNext()) eventIterator.next() else null
            }
        }
    }

    return timeline
}
```

#### 4.5 Retroactive Sync on Conversation Open

Add to ChatViewModel initialization:

```kotlin
// In ChatViewModel.init or ChatSyncDelegate

private fun checkAndPerformRetroactiveSync(address: String) {
    viewModelScope.launch {
        val association = contactCalendarRepository.getAssociation(address)
        if (association != null && calendarEventSyncPreferences.needsRetroactiveSync(address)) {
            Timber.d("Performing retroactive calendar sync for $address")
            calendarEventOccurrenceRepository.retroactiveSyncForContact(address)
            calendarEventSyncPreferences.setLastSyncTime(address)
        }
    }
}
```

#### 4.6 Update ChatMessageList to Handle Timeline Items

Modify the LazyColumn to render both message and calendar event items:

```kotlin
// In ChatMessageList.kt - update itemsIndexed block

itemsIndexed(
    items = timelineItems,  // Changed from messages
    key = { _, item -> item.key },
    contentType = { _, item ->
        when (item) {
            is ChatTimelineItem.CalendarEvent -> ContentType.CALENDAR_EVENT
            is ChatTimelineItem.Message -> {
                // Existing message content type logic
            }
        }
    }
) { index, item ->
    when (item) {
        is ChatTimelineItem.CalendarEvent -> {
            CalendarEventIndicator(text = item.event.displayText)
        }
        is ChatTimelineItem.Message -> {
            // Existing MessageListItem code
            MessageListItem(message = item.message, ...)
        }
    }
}
```

---

### Phase 5: Schedule Worker on App Start

#### 5.1 Initialize Worker in BothBubblesApp

```kotlin
// In BothBubblesApp.kt - initializeBackgroundSync() or similar

private fun initializeCalendarEventSync() {
    // Only schedule if there are any calendar associations
    applicationScope.launch {
        val hasAssociations = contactCalendarDao.hasAnyAssociations()
        if (hasAssociations) {
            CalendarEventSyncWorker.schedule(this@BothBubblesApp, intervalMinutes = 30)
        }
    }
}
```

#### 5.2 Start/Stop Worker When Associations Change

```kotlin
// In ContactCalendarRepository

suspend fun setAssociation(...) {
    // ... existing code ...

    // Start worker if this is the first association
    val count = dao.getAssociationCount()
    if (count == 1) {
        CalendarEventSyncWorker.schedule(context, intervalMinutes = 30)
    }
}

suspend fun removeAssociation(address: String) {
    // ... existing code ...

    // Stop worker if no more associations
    val count = dao.getAssociationCount()
    if (count == 0) {
        CalendarEventSyncWorker.cancel(context)
    }
}
```

---

## Files to Create/Modify

### New Files
1. `core/model/src/main/kotlin/com/bothbubbles/core/model/entity/CalendarEventOccurrenceEntity.kt`
2. `app/src/main/kotlin/com/bothbubbles/data/local/db/dao/CalendarEventOccurrenceDao.kt`
3. `app/src/main/kotlin/com/bothbubbles/data/repository/CalendarEventOccurrenceRepository.kt`
4. `app/src/main/kotlin/com/bothbubbles/services/calendar/CalendarEventSyncWorker.kt`
5. `app/src/main/kotlin/com/bothbubbles/services/calendar/CalendarEventSyncPreferences.kt`
6. `app/src/main/kotlin/com/bothbubbles/ui/components/message/CalendarEventIndicator.kt`
7. `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatTimelineItem.kt`
8. `app/schemas/com.bothbubbles.data.local.db.BothBubblesDatabase/63.json`

### Modified Files
1. `app/src/main/kotlin/com/bothbubbles/data/local/db/BothBubblesDatabase.kt` - Add entity and DAO
2. `app/src/main/kotlin/com/bothbubbles/data/local/db/DatabaseMigrations.kt` - Add migration
3. `app/src/main/kotlin/com/bothbubbles/services/calendar/CalendarContentProvider.kt` - Add getEventsInRange
4. `app/src/main/kotlin/com/bothbubbles/di/DatabaseModule.kt` - Provide new DAO
5. `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatMessageList.kt` - Handle timeline items
6. `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatViewModel.kt` - Retroactive sync on open
7. `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/CursorChatMessageListDelegate.kt` - Merge logic
8. `app/src/main/kotlin/com/bothbubbles/BothBubblesApp.kt` - Initialize worker

---

## Testing Considerations

1. **Unit Tests**
   - CalendarEventOccurrenceRepository merge logic
   - Retroactive sync time calculations
   - Display text formatting

2. **Integration Tests**
   - Background worker scheduling/cancellation
   - Database migration

3. **Manual Testing**
   - Verify events appear at correct timeline positions
   - Verify no notifications triggered
   - Verify conversations don't bump to top
   - Test retroactive loading on conversation open
   - Test with all-day vs timed events

---

## Open Questions

1. Should calendar events have an icon? (Proposed: yes, small calendar icon)
2. Should there be a setting to disable calendar event display per-contact?
3. Should calendar events be filterable/hideable globally?
4. How to handle recurring events that span multiple days?
