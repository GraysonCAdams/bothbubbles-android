# Message Enhancement Features Plan

This document outlines the implementation plan for four message enhancement features.

---

## Summary

| Feature | Complexity | DB Changes | New Files | Effort |
|---------|------------|------------|-----------|--------|
| Message Pinning/Starring | Medium | 1 column + index | ~8 files | Medium |
| Message Reminders | High | New table | ~12 files | High |
| Audio Transcription | Medium | 1 column | ~6 files | Medium |
| Draft Messages | **Already Done** | None | None | None |

---

## 1. Draft Messages - VALIDATION

### Current Status: FULLY IMPLEMENTED

Draft messages are already fully supported via `ChatComposerDelegate`:

**Implementation Details:**
- **Storage**: `UnifiedChatEntity.textFieldText` column (added in migration 2→3)
- **Loading**: `loadDraftFromChat()` restores draft on chat open
- **Saving**: `persistDraft()` with 500ms debounce during typing
- **Immediate Save**: `saveDraftImmediately()` called on chat leave/pause
- **Clearing**: `clearDraftFromDatabase()` after successful send

**Key Files:**
- [ChatComposerDelegate.kt](app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatComposerDelegate.kt) - Draft state management
- [ChatRepository.kt](app/src/main/kotlin/com/bothbubbles/data/repository/ChatRepository.kt#L470-L483) - `getDraftText()` / `updateDraftText()`
- [UnifiedChatEntity.kt](core/model/src/main/kotlin/com/bothbubbles/core/model/entity/UnifiedChatEntity.kt) - `textFieldText` field

**Verified Behavior:**
- Drafts persist per unified chat (shared across merged iMessage/SMS)
- 500ms debounce prevents excessive writes
- Immediate save on leaving chat preserves unsent text
- Draft cleared after message sent successfully

**Conclusion:** No work needed.

---

## 2. Message Pinning/Starring/Bookmarking

### Overview

Allow users to pin important messages within a chat for quick access, and star/bookmark messages for later reference across all chats.

**UX Design:**
- **Pin**: Per-chat action - pinned messages shown in expandable header within chat
- **Star/Bookmark**: Global action - starred messages accessible from dedicated screen
- **Existing Column**: `isBookmarked` already exists in `MessageEntity` but is unused - repurpose for starring

### Database Changes

**Add to `MessageEntity`:**
```kotlin
@ColumnInfo(name = "is_pinned", defaultValue = "0")
val isPinned: Boolean = false

@ColumnInfo(name = "pinned_at")
val pinnedAt: Long? = null  // For ordering pinned messages by pin time
```

**Migration (version N → N+1):**
```kotlin
val MIGRATION_XX_YY = object : Migration(XX, YY) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN pinned_at INTEGER DEFAULT NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_is_pinned ON messages(is_pinned)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_is_bookmarked ON messages(is_bookmarked)")
    }
}
```

### DAO Changes

**Add to `MessageDao.kt`:**
```kotlin
// Pin operations
@Query("UPDATE messages SET is_pinned = :isPinned, pinned_at = :pinnedAt WHERE guid = :guid")
suspend fun updatePinnedStatus(guid: String, isPinned: Boolean, pinnedAt: Long?)

@Query("SELECT * FROM messages WHERE chat_guid = :chatGuid AND is_pinned = 1 ORDER BY pinned_at DESC")
fun getPinnedMessagesForChat(chatGuid: String): Flow<List<MessageEntity>>

@Query("SELECT COUNT(*) FROM messages WHERE chat_guid = :chatGuid AND is_pinned = 1")
fun getPinnedCountForChat(chatGuid: String): Flow<Int>

// Bookmark/star operations
@Query("UPDATE messages SET is_bookmarked = :isBookmarked WHERE guid = :guid")
suspend fun updateBookmarkStatus(guid: String, isBookmarked: Boolean)

@Query("SELECT * FROM messages WHERE is_bookmarked = 1 ORDER BY date_created DESC")
fun getBookmarkedMessages(): Flow<List<MessageEntity>>

@Query("SELECT * FROM messages WHERE is_bookmarked = 1 ORDER BY date_created DESC LIMIT :limit OFFSET :offset")
suspend fun getBookmarkedMessagesPaged(limit: Int, offset: Int): List<MessageEntity>
```

### Repository Layer

**Add to `MessageRepository.kt`:**
```kotlin
// Pinning
suspend fun pinMessage(guid: String): Result<Unit>
suspend fun unpinMessage(guid: String): Result<Unit>
fun getPinnedMessages(chatGuid: String): Flow<List<MessageEntity>>

// Starring/Bookmarking
suspend fun starMessage(guid: String): Result<Unit>
suspend fun unstarMessage(guid: String): Result<Unit>
fun getStarredMessages(): Flow<List<MessageEntity>>
```

### UI Components

**1. Pinned Messages Header** (`ui/chat/components/PinnedMessagesBar.kt`):
```kotlin
@Composable
fun PinnedMessagesBar(
    pinnedMessages: ImmutableList<MessageWithSender>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onMessageClick: (MessageEntity) -> Unit,
    onUnpin: (String) -> Unit,
    modifier: Modifier = Modifier
)
```
- Collapsed: Shows count badge "📌 3 pinned messages" - tap to expand
- Expanded: Horizontal scrollable row of pinned message previews
- Long-press on pinned message → unpin option

**2. Context Menu Additions** (`ui/components/message/MessageActionMenu.kt`):
Add to existing menu:
```kotlin
// After Forward action
ActionMenuItem(
    icon = if (isPinned) Icons.Default.PushPinOff else Icons.Default.PushPin,
    label = if (isPinned) "Unpin" else "Pin",
    onClick = onTogglePin
)
ActionMenuItem(
    icon = if (isStarred) Icons.Default.Star else Icons.Default.StarOutline,
    label = if (isStarred) "Unstar" else "Star",
    onClick = onToggleStar
)
```

**3. Starred Messages Screen** (`ui/starred/StarredMessagesScreen.kt`):
- New screen accessible from Settings or Conversations overflow menu
- Lists all starred messages with chat context
- Tap message → navigate to that message in chat
- Long-press → unstar option

### ViewModel Changes

**Add to `ChatViewModel.kt` (via new delegate `ChatPinDelegate.kt`):**
```kotlin
class ChatPinDelegate @AssistedInject constructor(
    private val messageRepository: MessageRepository,
    @Assisted private val chatGuid: String
) {
    val pinnedMessages: StateFlow<ImmutableList<MessageWithSender>>
    val pinnedExpanded: StateFlow<Boolean>

    fun togglePin(messageGuid: String)
    fun toggleStar(messageGuid: String)
    fun togglePinnedExpanded()
}
```

### Files to Create/Modify

| File | Action | Description |
|------|--------|-------------|
| `core/model/.../MessageEntity.kt` | Modify | Add `isPinned`, `pinnedAt` columns |
| `DatabaseMigrations.kt` | Modify | Add migration for new columns + indexes |
| `MessageDao.kt` | Modify | Add pin/bookmark queries |
| `MessageRepository.kt` | Modify | Add pin/star methods |
| `ui/chat/delegates/ChatPinDelegate.kt` | Create | Pin/star state management |
| `ui/chat/components/PinnedMessagesBar.kt` | Create | Pinned messages UI |
| `ui/components/message/MessageActionMenu.kt` | Modify | Add pin/star actions |
| `ui/starred/StarredMessagesScreen.kt` | Create | Starred messages list |
| `ui/starred/StarredMessagesViewModel.kt` | Create | Starred messages VM |
| `NavHost.kt` | Modify | Add starred messages route |

---

## 3. Message Reminders

### Overview

Allow users to set reminders on messages that trigger Android system notifications at a specified time.

**UX Flow:**
1. Long-press message → "Remind me" option
2. Bottom sheet with time picker (presets: 1 hour, Tonight, Tomorrow, Pick date/time)
3. Reminder saved to database
4. At reminder time: System notification with message preview → tap to open chat at that message

### Database Schema

**New Entity: `MessageReminderEntity`**
```kotlin
@Entity(
    tableName = "message_reminders",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["guid"],
            childColumns = ["message_guid"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["message_guid"], unique = true),
        Index(value = ["reminder_time"]),
        Index(value = ["is_triggered"])
    ]
)
data class MessageReminderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "message_guid")
    val messageGuid: String,

    @ColumnInfo(name = "chat_guid")
    val chatGuid: String,

    @ColumnInfo(name = "reminder_time")
    val reminderTime: Long,  // Unix timestamp

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_triggered")
    val isTriggered: Boolean = false,

    @ColumnInfo(name = "message_preview")
    val messagePreview: String? = null,  // Cached for notification

    @ColumnInfo(name = "sender_name")
    val senderName: String? = null  // Cached for notification
)
```

**Migration:**
```kotlin
db.execSQL("""
    CREATE TABLE IF NOT EXISTS message_reminders (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        message_guid TEXT NOT NULL,
        chat_guid TEXT NOT NULL,
        reminder_time INTEGER NOT NULL,
        created_at INTEGER NOT NULL,
        is_triggered INTEGER NOT NULL DEFAULT 0,
        message_preview TEXT,
        sender_name TEXT,
        FOREIGN KEY(message_guid) REFERENCES messages(guid) ON DELETE CASCADE
    )
""")
db.execSQL("CREATE INDEX IF NOT EXISTS index_message_reminders_message_guid ON message_reminders(message_guid)")
db.execSQL("CREATE INDEX IF NOT EXISTS index_message_reminders_reminder_time ON message_reminders(reminder_time)")
db.execSQL("CREATE INDEX IF NOT EXISTS index_message_reminders_is_triggered ON message_reminders(is_triggered)")
```

### DAO

**Create `MessageReminderDao.kt`:**
```kotlin
@Dao
interface MessageReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: MessageReminderEntity): Long

    @Query("SELECT * FROM message_reminders WHERE message_guid = :messageGuid")
    suspend fun getReminderForMessage(messageGuid: String): MessageReminderEntity?

    @Query("SELECT * FROM message_reminders WHERE message_guid = :messageGuid")
    fun observeReminderForMessage(messageGuid: String): Flow<MessageReminderEntity?>

    @Query("SELECT * FROM message_reminders WHERE is_triggered = 0 ORDER BY reminder_time ASC")
    fun getPendingReminders(): Flow<List<MessageReminderEntity>>

    @Query("SELECT * FROM message_reminders WHERE reminder_time <= :currentTime AND is_triggered = 0")
    suspend fun getDueReminders(currentTime: Long): List<MessageReminderEntity>

    @Query("UPDATE message_reminders SET is_triggered = 1 WHERE id = :id")
    suspend fun markAsTriggered(id: Long)

    @Query("DELETE FROM message_reminders WHERE message_guid = :messageGuid")
    suspend fun deleteReminder(messageGuid: String)

    @Query("DELETE FROM message_reminders WHERE is_triggered = 1 AND reminder_time < :cutoffTime")
    suspend fun deleteOldTriggeredReminders(cutoffTime: Long)
}
```

### Service Layer

**Create `MessageReminderService.kt`:**
```kotlin
@Singleton
class MessageReminderService @Inject constructor(
    private val reminderDao: MessageReminderDao,
    private val messageDao: MessageDao,
    private val notificationService: NotificationService,
    private val workManager: WorkManager,
    @ApplicationContext private val context: Context
) {
    suspend fun setReminder(
        messageGuid: String,
        chatGuid: String,
        reminderTime: Long,
        messagePreview: String?,
        senderName: String?
    ): Result<Long>

    suspend fun cancelReminder(messageGuid: String): Result<Unit>

    suspend fun processReminder(reminderId: Long)

    fun scheduleReminderWork(reminderId: Long, triggerTime: Long)
}
```

**Create `MessageReminderWorker.kt`:**
```kotlin
@HiltWorker
class MessageReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reminderService: MessageReminderService
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val reminderId = inputData.getLong(KEY_REMINDER_ID, -1)
        if (reminderId == -1L) return Result.failure()

        reminderService.processReminder(reminderId)
        return Result.success()
    }

    companion object {
        const val KEY_REMINDER_ID = "reminder_id"
    }
}
```

### UI Components

**1. Reminder Time Picker Sheet** (`ui/components/dialogs/ReminderTimeSheet.kt`):
```kotlin
@Composable
fun ReminderTimeSheet(
    onDismiss: () -> Unit,
    onTimeSelected: (Long) -> Unit
) {
    // Presets:
    // - In 1 hour
    // - Tonight at 8pm (or tomorrow if past 8pm)
    // - Tomorrow at 9am
    // - Pick date & time (opens DateTimePicker)
}
```

**2. Message Reminder Indicator** (in message bubble):
- Small clock icon on messages with active reminders
- Tap icon → shows reminder time, option to cancel

**3. Pending Reminders Screen** (optional, in Settings):
- List all pending reminders
- Swipe to cancel

### Context Menu Integration

**Add to `MessageActionMenu.kt`:**
```kotlin
ActionMenuItem(
    icon = if (hasReminder) Icons.Default.AlarmOff else Icons.Default.AlarmAdd,
    label = if (hasReminder) "Cancel Reminder" else "Remind Me",
    onClick = onReminderClick
)
```

### Notification Integration

**Add to `NotificationService.kt`:**
```kotlin
fun showReminderNotification(
    messageGuid: String,
    chatGuid: String,
    messagePreview: String,
    senderName: String?
) {
    // Create notification with:
    // - Title: "Reminder: {senderName}" or "Message Reminder"
    // - Body: Message preview (truncated)
    // - Tap action: Deep link to chat at specific message
    // - Dismiss action: Mark reminder as seen
}
```

### Files to Create/Modify

| File | Action | Description |
|------|--------|-------------|
| `core/model/.../MessageReminderEntity.kt` | Create | Reminder entity |
| `MessageReminderDao.kt` | Create | Reminder DAO |
| `DatabaseMigrations.kt` | Modify | Add reminders table |
| `BothBubblesDatabase.kt` | Modify | Add entity + DAO |
| `DatabaseModule.kt` | Modify | Provide DAO |
| `MessageReminderService.kt` | Create | Reminder business logic |
| `MessageReminderWorker.kt` | Create | WorkManager job |
| `ui/components/dialogs/ReminderTimeSheet.kt` | Create | Time picker UI |
| `ui/components/message/MessageActionMenu.kt` | Modify | Add remind option |
| `NotificationService.kt` | Modify | Add reminder notifications |
| `ChatViewModel.kt` | Modify | Add reminder methods |

---

## 4. Audio Transcription

### Overview

Transcribe voice memos/audio messages using Android's on-device Speech Recognition API (no network calls, privacy-preserving).

**UX Flow:**
1. Voice message received → "Transcribe" button shown
2. Tap transcribe → On-device processing with progress indicator
3. Transcription saved and displayed below audio player
4. Cached in database for future views

### Android Speech Recognition

**API**: `android.speech.SpeechRecognizer` with offline mode

```kotlin
val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)  // Force offline
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
}
```

**Limitations:**
- Requires downloading offline speech model (prompted on first use)
- Not all languages supported offline
- Audio must be converted to stream format (SpeechRecognizer expects live audio)

**Alternative**: Use `MediaPlayer` to play audio internally while `SpeechRecognizer` listens (hacky but works)

**Better Alternative**: Use ML Kit's on-device speech recognition if available, or process audio file directly with a local model.

### Database Changes

**Add to `AttachmentEntity` or use `MessageEntity.metadata`:**

Option A - New column on AttachmentEntity:
```kotlin
@ColumnInfo(name = "transcription")
val transcription: String? = null

@ColumnInfo(name = "transcription_status")
val transcriptionStatus: String? = null  // null, "pending", "complete", "failed"
```

Option B - Store in MessageEntity.metadata JSON (simpler, no migration):
```kotlin
data class MessageMetadata(
    val transcription: String? = null,
    val transcriptionStatus: TranscriptionStatus? = null
)
```

**Recommendation**: Option A (explicit columns) for better queryability

**Migration:**
```kotlin
db.execSQL("ALTER TABLE attachments ADD COLUMN transcription TEXT DEFAULT NULL")
db.execSQL("ALTER TABLE attachments ADD COLUMN transcription_status TEXT DEFAULT NULL")
```

### Service Layer

**Create `AudioTranscriptionService.kt`:**
```kotlin
@Singleton
class AudioTranscriptionService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attachmentDao: AttachmentDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    sealed class TranscriptionResult {
        data class Success(val text: String) : TranscriptionResult()
        data class Error(val message: String) : TranscriptionResult()
        object NoOfflineModel : TranscriptionResult()
    }

    suspend fun transcribeAudio(attachmentGuid: String, audioUri: Uri): TranscriptionResult

    suspend fun isOfflineModelAvailable(): Boolean

    fun promptDownloadOfflineModel()
}
```

**Implementation Approach:**

Since `SpeechRecognizer` expects live audio stream, we need to:
1. Use `MediaExtractor` + `MediaCodec` to decode audio to PCM
2. Feed PCM data to SpeechRecognizer via audio routing
3. Or use a different approach with ML Kit / local Whisper model

**Simpler Alternative - Use Intent:**
```kotlin
// Launch system speech recognizer with audio file
// This may not work on all devices as it's designed for live input
```

**Best Approach - Whisper.cpp (Local):**
- Bundle lightweight Whisper tiny model (~40MB)
- Use whisper.cpp Android bindings
- Process audio file directly
- Works offline, high accuracy

### UI Components

**Modify `AudioAttachment.kt`:**
```kotlin
@Composable
fun AudioAttachment(
    attachment: AttachmentEntity,
    transcription: String?,
    transcriptionStatus: TranscriptionStatus?,
    onPlayClick: () -> Unit,
    onTranscribeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        // Existing audio player UI
        AudioPlayerRow(...)

        // Transcription section
        when (transcriptionStatus) {
            null -> {
                TextButton(onClick = onTranscribeClick) {
                    Icon(Icons.Default.Subtitles, null)
                    Text("Transcribe")
                }
            }
            TranscriptionStatus.PENDING -> {
                Row {
                    CircularProgressIndicator(Modifier.size(16.dp))
                    Text("Transcribing...", style = MaterialTheme.typography.bodySmall)
                }
            }
            TranscriptionStatus.COMPLETE -> {
                Text(
                    text = transcription ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TranscriptionStatus.FAILED -> {
                TextButton(onClick = onTranscribeClick) {
                    Text("Retry transcription")
                }
            }
        }
    }
}
```

### ViewModel Integration

**Add to `ChatViewModel.kt`:**
```kotlin
fun transcribeVoiceMessage(attachmentGuid: String) {
    viewModelScope.launch {
        attachmentRepository.updateTranscriptionStatus(attachmentGuid, TranscriptionStatus.PENDING)

        val result = transcriptionService.transcribeAudio(
            attachmentGuid = attachmentGuid,
            audioUri = attachmentRepository.getLocalUri(attachmentGuid)
        )

        when (result) {
            is TranscriptionResult.Success -> {
                attachmentRepository.saveTranscription(attachmentGuid, result.text)
            }
            is TranscriptionResult.Error -> {
                attachmentRepository.updateTranscriptionStatus(attachmentGuid, TranscriptionStatus.FAILED)
            }
            is TranscriptionResult.NoOfflineModel -> {
                // Show dialog to download offline model
            }
        }
    }
}
```

### Implementation Options Comparison

| Approach | Pros | Cons |
|----------|------|------|
| **SpeechRecognizer** | Built-in, no extra deps | Designed for live audio, hacky for files |
| **ML Kit Speech** | Google-supported | May require Play Services |
| **Whisper.cpp** | Best accuracy, truly offline | Adds ~40MB to APK, native code |
| **Vosk** | Open source, offline | Additional setup, model downloads |

**Recommendation**: Start with SpeechRecognizer (built-in), fall back to prompting user if offline model unavailable. Consider Whisper.cpp in future for better accuracy.

### Files to Create/Modify

| File | Action | Description |
|------|--------|-------------|
| `core/model/.../AttachmentEntity.kt` | Modify | Add transcription columns |
| `DatabaseMigrations.kt` | Modify | Add migration |
| `AttachmentDao.kt` | Modify | Add transcription queries |
| `AttachmentRepository.kt` | Modify | Add transcription methods |
| `AudioTranscriptionService.kt` | Create | Transcription logic |
| `ui/components/attachment/AudioAttachment.kt` | Modify | Add transcription UI |
| `ChatViewModel.kt` | Modify | Add transcription trigger |

---

## Implementation Order

**Recommended sequence:**

1. **Message Pinning/Starring** (Medium effort)
   - Leverages existing `isBookmarked` column
   - Self-contained feature
   - High user value

2. **Audio Transcription** (Medium effort)
   - Depends on Android APIs
   - May need iteration on approach
   - Good for voice-heavy users

3. **Message Reminders** (High effort)
   - New table + WorkManager integration
   - More complex notification handling
   - Can be done incrementally

---

## Testing Considerations

### Migration Testing
- Test fresh install with new schema
- Test upgrade from previous version
- Verify indexes created correctly

### Pin/Star Testing
- Pin message → verify appears in header
- Star message → verify appears in starred screen
- Unpin/unstar → verify removed
- Pin limit (consider max 25 pins per chat?)

### Reminder Testing
- Set reminder → verify notification fires at correct time
- Cancel reminder → verify no notification
- App killed → verify WorkManager still triggers
- Device reboot → verify reminders persist (WorkManager handles this)

### Transcription Testing
- Test with various audio lengths (5s, 30s, 2min)
- Test offline model availability
- Test with poor audio quality
- Test cancellation during transcription
