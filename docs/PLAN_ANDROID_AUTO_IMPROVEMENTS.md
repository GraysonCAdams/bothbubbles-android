# Android Auto Improvements - Implementation Plan

## Overview

This document outlines implementation plans for enhancing the BothBubbles Android Auto experience. Features are prioritized by impact and effort, with detailed technical specifications for each.

---

## Phase 1: Quick Wins

### 1.1 Auto Mark-as-Read on Conversation Entry

**Problem:** Users must manually tap "Mark Read" button. Reading messages in the car leaves notifications active on phone.

**UX Goal:** When entering a conversation, immediately clear unread indicator and sync read status.

**Implementation:**

**File:** `services/auto/ConversationDetailScreen.kt`

```kotlin
init {
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            screenScope.cancel()
        }
    })
    refreshMessages()

    // NEW: Auto mark as read when entering conversation
    if (chat.hasUnreadMessage) {
        screenScope.launch {
            try {
                chatRepository.markChatAsRead(chat.guid)
                onRefresh() // Update parent list immediately
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-mark as read", e)
            }
        }
    }
}
```

**Considerations:**
- Update `ChatEntity` locally before API call to prevent UI flicker
- Keep manual "Mark Read" button as fallback for failed auto-mark
- Fire-and-forget pattern (don't block screen rendering)

**Effort:** ~2 hours

---

### 1.2 Connection Status Indicator

**Problem:** If phone loses data while driving, message sends fail silently. User has no visibility into connection state.

**UX Goal:**
- Show connection status in root screen header
- Show toast on send attempt while offline
- Visual indicator: icon or title suffix "(Offline)"

**Implementation:**

**File:** `services/auto/BothBubblesCarAppService.kt`

Add `SocketService` to session dependencies:

```kotlin
class BothBubblesAutoSession(
    // ... existing deps
    private val socketService: SocketService
) : Session() {
    // Pass to MessagingRootScreen
}
```

**File:** `services/auto/MessagingRootScreen.kt`

```kotlin
class MessagingRootScreen(
    carContext: CarContext,
    // ... existing deps
    private val socketService: SocketService
) : Screen(carContext) {

    @Volatile
    private var isConnected: Boolean = true

    init {
        screenScope.launch {
            socketService.connectionState.collect { state ->
                val wasConnected = isConnected
                isConnected = state == ConnectionState.Connected
                if (wasConnected != isConnected) {
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val title = if (isConnected) "BothBubbles" else "BothBubbles (Offline)"

        // OR use ActionStrip with warning icon
        val headerAction = if (!isConnected) {
            Action.Builder()
                .setIcon(CarIcon.Builder(
                    IconCompat.createWithResource(carContext, R.drawable.ic_cloud_off)
                ).build())
                .build()
        } else null

        return TabTemplate.Builder(tabCallback)
            .setTitle(title)
            // ...
            .build()
    }
}
```

**File:** `services/auto/VoiceReplyScreen.kt`

Add offline check before send:

```kotlin
private fun sendMessage(text: String) {
    if (!socketService.isConnected()) {
        CarToast.makeText(carContext, "Offline - message queued", CarToast.LENGTH_LONG).show()
    }
    // Continue with send (MessageSendingService handles queuing)
}
```

**Effort:** ~4 hours

---

### 1.3 Privacy Mode (Hide Message Content)

**Problem:** Message content displayed on dashboard screen visible to passengers.

**UX Goal:** Toggle in phone app settings to hide message previews in car. Show "New Message" instead of actual content.

**Implementation:**

**File:** `data/local/prefs/FeaturePreferences.kt`

```kotlin
object FeaturePreferences {
    // ... existing prefs

    val HIDE_AUTO_MESSAGE_CONTENT = booleanPreferencesKey("hide_auto_message_content")
}
```

**File:** `data/local/prefs/SettingsDataStore.kt`

```kotlin
val hideAutoMessageContent: Flow<Boolean> = dataStore.data.map { prefs ->
    prefs[FeaturePreferences.HIDE_AUTO_MESSAGE_CONTENT] ?: false
}

suspend fun setHideAutoMessageContent(hide: Boolean) {
    dataStore.edit { prefs ->
        prefs[FeaturePreferences.HIDE_AUTO_MESSAGE_CONTENT] = hide
    }
}
```

**File:** `services/auto/ConversationListContent.kt`

```kotlin
class ConversationListContent(
    // ... existing deps
    private val hideMessageContent: Boolean = false
) {
    private fun getMessagePreview(message: MessageEntity?, chat: ChatEntity): String {
        if (hideMessageContent) {
            return if (chat.hasUnreadMessage) "New Message" else "Tap to view"
        }
        // ... existing preview logic
    }
}
```

**File:** `services/auto/ConversationDetailScreen.kt`

```kotlin
private fun buildMessageRow(message: MessageEntity): Row? {
    val displayText = if (hideMessageContent) {
        "Message hidden for privacy"
    } else {
        message.text ?: return null
    }
    // ...
}
```

**New Settings Screen:** `ui/settings/auto/AndroidAutoSettingsScreen.kt`

- Toggle: "Hide message content in car"
- Description: "Shows 'New Message' instead of message preview when using Android Auto"

**Effort:** ~6 hours

---

## Phase 2: Medium Effort Features

### 2.1 Audio Message Playback

**Problem:** Audio messages display nothing (skipped due to null text). Major functional gap for audio-first platform.

**UX Goal:**
- Show "Audio Message" row with play button
- Tap to play audio (pauses car media)
- Visual feedback during playback

**Implementation:**

**File:** `services/auto/ConversationDetailScreen.kt`

```kotlin
private fun buildMessageRow(message: MessageEntity): Row? {
    // Check for audio attachment first
    val audioAttachment = message.attachments?.firstOrNull {
        it.mimeType?.startsWith("audio/") == true
    }

    if (audioAttachment != null) {
        return buildAudioMessageRow(message, audioAttachment)
    }

    val text = message.text ?: return null
    if (text.isBlank()) return null
    // ... existing text message handling
}

private fun buildAudioMessageRow(message: MessageEntity, attachment: AttachmentEntity): Row {
    val senderName = if (message.isFromMe) "You" else getSenderName(message)
    val time = dateFormat.format(Date(message.dateCreated))
    val duration = attachment.duration?.let { formatDuration(it) } ?: ""

    return Row.Builder()
        .setTitle("$senderName - $time")
        .addText("Audio Message $duration")
        .setOnClickListener { playAudioMessage(attachment) }
        .build()
}

private fun playAudioMessage(attachment: AttachmentEntity) {
    screenScope.launch {
        try {
            // 1. Get/download attachment file
            val file = attachmentRepository.getLocalFile(attachment)
                ?: attachmentRepository.downloadAttachment(attachment)

            if (file == null) {
                CarToast.makeText(carContext, "Unable to load audio", CarToast.LENGTH_SHORT).show()
                return@launch
            }

            // 2. Request audio focus
            val audioManager = carContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setOnAudioFocusChangeListener { /* handle focus loss */ }
                .build()

            val result = audioManager.requestAudioFocus(focusRequest)
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                CarToast.makeText(carContext, "Cannot play audio now", CarToast.LENGTH_SHORT).show()
                return@launch
            }

            // 3. Play with MediaPlayer
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    audioManager.abandonAudioFocusRequest(focusRequest)
                    CarToast.makeText(carContext, "Audio complete", CarToast.LENGTH_SHORT).show()
                }
            }

            CarToast.makeText(carContext, "Playing audio...", CarToast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
            CarToast.makeText(carContext, "Playback failed", CarToast.LENGTH_SHORT).show()
        }
    }
}

private var mediaPlayer: MediaPlayer? = null

// Clean up in lifecycle observer
override fun onDestroy(owner: LifecycleOwner) {
    mediaPlayer?.release()
    mediaPlayer = null
    screenScope.cancel()
}
```

**Dependencies:**
- `AttachmentRepository` - for downloading/accessing audio files
- `MediaPlayer` - Android native audio playback
- `AudioManager` - for audio focus management

**Considerations:**
- Handle streaming vs downloaded audio
- Show playback progress indicator if possible
- Stop playback on screen exit
- Handle audio focus loss gracefully (pause/stop)

**Effort:** ~1-2 days

---

### 2.2 Voice Search for Contacts

**Problem:** Compose tab only shows recent 20 contacts. Can't message someone not in recent list.

**UX Goal:**
- Search button in Compose tab header
- Tap triggers voice input
- Results filtered from full contact database
- Select contact to compose message

**Implementation:**

**New File:** `services/auto/ContactSearchScreen.kt`

```kotlin
class ContactSearchScreen(
    carContext: CarContext,
    private val handleDao: HandleDao,
    private val chatDao: ChatDao,
    private val messageSendingService: MessageSendingService,
    private val onContactSelected: (ChatEntity) -> Unit
) : Screen(carContext) {

    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var searchQuery: String = ""

    @Volatile
    private var searchResults: List<HandleEntity> = emptyList()

    @Volatile
    private var isSearching: Boolean = false

    override fun onGetTemplate(): Template {
        return SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) {
                    searchQuery = searchText
                    performSearch(searchText)
                }

                override fun onSearchSubmitted(searchText: String) {
                    searchQuery = searchText
                    performSearch(searchText)
                }
            }
        )
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(false) // Voice-first
            .setInitialSearchText(searchQuery)
            .setSearchHint("Search contacts...")
            .setItemList(buildResultsList())
            .build()
    }

    private fun performSearch(query: String) {
        if (query.length < 2) {
            searchResults = emptyList()
            invalidate()
            return
        }

        isSearching = true
        invalidate()

        screenScope.launch {
            try {
                // Search handles by name or address
                val results = handleDao.searchHandles("%$query%")
                searchResults = results.take(10) // Limit results for Auto
                isSearching = false
                invalidate()
            } catch (e: Exception) {
                Log.e(TAG, "Search failed", e)
                isSearching = false
                invalidate()
            }
        }
    }

    private fun buildResultsList(): ItemList {
        val builder = ItemList.Builder()

        if (isSearching) {
            builder.setNoItemsMessage("Searching...")
            return builder.build()
        }

        if (searchResults.isEmpty() && searchQuery.isNotEmpty()) {
            builder.setNoItemsMessage("No contacts found")
            return builder.build()
        }

        for (handle in searchResults) {
            val displayName = handle.cachedDisplayName
                ?: handle.inferredName
                ?: PhoneNumberFormatter.format(handle.address)

            val row = Row.Builder()
                .setTitle(displayName)
                .addText(handle.address)
                .setOnClickListener {
                    // Find or create chat for this handle
                    screenScope.launch {
                        val chat = chatDao.getChatByParticipantAddress(handle.address)
                        if (chat != null) {
                            onContactSelected(chat)
                        } else {
                            // Navigate to voice compose with address
                            screenManager.push(
                                VoiceReplyScreen(
                                    carContext = carContext,
                                    recipientAddress = handle.address,
                                    messageSendingService = messageSendingService,
                                    onMessageSent = { screenManager.popToRoot() }
                                )
                            )
                        }
                    }
                }
                .build()

            builder.addItem(row)
        }

        return builder.build()
    }

    companion object {
        private const val TAG = "ContactSearchScreen"
    }
}
```

**File:** `data/local/db/dao/HandleDao.kt`

Add search query:

```kotlin
@Query("""
    SELECT * FROM handles
    WHERE cachedDisplayName LIKE :query
       OR inferredName LIKE :query
       OR address LIKE :query
    ORDER BY cachedDisplayName ASC
    LIMIT 20
""")
suspend fun searchHandles(query: String): List<HandleEntity>
```

**File:** `services/auto/MessagingRootScreen.kt`

Add search action to Compose tab:

```kotlin
// In compose tab header or as floating action
val searchAction = Action.Builder()
    .setTitle("Search")
    .setIcon(CarIcon.Builder(
        IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_search)
    ).build())
    .setOnClickListener {
        screenManager.push(ContactSearchScreen(
            carContext = carContext,
            handleDao = handleDao,
            chatDao = chatDao,
            messageSendingService = messageSendingService,
            onContactSelected = { chat ->
                screenManager.push(VoiceReplyScreen(...))
            }
        ))
    }
    .build()
```

**Effort:** ~1-2 days

---

### 2.3 Location Sharing

**Problem:** "Where are you?" is top driving communication use case. No quick way to share location.

**UX Goal:**
- "Share Location" quick action in conversation detail
- One tap sends current coordinates as Apple Maps link
- Works for both iMessage and SMS recipients

**Implementation:**

**File:** `services/auto/ConversationDetailScreen.kt`

Add location action:

```kotlin
override fun onGetTemplate(): Template {
    // ... existing code

    val locationAction = Action.Builder()
        .setTitle("Location")
        .setIcon(CarIcon.Builder(
            IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_mylocation)
        ).build())
        .setOnClickListener { shareCurrentLocation() }
        .build()

    return ListTemplate.Builder()
        .setTitle(displayTitle)
        .setHeaderAction(Action.BACK)
        .setSingleList(itemListBuilder.build())
        .addAction(replyAction)
        .addAction(locationAction)  // NEW
        .addAction(markReadAction)
        .build()
}

private fun shareCurrentLocation() {
    screenScope.launch {
        try {
            // Check permission
            if (ContextCompat.checkSelfPermission(
                carContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) {
                CarToast.makeText(
                    carContext,
                    "Location permission required",
                    CarToast.LENGTH_LONG
                ).show()
                return@launch
            }

            // Get current location
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(carContext)
            val location = fusedLocationClient.lastLocation.await()

            if (location == null) {
                CarToast.makeText(carContext, "Unable to get location", CarToast.LENGTH_SHORT).show()
                return@launch
            }

            // Format as Apple Maps link (works for iMessage, clickable for SMS)
            val mapsLink = "https://maps.apple.com/?ll=${location.latitude},${location.longitude}"

            // Or use Google Maps for broader compatibility:
            // val mapsLink = "https://maps.google.com/?q=${location.latitude},${location.longitude}"

            // Send as message
            val result = messageSendingService.sendMessage(
                chatGuid = chat.guid,
                text = mapsLink
            )

            if (result.isSuccess) {
                CarToast.makeText(carContext, "Location shared", CarToast.LENGTH_SHORT).show()
                refreshMessages()
                onRefresh()
            } else {
                CarToast.makeText(carContext, "Failed to share location", CarToast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to share location", e)
            CarToast.makeText(carContext, "Location error", CarToast.LENGTH_SHORT).show()
        }
    }
}
```

**Dependencies:**
- `play-services-location` for `FusedLocationProviderClient`
- `ACCESS_FINE_LOCATION` permission (app likely already has)

**Considerations:**
- Use Apple Maps link for iMessage recipients (rich preview)
- Use Google Maps link as fallback/option
- Consider adding to quick reply templates
- Could also send as vCard with location for richer experience

**Effort:** ~4-6 hours

---

## Phase 3: Higher Effort Features

### 3.1 Improved Pagination (Toward Infinite Scroll)

**Problem:** Manual "Load More" buttons are poor UX while driving.

**Current Limitation:** Car App Library `ItemList` doesn't expose scroll position listeners in the standard API.

**Investigation Required:**
1. Check if `OnItemVisibilityChangedListener` is available in Car App Library 1.7.0
2. Investigate if native Android Auto APIs expose this
3. Consider pre-loading strategy (load 2 pages ahead)

**Potential Workaround - Predictive Loading:**

```kotlin
// Load more items when user views the list
// Assumption: if they opened detail and came back, they might scroll more

override fun onGetTemplate(): Template {
    // Pre-fetch next page in background if near end
    if (cachedConversations.size >= (currentPage + 1) * PAGE_SIZE - 3) {
        prefetchNextPage()
    }
    // ...
}

private fun prefetchNextPage() {
    if (isPrefetching || !hasMoreConversations) return
    isPrefetching = true
    screenScope.launch {
        // Fetch next page silently
        // Don't invalidate until user triggers load more
    }
}
```

**Better Solution (if API supports):**

```kotlin
val itemList = ItemList.Builder()
    .setOnItemVisibilityChangedListener { startIndex, endIndex ->
        if (endIndex >= cachedItems.size - 3 && hasMore) {
            loadMoreItems()
        }
    }
    .build()
```

**Effort:** ~2-3 days (including investigation)

---

### 3.2 Text-to-Speech Message Readback

**Problem:** Standard notification TTS reads only new messages. Can't catch up on conversation thread.

**UX Goal:**
- "Read Aloud" button in conversation detail
- Reads last N messages using TTS
- Respects audio focus

**Implementation:**

**File:** `services/auto/ConversationDetailScreen.kt`

```kotlin
private var tts: TextToSpeech? = null
private var ttsReady = false

init {
    tts = TextToSpeech(carContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
    }
}

private fun readMessagesAloud() {
    if (!ttsReady) {
        CarToast.makeText(carContext, "Speech not available", CarToast.LENGTH_SHORT).show()
        return
    }

    val audioManager = carContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setOnAudioFocusChangeListener { focus ->
            if (focus == AudioManager.AUDIOFOCUS_LOSS) {
                tts?.stop()
            }
        }
        .build()

    if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        return
    }

    // Read last 5 messages
    val messagesToRead = cachedMessages.take(5).reversed()
    val script = messagesToRead.joinToString(". ") { message ->
        val sender = if (message.isFromMe) "You said" else "${getSenderName(message)} said"
        "$sender: ${message.text ?: "attachment"}"
    }

    tts?.speak(script, TextToSpeech.QUEUE_FLUSH, null, "readback")

    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onDone(utteranceId: String?) {
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
        override fun onError(utteranceId: String?) {
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
        override fun onStart(utteranceId: String?) {}
    })
}

// Add to template actions
val readAloudAction = Action.Builder()
    .setTitle("Read")
    .setIcon(CarIcon.Builder(
        IconCompat.createWithResource(carContext, android.R.drawable.ic_btn_speak_now)
    ).build())
    .setOnClickListener { readMessagesAloud() }
    .build()
```

**Effort:** ~1 day

---

### 3.3 Reaction Display Enhancement

**Problem:** Reactions shown as text summary only ("Loved an image").

**UX Goal:** Map reaction text to emoji for quick visual parsing.

**Implementation:**

```kotlin
private fun formatReactionText(associatedMessageType: Int?): String? {
    return when (associatedMessageType) {
        2000 -> "❤️"      // Love
        2001 -> "👍"      // Like
        2002 -> "👎"      // Dislike
        2003 -> "😂"      // Laugh
        2004 -> "‼️"      // Emphasis
        2005 -> "❓"      // Question
        else -> null
    }
}

private fun buildMessageRow(message: MessageEntity): Row? {
    // Check if this is a reaction
    if (message.associatedMessageGuid != null) {
        val emoji = formatReactionText(message.associatedMessageType)
        if (emoji != null) {
            return Row.Builder()
                .setTitle("${getSenderName(message)} reacted $emoji")
                .build()
        }
    }
    // ... normal message handling
}
```

**Effort:** ~2-3 hours

---

## Implementation Order Summary

| Priority | Feature | Effort | Impact |
|----------|---------|--------|--------|
| 1 | Auto Mark-as-Read | 2 hrs | High - reduces friction |
| 2 | Connection Status | 4 hrs | High - critical feedback |
| 3 | Privacy Mode | 6 hrs | Medium - passenger comfort |
| 4 | Reaction Emoji | 3 hrs | Low - polish |
| 5 | Location Sharing | 6 hrs | High - top use case |
| 6 | Audio Playback | 1-2 days | High - major gap |
| 7 | Voice Search | 1-2 days | Medium - power users |
| 8 | TTS Readback | 1 day | Medium - convenience |
| 9 | Infinite Scroll | 2-3 days | Low - UX polish |

---

## Testing Considerations

### Android Auto Testing
- Use Desktop Head Unit (DHU) for development
- Test with actual car head unit for real-world validation
- Test offline scenarios (airplane mode)
- Test audio focus with music apps running

### Test Scenarios
1. Enter conversation → verify auto mark-as-read
2. Lose connection → verify status indicator
3. Toggle privacy mode → verify content hidden
4. Play audio message → verify media pause/resume
5. Search contact → verify voice input works
6. Share location → verify link format

---

## Dependencies to Add

```kotlin
// build.gradle.kts (app)
dependencies {
    // Location services (if not already present)
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // Car App Library (verify version)
    implementation("androidx.car.app:app:1.7.0")
}
```

---

## Open Questions

1. **Audio streaming:** Should we stream audio or require full download first?
2. **Location format:** Apple Maps vs Google Maps vs both?
3. **Privacy mode scope:** Hide in notifications too, or just Auto UI?
4. **Search scope:** Contacts only, or include message content search?
