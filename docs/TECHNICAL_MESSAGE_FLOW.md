# Technical Deep Dive: Message Flow in BothBubbles

This document provides a comprehensive technical explanation of how message sending and receiving works within the BothBubbles Android app, including the complete logic flow and GUI rendering mechanisms.

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Message Sending Flow](#message-sending-flow)
3. [Message Receiving Flow](#message-receiving-flow)
4. [GUI Rendering](#gui-rendering)
5. [Flow Diagrams](#flow-diagrams)

---

## Architecture Overview

BothBubbles uses an **offline-first architecture** with multiple redundant sync mechanisms to ensure reliable message delivery. The key architectural patterns are:

- **Repository Pattern**: Data layer abstracts sources (Room, API, SMS)
- **Service Layer Delegation**: Complex services decomposed into focused handlers
- **WorkManager**: Background job processing with exponential backoff
- **Flow-based Reactivity**: Room database as single source of truth with Flow observers

### Key Components

| Layer          | Components                                                   |
| -------------- | ------------------------------------------------------------ |
| **UI**         | ChatScreen, ChatViewModel, MessageBubble                     |
| **Services**   | MessageSendingService, SocketService, IncomingMessageHandler |
| **Data**       | PendingMessageRepository, MessageRepository, Room DAOs       |
| **Background** | MessageSendWorker, BackgroundSyncWorker                      |

---

## Message Sending Flow

### 1. Entry Point: User Taps Send

**File**: `ui/chat/delegates/ChatSendDelegate.kt`

When a user taps send, the flow begins in `ChatSendDelegate.sendMessage()`:

```kotlin
fun sendMessage(
    text: String,
    attachments: List<PendingAttachmentInput>,
    effectId: String? = null,
    currentSendMode: ChatSendMode,
    isLocalSmsChat: Boolean,
    onClearInput: () -> Unit,
    onDraftCleared: () -> Unit
) {
    val trimmedText = text.trim()
    if (trimmedText.isBlank() && attachments.isEmpty()) return

    // Stop typing indicator immediately
    cancelTypingIndicator()

    scope.launch {
        val replyToGuid = _replyingToGuid.value

        // Clear UI state immediately for responsive feel
        onClearInput()
        _replyingToGuid.value = null
        onDraftCleared()

        // Determine delivery mode based on chat type and user selection
        val deliveryMode = determineDeliveryMode(
            isLocalSmsChat = isLocalSmsChat,
            currentSendMode = currentSendMode,
            hasAttachments = attachments.isNotEmpty()
        )

        // Queue message for offline-first delivery
        pendingMessageRepository.queueMessage(
            chatGuid = chatGuid,
            text = trimmedText,
            replyToGuid = replyToGuid,
            effectId = effectId,
            attachments = attachments,
            deliveryMode = deliveryMode
        )
    }
}
```

**Key Design Decision**: UI clears immediately for responsiveness. The message is queued, not sent directly, enabling offline-first behavior.

### 2. Message Queuing: PendingMessageRepository

**File**: `data/repository/PendingMessageRepository.kt`

Messages are persisted to survive process death:

```kotlin
suspend fun queueMessage(
    chatGuid: String,
    text: String?,
    replyToGuid: String? = null,
    effectId: String? = null,
    attachments: List<PendingAttachmentInput> = emptyList(),
    deliveryMode: MessageDeliveryMode = MessageDeliveryMode.AUTO
): Result<String> = runCatching {
    val localId = "pending-${UUID.randomUUID()}"

    // 1. Create and insert pending message entity
    val pendingMessage = PendingMessageEntity(
        localId = localId,
        chatGuid = chatGuid,
        text = text,
        replyToGuid = replyToGuid,
        effectId = effectId,
        deliveryMode = deliveryMode.name,
        syncStatus = PendingSyncStatus.PENDING.name,
        createdAt = System.currentTimeMillis()
    )
    val messageId = pendingMessageDao.insert(pendingMessage)

    // 2. Persist attachments to app-internal storage
    if (attachments.isNotEmpty()) {
        val persistedAttachments = attachments.mapIndexedNotNull { index, input ->
            attachmentPersistenceManager.persistAttachment(input.uri, "$localId-att-$index")
                .getOrNull()
                ?.let { result ->
                    PendingAttachmentEntity(
                        localId = "$localId-att-$index",
                        pendingMessageId = messageId,
                        persistedPath = result.persistedPath,
                        fileName = result.fileName,
                        mimeType = result.mimeType,
                        fileSize = result.fileSize,
                        orderIndex = index,
                        caption = input.caption
                    )
                }
        }
        pendingAttachmentDao.insertAll(persistedAttachments)
    }

    // 3. Enqueue WorkManager job
    enqueueWorker(messageId, localId)
    localId
}
```

**Attachment Persistence**: Attachments are copied from their original URI (which may be temporary) to app-internal storage (`app-internal/pending_attachments/`). This ensures they survive across app restarts.

### 3. WorkManager Execution: MessageSendWorker

**File**: `services/messaging/MessageSendWorker.kt`

WorkManager executes when network is available:

```kotlin
@HiltWorker
class MessageSendWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val pendingMessageDao: PendingMessageDao,
    private val messageSendingService: MessageSendingService,
    // ...
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val pendingMessageId = inputData.getLong(KEY_PENDING_MESSAGE_ID, -1)
        val pendingMessage = pendingMessageDao.getById(pendingMessageId) ?: return Result.failure()

        // Skip if already sent (idempotency)
        if (pendingMessage.syncStatus == PendingSyncStatus.SENT.name) {
            return Result.success()
        }

        // Update status to SENDING
        pendingMessageDao.updateStatusWithTimestamp(
            pendingMessageId,
            PendingSyncStatus.SENDING.name,
            System.currentTimeMillis()
        )

        return try {
            // Load persisted attachments
            val attachments = pendingAttachmentDao.getForMessage(pendingMessageId)
            val attachmentInputs = attachments.mapNotNull { /* convert to PendingAttachmentInput */ }

            // Send via MessageSendingService
            val result = messageSendingService.sendUnified(
                chatGuid = pendingMessage.chatGuid,
                text = pendingMessage.text ?: "",
                replyToGuid = pendingMessage.replyToGuid,
                attachments = attachmentInputs,
                deliveryMode = MessageDeliveryMode.valueOf(pendingMessage.deliveryMode),
                tempGuid = pendingMessage.localId  // Stable ID for retry idempotency
            )

            if (result.isSuccess) {
                val sentMessage = result.getOrThrow()
                pendingMessageDao.markAsSent(pendingMessageId, sentMessage.guid)
                attachmentPersistenceManager.cleanupAttachments(attachments.map { it.persistedPath })
                Result.success()
            } else {
                handleFailure(pendingMessageId, result.exceptionOrNull())
            }
        } catch (e: Exception) {
            handleFailure(pendingMessageId, e)
        }
    }

    private suspend fun handleFailure(pendingMessageId: Long, error: Throwable?): Result {
        return if (runAttemptCount < MAX_RETRY_COUNT) {  // MAX = 3
            // Retry with exponential backoff (30s initial)
            pendingMessageDao.updateStatusWithError(pendingMessageId, PendingSyncStatus.PENDING.name, error?.message)
            Result.retry()
        } else {
            // Max retries exceeded
            pendingMessageDao.updateStatusWithError(pendingMessageId, PendingSyncStatus.FAILED.name, error?.message)
            Result.failure()
        }
    }
}
```

**Constraints**: Network required, exponential backoff (30s initial), max 3 retries.

### 4. Actual Sending: MessageSendingService

**File**: `services/messaging/MessageSendingService.kt`

Routes to appropriate delivery channel:

```kotlin
override suspend fun sendUnified(
    chatGuid: String,
    text: String,
    attachments: List<PendingAttachmentInput>,
    deliveryMode: MessageDeliveryMode,
    tempGuid: String?
): Result<MessageEntity> {
    val actualMode = when (deliveryMode) {
        MessageDeliveryMode.AUTO -> determineDeliveryMode(chatGuid, attachments.isNotEmpty())
        else -> deliveryMode
    }

    return when (actualMode) {
        MessageDeliveryMode.LOCAL_SMS -> sendLocalSms(chatGuid, text)
        MessageDeliveryMode.LOCAL_MMS -> sendLocalMms(chatGuid, text, attachments.map { it.uri })
        else -> {
            if (attachments.isNotEmpty()) {
                sendIMessageWithAttachments(chatGuid, text, attachments, tempGuid)
            } else {
                sendMessage(chatGuid, text, tempGuid)
            }
        }
    }
}
```

#### iMessage via BlueBubbles API (Text Only)

```kotlin
override suspend fun sendMessage(
    chatGuid: String,
    text: String,
    providedTempGuid: String?
): Result<MessageEntity> = safeCall {
    val tempGuid = providedTempGuid ?: "temp-${UUID.randomUUID()}"

    // Check for retry (idempotency)
    val existingMessage = messageDao.getMessageByGuid(tempGuid)
    if (existingMessage != null && existingMessage.error == 0) {
        return@safeCall existingMessage  // Already sent successfully
    }

    // Create temp message for immediate UI feedback
    if (existingMessage == null) {
        val tempMessage = MessageEntity(
            guid = tempGuid,
            chatGuid = chatGuid,
            text = text,
            dateCreated = System.currentTimeMillis(),
            isFromMe = true,
            messageSource = MessageSource.IMESSAGE.name
        )
        messageDao.insertMessage(tempMessage)
        chatDao.updateLastMessage(chatGuid, System.currentTimeMillis(), text)
    }

    // Send to server
    val response = api.sendMessage(
        SendMessageRequest(chatGuid = chatGuid, message = text, tempGuid = tempGuid)
    )

    val body = response.body()
    if (!response.isSuccessful || body?.status != 200) {
        // Handle failure (optionally auto-retry as SMS)
        messageDao.updateErrorStatus(tempGuid, 1)
        throw MessageError.SendFailed(tempGuid, body?.message ?: "Failed to send")
    }

    // Replace temp GUID with server GUID
    val serverMessage = body.data
    if (serverMessage != null) {
        messageDao.replaceGuid(tempGuid, serverMessage.guid)
        serverMessage.toEntity(chatGuid)
    } else {
        messageDao.updateErrorStatus(tempGuid, 0)
        messageDao.getMessageByGuid(tempGuid)!!
    }
}
```

#### iMessage with Attachments

For attachments, each is uploaded individually with progress tracking:

```kotlin
private suspend fun sendIMessageWithAttachments(...): Result<MessageEntity> = safeCall {
    val tempGuid = providedTempGuid ?: "temp-${UUID.randomUUID()}"

    // Create temp message and attachment records for immediate UI
    val tempMessage = MessageEntity(guid = tempGuid, hasAttachments = true, ...)
    messageDao.insertMessage(tempMessage)

    attachments.forEachIndexed { index, input ->
        val tempAttGuid = "$tempGuid-att-$index"
        attachmentDao.insertAttachment(AttachmentEntity(
            guid = tempAttGuid,
            messageGuid = tempGuid,
            transferState = TransferState.UPLOADING.name,
            localPath = input.uri.toString()
        ))
    }

    // Upload each attachment with compression and progress
    attachments.forEachIndexed { index, input ->
        val tempAttGuid = "$tempGuid-att-$index"

        // Skip if already uploaded (retry case)
        val existing = attachmentDao.getAttachmentByGuid(tempAttGuid)
        if (existing?.transferState == TransferState.UPLOADED.name) return@forEachIndexed

        // Compress if needed (images/videos)
        val bytes = compressIfNeeded(input)

        // Upload with progress tracking
        val progressBody = ProgressRequestBody(bytes) { bytesWritten, total ->
            _uploadProgress.value = UploadProgress(fileName, bytesWritten, total, index, total)
        }

        val response = api.sendAttachment(chatGuid, tempAttGuid, filePart)
        if (response.isSuccessful) {
            attachmentDao.markUploaded(tempAttGuid)
        } else {
            throw error
        }
    }

    // Send text message if present
    if (text.isNotBlank()) {
        api.sendMessage(SendMessageRequest(chatGuid, text, tempGuid))
    }

    // Replace temp GUIDs with server GUIDs
    messageDao.replaceGuid(tempGuid, serverMessage.guid)
    syncOutboundAttachments(serverMessage, tempGuid)
}
```

### 5. State Transitions

```
┌─────────────────┐
│    PENDING      │ ← Initial state after queueMessage()
└────────┬────────┘
         │ WorkManager starts
         ▼
┌─────────────────┐
│    SENDING      │ ← Worker active, network request in progress
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
    ▼         ▼
┌────────┐  ┌─────────────────┐
│  SENT  │  │ PENDING (retry) │ ← Retry count < 3
└────────┘  └────────┬────────┘
                     │ retry count >= 3
                     ▼
               ┌──────────┐
               │  FAILED  │ ← User can retry manually
               └──────────┘
```

---

## Message Receiving Flow

BothBubbles uses **four redundant sync mechanisms** to ensure no messages are missed:

1. **Socket.IO** (Primary) - Real-time push
2. **FCM** (Backup) - Firebase push when socket disconnected
3. **Adaptive Polling** - Polls when socket quiet
4. **Background Sync** - WorkManager every 15 minutes

### 1. Socket.IO: Primary Real-Time Path

**Files**: `services/socket/SocketService.kt`, `services/socket/SocketEventParser.kt`

```kotlin
// SocketEventParser - converts raw JSON to typed events
val onNewMessage = Emitter.Listener { args ->
    val data = args.firstOrNull() as? JSONObject ?: return@Listener
    val message = messageAdapter.fromJson(data.toString()) ?: return@Listener
    val chatGuid = message.chats?.firstOrNull()?.guid ?: ""

    events.tryEmit(SocketEvent.NewMessage(message, chatGuid))

    // Play receive sound immediately
    if (message.isFromMe != true) {
        soundManager.get().playReceiveSound(chatGuid)
    }
}
```

### 2. Event Routing: SocketEventHandler

**File**: `services/socket/SocketEventHandler.kt`

```kotlin
private suspend fun handleEvent(event: SocketEvent) {
    when (event) {
        is SocketEvent.NewMessage ->
            messageEventHandler.handleNewMessage(event, _uiRefreshEvents, applicationScope)
        is SocketEvent.MessageUpdated ->
            messageEventHandler.handleMessageUpdated(event, _uiRefreshEvents)
        // ... other event types
    }
}
```

### 3. Message Processing: MessageEventHandler

**File**: `services/socket/handlers/MessageEventHandler.kt`

```kotlin
suspend fun handleNewMessage(
    event: SocketEvent.NewMessage,
    uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>,
    scope: CoroutineScope
) {
    // 1. Save to database
    val savedMessage = incomingMessageHandler.handleIncomingMessage(event.message, event.chatGuid)

    // 2. Emit UI refresh events
    uiRefreshEvents.tryEmit(UiRefreshEvent.NewMessage(event.chatGuid, savedMessage.guid))
    uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("new_message"))

    // 3. Show notification (with many checks)
    if (!savedMessage.isFromMe) {
        // Check deduplication
        if (!messageDeduplicator.shouldNotifyForMessage(savedMessage.guid)) return

        // Check if user viewing this chat
        if (activeConversationManager.isConversationActive(event.chatGuid)) return

        // Check notification settings
        val chat = chatDao.getChatByGuid(event.chatGuid)
        if (chat?.notificationsEnabled == false || chat?.isSnoozed == true) return

        // Check spam
        val spamResult = spamRepository.evaluateAndMarkSpam(...)
        if (spamResult.isSpam) return

        // Resolve sender info and show notification
        val (senderName, avatarUri) = resolveSenderNameAndAvatar(event.message)
        notificationService.showMessageNotification(...)
    }
}
```

### 4. Database Insertion: IncomingMessageHandler

**File**: `services/messaging/IncomingMessageHandler.kt`

Critical deduplication logic:

```kotlin
override suspend fun handleIncomingMessage(
    messageDto: MessageDto,
    chatGuid: String
): MessageEntity {
    val message = messageDto.toEntity(chatGuid)

    // CRITICAL: Check if message exists BEFORE side effects
    // Prevents duplicate unread count increments from FCM + Socket.IO race
    val existingMessage = messageDao.getMessageByGuid(message.guid)
    if (existingMessage != null) {
        return existingMessage  // Already processed
    }

    // Insert with insertOrIgnore to handle race conditions
    val insertResult = messageDao.insertMessage(message)
    if (insertResult == -1L) {
        // Another thread inserted first
        return messageDao.getMessageByGuid(message.guid) ?: message
    }

    // WE successfully inserted - safe to update chat metadata
    if (!message.isFromMe) {
        chatDao.updateLastMessage(chatGuid, message.dateCreated, message.text)
        chatDao.incrementUnreadCount(chatGuid)  // Atomic increment
    }

    // Sync attachments
    syncIncomingAttachments(messageDto)

    return message
}
```

### 5. FCM Backup Path

**Files**: `services/fcm/BothBubblesFirebaseService.kt`, `services/fcm/FcmMessageHandler.kt`

When Socket.IO is disconnected:

```kotlin
private suspend fun handleNewMessage(data: Map<String, String>) {
    val messageJson = JSONObject(data["data"] ?: return)
    val messageGuid = messageJson.optString("guid")
    val chatGuid = messageJson.optJSONArray("chats")?.optJSONObject(0)?.optString("guid")

    // Skip if socket connected (it will handle)
    if (socketService.isConnected()) return

    // Skip if from me or already notified
    if (messageJson.optBoolean("isFromMe")) return
    if (!messageDeduplicator.shouldNotifyForMessage(messageGuid)) return
    if (activeConversationManager.isConversationActive(chatGuid)) return

    // Show notification
    notificationService.showMessageNotification(...)

    // Trigger socket reconnect to sync full data
    socketService.connect()
}
```

### 6. Adaptive Polling (ChatViewModel)

When socket has been quiet for >5 seconds, polls every 2 seconds:

```kotlin
private fun startAdaptivePolling() {
    pollingJob = viewModelScope.launch {
        while (isActive) {
            delay(POLL_INTERVAL_MS)  // 2000ms

            val timeSinceLastSocket = System.currentTimeMillis() - lastSocketMessageTime
            if (timeSinceLastSocket > SOCKET_QUIET_THRESHOLD_MS) {  // 5000ms
                // Socket quiet - poll for missed messages
                messageRepository.syncMessagesForChat(chatGuid, limit = 10)
            }
        }
    }
}
```

### 7. Background Sync Worker

**File**: `services/sync/BackgroundSyncWorker.kt`

```kotlin
override suspend fun doWork(): Result {
    // Get 10 most recent chats
    val recentChats = chatDao.getRecentChats(limit = 10)

    for (chat in recentChats) {
        if (messageRepository.isLocalSmsChat(chat.guid)) continue

        val newestLocal = messageDao.getLatestMessageForChat(chat.guid)
        val result = messageRepository.syncMessagesForChat(
            chatGuid = chat.guid,
            limit = 20,
            after = newestLocal?.dateCreated
        )

        // Show notifications if app backgrounded and new messages found
        if (!appLifecycleTracker.isAppInForeground && result.getOrNull()?.isNotEmpty() == true) {
            showNotificationsForMessages(chat, result.getOrThrow())
        }
    }
    return Result.success()
}
```

---

## GUI Rendering

### 1. ChatScreen Structure

**File**: `ui/chat/ChatScreen.kt`

Uses a **reversed LazyColumn** where index 0 = newest message:

```kotlin
LazyColumn(
    modifier = Modifier.fillMaxSize(),
    state = listState,
    reverseLayout = true,  // Newest at visual bottom (index 0)
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = bannerPadding, bottom = 8.dp)
) {
    // Typing indicator at index 0 (visual bottom)
    if (showTypingIndicator) {
        item(key = "typing") { TypingIndicator(...) }
    }

    // Messages
    itemsIndexed(
        items = uiState.messages,
        key = { _, message -> message.guid }
    ) { index, message ->
        MessageItem(
            message = message,
            index = index,
            showDeliveryIndicator = index == lastOutgoingIndex,
            groupPosition = computeGroupPosition(index),
            showTimeSeparator = shouldShowTimeSeparator(message, nextMessage)
        )
    }
}
```

### 2. Message Paging Architecture

**Files**: `ui/chat/paging/MessagePagingController.kt`, `ui/chat/paging/RoomMessageDataSource.kt`

Uses **Signal-style BitSet pagination** for sparse loading:

```kotlin
class MessagePagingController {
    private val loadStatus = BitSet()  // O(1) "is position loaded?"
    private val sparseData = mutableMapOf<Int, MessageUiModel>()
    private val guidToPosition = mutableMapOf<String, Int>()

    // Initial load: fetch ~100 newest messages
    fun initialize() {
        val loadEnd = minOf(config.initialLoadSize, totalSize)
        loadRange(0, loadEnd)
    }

    // On scroll: load visible + prefetch distance
    fun onDataNeededAroundIndex(firstVisible: Int, lastVisible: Int) {
        val loadStart = maxOf(0, firstVisible - config.prefetchDistance)
        val loadEnd = minOf(lastVisible + config.prefetchDistance, totalSize)

        // Find gaps and load only missing ranges
        val gaps = MessagePagingHelpers.findGaps(loadStart, loadEnd, loadStatus)
        gaps.forEach { gap -> loadRange(gap.first, gap.last + 1) }
    }
}
```

**Sparse Loading Benefits**:

- Can jump to position 5000 without loading 0-4999
- Efficient for search-jump scenarios
- Memory efficient for long conversations

### 3. Real-Time Updates

**Socket → UI Flow**:

```kotlin
// ChatViewModel observes socket events
socketService.events
    .filterIsInstance<SocketEvent.NewMessage>()
    .filter { chatGuidMatches(it) }
    .collect { event ->
        lastSocketMessageTime = System.currentTimeMillis()
        pagingController.onNewMessageInserted(event.message.guid)
        _socketNewMessage.tryEmit(event.message.guid)  // "New messages" indicator
        markAsRead()
    }
```

**Position Shifting**: When a new message inserts at position 0, all existing positions shift by +1:

```kotlin
fun onNewMessageInserted(guid: String) {
    // Shift all existing positions
    val newSparseData = sparseData.mapKeys { (pos, _) -> pos + 1 }
    val newGuidToPosition = guidToPosition.mapValues { (_, pos) -> pos + 1 }

    // Insert new message at position 0
    newGuidToPosition[guid] = 0

    // Trigger Flow update
    _messages.value = SparseMessageList(newSparseData, totalSize + 1)
}
```

### 4. MessageBubble Rendering

**File**: `ui/components/message/MessageBubble.kt`

Routes between fast and complex paths:

```kotlin
@Composable
fun MessageBubble(message: MessageUiModel, ...) {
    val firstUrl = remember(message.text) { UrlParsingUtils.getFirstUrl(message.text) }
    val needsSegmentation = MessageSegmentParser.needsSegmentation(message, firstUrl != null)

    Row(verticalAlignment = Alignment.Bottom) {
        // Avatar (group chats only)
        if (shouldShowAvatarSpace && showAvatar) {
            Avatar(senderName = message.senderName, avatarPath = message.avatarPath)
        }

        // Content routing
        if (needsSegmentation) {
            SegmentedMessageBubble(...)  // Media, links, complex layout
        } else {
            SimpleBubbleContent(...)      // Text-only (fast path)
        }
    }
}
```

**Two-Path Rendering**:

- **SimpleBubbleContent**: Pure text, minimal composables
- **SegmentedMessageBubble**: Handles attachments, link previews, reactions overlay

### 5. Delivery Status Indicators

**File**: `ui/components/message/MessageDeliveryIndicators.kt`

Shows only on the last outgoing message:

```kotlin
// Find last outgoing message index (O(n) but cached via remember)
val lastOutgoingIndex = remember(messages) {
    messages.indexOfFirst { it.isFromMe && !it.isReaction }
}

// Status computation
val status = when {
    hasError -> "error"
    isRead -> "read"          // Blue double checkmark
    isDelivered -> "delivered" // Gray double checkmark
    isSent -> "sent"          // Single checkmark
    else -> "sending"         // Clock icon
}
```

### 6. Message Grouping & Spacing

Pre-computed maps to avoid O(n²) per-item lookups:

```kotlin
// Pre-compute sender name visibility (group chats)
val showSenderNameMap = remember(messages, isGroup) {
    if (!isGroup) emptyMap()
    else messages.indices.associateWith { i ->
        val msg = messages[i]
        val prev = messages.getOrNull(i + 1)  // Older message
        !msg.isFromMe && msg.senderName != null &&
            (prev == null || prev.isFromMe || prev.senderName != msg.senderName)
    }
}

// Per-item spacing
val topPadding = when {
    message.isPlacedSticker -> 0.dp
    groupPosition == SINGLE || groupPosition == FIRST -> 6.dp  // Group break
    else -> 2.dp  // Tight within group
}
```

### 7. Time Separators

15+ minute gap triggers separator:

```kotlin
val showTimeSeparator = !message.isReaction && (nextVisibleMessage?.let {
    (message.dateCreated - it.dateCreated) > 15 * 60 * 1000  // 15 minutes
} ?: true)

if (showTimeSeparator) {
    DateSeparator(date = formatTimeSeparator(message.dateCreated))
}
```

### 8. Optimistic UI

Pending messages display immediately with `temp-` prefix GUID:

```kotlin
// MessageSendingService creates temp message
val tempMessage = MessageEntity(
    guid = "temp-${UUID.randomUUID()}",  // Temp prefix
    chatGuid = chatGuid,
    text = text,
    isFromMe = true,
    dateCreated = System.currentTimeMillis()
)
messageDao.insertMessage(tempMessage)

// UI identifies pending state
val isPending = guid.startsWith("temp-")
val showSpinner = isPending && !hasError
```

---

## Flow Diagrams

### Complete Message Sending Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                        USER TAPS SEND                            │
│  ChatScreen → ChatSendDelegate.sendMessage()                    │
│  - Determines delivery mode (iMessage/SMS/MMS)                  │
│  - Clears UI immediately (responsive feel)                      │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                  QUEUE MESSAGE (OFFLINE-FIRST)                   │
│  PendingMessageRepository.queueMessage()                        │
│  ├─ Insert PendingMessageEntity (PENDING status)               │
│  ├─ Persist attachments to app-internal storage                │
│  └─ Enqueue WorkManager job (ExistingWorkPolicy.KEEP)         │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼ (When network available)
┌─────────────────────────────────────────────────────────────────┐
│                  WORKMANAGER SENDS MESSAGE                       │
│  MessageSendWorker.doWork()                                     │
│  ├─ Update status: PENDING → SENDING                           │
│  ├─ Load persisted attachments                                 │
│  └─ Call MessageSendingService.sendUnified()                   │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                  ┌────────┼────────┐
                  │                 │
                  ▼                 ▼
        ┌─────────────────┐  ┌──────────────┐
        │  iMessage (API) │  │  SMS/MMS     │
        │                 │  │  (Android)   │
        │  - Create temp  │  │              │
        │    message      │  │              │
        │  - Upload each  │  │              │
        │    attachment   │  │              │
        │  - Send text    │  │              │
        └────────┬────────┘  └──────┬───────┘
                 │                  │
                 └────────┬─────────┘
                          │
                 ┌────────┴────────┐
                 │                 │
              Success           Failure
                 │                 │
                 ▼                 ▼
          ┌──────────┐     ┌──────────────┐
          │   SENT   │     │ retry < 3?   │
          │          │     │   YES: RETRY │
          │ Replace  │     │   NO: FAILED │
          │ tempGuid │     └──────────────┘
          │ with     │
          │ serverGuid│
          └──────────┘
```

### Complete Message Receiving Flow

```
┌───────────────────────────────────────────────────────────────────┐
│                     MESSAGE ARRIVES                                │
│                                                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────┐  ┌─────────┐ │
│  │  Socket.IO   │  │     FCM      │  │  Adaptive   │  │ Background│
│  │  (Primary)   │  │   (Backup)   │  │   Polling   │  │   Sync   │ │
│  └──────┬───────┘  └──────┬───────┘  └──────┬──────┘  └────┬────┘ │
│         │                 │                 │               │      │
│         │                 │ (if socket      │ (if socket   │      │
│         │                 │  disconnected)  │  quiet >5s)  │      │
│         │                 │                 │               │      │
└─────────┼─────────────────┼─────────────────┼───────────────┼──────┘
          │                 │                 │               │
          └─────────────────┴─────────────────┴───────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                  DEDUPLICATION CHECK                             │
│  IncomingMessageHandler.handleIncomingMessage()                 │
│  - Check if message.guid already exists in Room                 │
│  - Use insertOrIgnore to handle race conditions                 │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                  DATABASE INSERT                                 │
│  MessageDao.insertMessage()                                     │
│  ChatDao.incrementUnreadCount() (atomic)                        │
│  ChatDao.updateLastMessage()                                    │
│  AttachmentDao.insertAttachment() (for each attachment)         │
└──────────────────────────┬──────────────────────────────────────┘
                           │
              ┌────────────┴────────────┐
              │                         │
              ▼                         ▼
┌──────────────────────┐    ┌──────────────────────────────────┐
│   NOTIFICATION       │    │          UI UPDATE                │
│                      │    │                                   │
│ - Dedup check        │    │ Room Flow emits                  │
│ - Active chat check  │    │        ↓                         │
│ - Muted/snoozed check│    │ MessagePagingController detects  │
│ - Spam check         │    │        ↓                         │
│ - Contact lookup     │    │ Shifts positions, loads new msg  │
│ - Show notification  │    │        ↓                         │
│                      │    │ StateFlow update                 │
└──────────────────────┘    │        ↓                         │
                            │ ChatScreen LazyColumn recomposes │
                            └──────────────────────────────────┘
```

### GUI Rendering Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    DATA SOURCE                                   │
│  Room Database (MessageDao)                                     │
│  - Single source of truth                                       │
│  - Triggers Flow on any insert/update/delete                   │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│               RoomMessageDataSource                              │
│  - Observes message count changes                               │
│  - Loads messages by position range (sparse)                    │
│  - Converts MessageEntity → MessageUiModel                      │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│              MessagePagingController                             │
│  - BitSet for O(1) "is loaded?" check                          │
│  - Sparse map: position → MessageUiModel                        │
│  - Gap detection and lazy loading                               │
│  - Position shifting on new messages                            │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                 ChatViewModel                                    │
│  - Collects pagingController.messages StateFlow                │
│  - Converts to uiState.messages: List<MessageUiModel>          │
│  - Observes socket events for real-time indicators             │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                   ChatScreen                                     │
│  - LazyColumn (reverseLayout = true)                           │
│  - Pre-computed maps: senderName, avatar, groupPosition        │
│  - itemsIndexed with stable key = message.guid                 │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                  MessageBubble                                   │
│                                                                  │
│  ┌──────────────────┐     ┌────────────────────────────────┐   │
│  │ SimpleBubbleContent│    │ SegmentedMessageBubble         │   │
│  │ (text-only, fast) │    │ (attachments, links, complex)  │   │
│  └──────────────────┘     └────────────────────────────────┘   │
│                                                                  │
│  + DeliveryIndicator (last outgoing only)                       │
│  + ReactionOverlay (if has reactions)                           │
│  + DateSeparator (15+ min gap)                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Key Design Principles

1. **Offline-First**: Messages queued before network requests; survives crashes
2. **Idempotency**: `tempGuid`/`localId` prevents duplicates on retry
3. **Reliable Delivery**: WorkManager + exponential backoff (30s initial, max 3 retries)
4. **Flexible Routing**: Auto-selects iMessage/SMS/MMS based on chat type and settings
5. **Multi-Path Sync**: Socket.IO + FCM + polling + background worker
6. **Deduplication**: Multiple layers prevent duplicate processing/notifications
7. **Single Source of Truth**: Room database with Flow observation
8. **Sparse Loading**: BitSet pagination for efficient large conversation handling
9. **Optimistic UI**: Temp messages display immediately
10. **Graceful Fallback**: Auto-retry iMessage as SMS when configured

## Performance & Animation Analysis

For a detailed analysis of UI performance issues (hangs during message sending/receiving) and a plan for implementing fluid animations, see [PLAN_FLUID_MESSAGE_ANIMATIONS.md](PLAN_FLUID_MESSAGE_ANIMATIONS.md).

Key findings:

1. **Main Thread Blocking**: `RoomMessageDataSource` performs heavy data transformation (reactions, attachments, UI model conversion) on the Main thread.
2. **Animation**: `ChatScreen` lacks `animateItemPlacement`, causing new messages to "snap" in.
3. **Threading**: `MessagePagingController` operations run on `viewModelScope` (Main) by default.
