# Code Quality Anti-Patterns

**Scope:** Dead code, duplication, naming, complexity, documentation, error handling

---

## High Severity Issues

### 1. Empty Catch Blocks with Swallowed Exceptions

~~#### 1.1 VideoCompressor.kt (Lines 190-198)~~ **FIXED 2024-12-20**

~~```kotlin
finally {
    try { videoDecoder?.stop() } catch (_: Exception) {}
    try { videoDecoder?.release() } catch (_: Exception) {}
    try { videoEncoder?.stop() } catch (_: Exception) {}
    try { videoEncoder?.release() } catch (_: Exception) {}
    try { audioDecoder?.stop() } catch (_: Exception) {}
    try { audioDecoder?.release() } catch (_: Exception) {}
    try { muxer?.release() } catch (_: Exception) {}
    try { extractor?.release() } catch (_: Exception) {}
}
```~~

~~**Problem:** Silent exception swallowing makes debugging impossible.~~

**Fix Applied:**
```kotlin
try { videoDecoder?.stop() } catch (e: Exception) { Timber.d(e, "Failed to stop video decoder") }
// All 9 cleanup operations now log exceptions with Timber.d()
```

~~#### 1.2 ChatAudioHelper.kt (Lines 157, 189)~~ **FIXED 2024-12-20**

~~```kotlin
try {
    mediaRecorder?.stop()
    mediaRecorder?.release()
} catch (_: Exception) {}
```~~

**Fix Applied:** Added exception logging with descriptive messages for both locations.

~~#### 1.3 IMessageSenderStrategy.kt (Line 469)~~ **FIXED 2024-12-20**

~~```kotlin
compressedPath?.let { try { File(it).delete() } catch (e: Exception) { } }
```~~

~~**Problem:** File deletion failures silently ignored, could leave temp files.~~

**Fix Applied:**
```kotlin
compressedPath?.let {
    try {
        File(it).delete()
    } catch (e: Exception) {
        Timber.d(e, "Failed to delete compressed file: $it")
    }
}
```

---

### 2. Broad Exception Catching

**Affected Files:** 40+ files including:
- `services/media/AttachmentDownloadQueue.kt:388`
- `services/media/ThumbnailManager.kt:92,148`
- `services/media/ImageCompressor.kt:151,189,211,289`

**Issue:**
```kotlin
} catch (e: Exception) {
    Timber.e(e, "Failed to generate thumbnail")
    null
}
```

**Problem:** Catches all exceptions including programming errors (NPE, logic bugs).

**Fix:**
```kotlin
} catch (e: IOException) {
    Timber.e(e, "Failed to read image file")
    null
} catch (e: OutOfMemoryError) {
    Timber.e(e, "OOM generating thumbnail")
    null
}
```

---

## Medium Severity Issues

### 3. Code Duplication - Message Bubble Components

**Files:**
- `ui/components/message/MessageSimpleBubble.kt` (924 lines)
- `ui/components/message/MessageSegmentedBubble.kt` (906 lines)

**Problem:**
- Nearly identical import lists (95+ imports each)
- Similar functionality (reactions, delivery indicators)
- First 100 lines 90% identical
- Changes to one must be replicated to other

**Fix:**
Extract common composables:
```kotlin
// MessageBubbleCommon.kt
@Composable
fun CommonMessageDecorations(...)

@Composable
fun CommonReactionRow(...)
```

---

### 4. Streaming Request Body Buffer Bug

**Location:** `core/network/.../StreamingRequestBody.kt` (Lines 44-46)

```kotlin
val buffer = ByteArray(BUFFER_SIZE)  // Line 41 - declared but never used!

while (source.read(okio.Buffer(), BUFFER_SIZE.toLong()).also { bytesRead = it.toInt() } != -1L) {
    sink.write(okio.Buffer().apply { write(buffer, 0, bytesRead) }, bytesRead.toLong())
    //                                        ^^^^^^ Uses uninitialized ByteArray!
```

**Problem:**
- `ByteArray buffer` declared but never populated
- Reads into okio.Buffer, writes from uninitialized ByteArray
- Creates new `okio.Buffer()` per iteration (wasteful)
- **Potential data corruption on uploads**

**Note:** UriStreamingRequestBody.kt handles this correctly - inconsistent.

---

### 5. Boolean Parameters (Code Smell)

**Locations:**
- `services/messaging/MessageSendingService.kt:563` - `ensureCarrierReady(requireMms: Boolean)`
- `services/export/HtmlExporter.kt:106` - `generateBubbleMessage(message, isGroup: Boolean)`
- `services/messaging/sender/SmsSenderStrategy.kt:77` - `sendMms(options, isGroup: Boolean)`

**Problem:**
```kotlin
sendMms(options, true)  // What does true mean?
```

**Fix:**
```kotlin
enum class ChatType { SINGLE, GROUP }
fun sendMms(options: SendOptions, chatType: ChatType)
// Usage: sendMms(options, ChatType.GROUP)
```

---

## Low Severity Issues

### 6. Missing KDoc on Public APIs

**Location:** `data/repository/ChatRepository.kt` (Lines 38-80)

```kotlin
fun observeAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()
fun getAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()
suspend fun getChat(guid: String): ChatEntity? = chatDao.getChatByGuid(guid)
suspend fun getChatByGuid(guid: String): ChatEntity? = chatDao.getChatByGuid(guid)
```

**Problem:**
- No documentation
- `getChat` vs `getChatByGuid` are duplicates!
- No explanation of "observe" vs "get" pattern

---

### 7. Stale TODO Comments

**Locations:**
- `data/repository/ChatRepository.kt:347,378` - "TODO: Fix by changing API return type"
- ~~`data/repository/GifRepository.kt:28` - "TODO: Move to BuildConfig" (API key!)~~ **FIXED 2024-12-20**
- `ui/chat/components/ChatBackground.kt:28,29` - "TODO: Add support for per-chat wallpaper"
- `ui/conversations/ConversationsScreen.kt:531` - "TODO: For full select-all mode"

**Problem:** No ownership, no linked issues, unclear priority.

---

### 8. Duplicate Query Methods

**Location:** `data/repository/ChatRepository.kt`

```kotlin
suspend fun getChat(guid: String): ChatEntity? = chatDao.getChatByGuid(guid)  // Line 50
suspend fun getChatByGuid(guid: String): ChatEntity? = chatDao.getChatByGuid(guid)  // Line 52
```

**Problem:** Two methods doing identical work.

**Also in Life360Dao.kt:**
```kotlin
fun getMemberByIdFlow(memberId: String): Flow<Life360MemberEntity?>
fun getMemberById(memberId: String): Life360MemberEntity?  // Duplicate
```

---

### 9. Single-Letter Variables Outside Loops

**Locations:**
- `services/sms/MmsPduEncodingHelpers.kt:61,91` - `var v = value`
- `services/sms/MmsPduParser.kt:180,212,283,307` - `val b = stream.read()`
- `ui/chat/composer/components/ComposerSendButton.kt:348,434` - `val t = ...`

**Problem:** Unclear naming reduces readability.

**Fix:**
```kotlin
val byte = stream.read()
val normalizedProgress = (x / width).coerceIn(0f, 1f)
```

---

## Summary Table

| Issue | Severity | Count | Impact | Status |
|-------|----------|-------|--------|--------|
| ~~Empty catch blocks~~ | HIGH | ~~3~~ 0 | Hides failures | **FIXED 2024-12-20** |
| Broad Exception catching | HIGH | 40+ | Masks bugs | Open |
| Code duplication (UI) | MEDIUM | 2 | Maintenance burden | Open |
| Streaming body bug | MEDIUM | 1 | Data corruption risk | Open |
| Boolean parameters | MEDIUM | 3+ | API clarity | Open |
| Missing KDoc | LOW | 40+ | Documentation | Open |
| Stale TODOs | LOW | 6 | Tech debt | Open |
| Duplicate methods | LOW | 2 | Confusion | Open |
| Single-letter vars | LOW | 10+ | Readability | Open |
