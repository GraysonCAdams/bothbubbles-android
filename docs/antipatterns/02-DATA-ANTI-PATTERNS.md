# Data Layer Anti-Patterns

**Layer:** `app/src/main/kotlin/com/bothbubbles/data/`
**Files Scanned:** All repository, DAO, and entity files

---

## Critical Issues

### 1. ~~API Key Exposed in Source Code (SECURITY)~~ **FIXED 2024-12-20**

**Location:** `data/repository/GifRepository.kt` (Lines 28-29)

**Issue:**
```kotlin
companion object {
    // TODO: Move to BuildConfig or secure storage
    private const val TENOR_API_KEY = "AIzaSyAyimkuYQYF_FXVALexPuGQctUWRURdCYQ"
}
```

**Why Problematic:**
- API key is committed to version control
- Can be extracted from APK
- Enables unauthorized API access
- Violates security best practices

**Fix Applied:**
```kotlin
// 1. Added to local.properties (not committed, in .gitignore)
// TENOR_API_KEY=YOUR_API_KEY_HERE

// 2. Configured in build.gradle.kts
android {
    defaultConfig {
        buildConfigField("String", "TENOR_API_KEY", "\"${properties.getProperty("TENOR_API_KEY", "")}\"")
    }
}

// 3. Updated in code
private val TENOR_API_KEY = BuildConfig.TENOR_API_KEY
```

**Status:** FIXED on 2024-12-20 - API key moved to BuildConfig and local.properties

---

## High Severity Issues

### 2. God Repositories (Oversized Classes)

**Locations:**
- `data/repository/AttachmentRepository.kt` - **808 lines**
- `data/repository/MessageRepository.kt` - **580 lines**
- `data/repository/ChatRepository.kt` - **536 lines**

**Why Problematic:**
- Single Responsibility Principle violated
- Hard to test individual functionality
- High cognitive load for maintainers
- Changes risk breaking unrelated features

**AttachmentRepository handles too much:**
- Downloads
- Upload progress tracking
- Media conversion (HEIC, GIF)
- Caching
- Gallery save operations
- Thumbnail generation
- URL parsing

**Fix:** Extract into focused sub-components:
```kotlin
@Singleton
class AttachmentRepository @Inject constructor(
    private val downloadManager: AttachmentDownloadManager,
    private val mediaConverter: AttachmentMediaConverter,
    private val cacheManager: AttachmentCacheManager,
)
```

---

### 3. Missing Result<T> Error Handling

**Locations:**
- `data/repository/HandleRepository.kt` (Lines 81-128)
- `data/repository/ChatRepository.kt` (Lines 237-248)
- `data/repository/UnifiedChatGroupRepository.kt` (Lines 104-151)

**Issue:**
```kotlin
// HandleRepository.kt - No error handling
suspend fun updateCachedContactInfo(id: Long, displayName: String?, avatarPath: String?) {
    handleDao.updateCachedContactInfo(id, displayName, avatarPath)
    // If this fails, error is silently ignored
}

// ChatRepository.kt - Unwrapped mutation
suspend fun updateHandleCachedContactInfo(address: String, displayName: String?, avatarPath: String? = null) =
    participantOps.updateHandleCachedContactInfo(address, displayName, avatarPath)
    // Caller has no way to know if this succeeded
```

**Why Problematic:**
- Database failures are silently ignored
- Callers cannot react to errors
- Makes debugging difficult
- Inconsistent with error handling framework in `util/error/`

**Fix:**
```kotlin
suspend fun updateCachedContactInfo(id: Long, displayName: String?, avatarPath: String?): Result<Unit> {
    return runCatching {
        handleDao.updateCachedContactInfo(id, displayName, avatarPath)
    }
}
```

---

### 4. N+1 Query Patterns

**Location:** `data/repository/ChatParticipantOperations.kt` (Lines 109-137)

**Issue:**
```kotlin
suspend fun refreshAllContactInfo() {
    val allHandles = handleDao.getAllHandlesOnce()  // 1 query

    for (handle in allHandles) {  // N iterations
        val displayName = androidContactsService.getContactDisplayName(handle.address)
        val photoUri = androidContactsService.getContactPhotoUri(handle.address)
        handleDao.updateCachedContactInfo(handle.id, displayName, photoUri)  // N queries
    }
}
```

**Why Problematic:**
- 1 + N database queries where 2 would suffice
- O(N) contact service lookups
- Performance degrades linearly with number of handles
- Can cause UI jank during sync

**Fix:**
```kotlin
suspend fun refreshAllContactInfo() {
    val allHandles = handleDao.getAllHandlesOnce()

    // Batch collect updates
    val updates = allHandles.map { handle ->
        HandleUpdate(
            id = handle.id,
            displayName = androidContactsService.getContactDisplayName(handle.address),
            photoUri = androidContactsService.getContactPhotoUri(handle.address)
        )
    }

    // Single batch update
    handleDao.updateCachedContactInfoBatch(updates)
}
```

---

### 5. ~~Missing @Transaction on Multi-Table Operations~~ **FIXED 2024-12-20**

**Location:** `data/repository/ChatRepository.kt` (Lines 428-432)

**Issue:**
```kotlin
suspend fun deleteChat(guid: String): Result<Unit> = runCatching {
    tombstoneDao.recordDeletedChat(guid)      // Step 1
    messageDao.deleteMessagesForChat(guid)    // Step 2
    chatDao.deleteChatByGuid(guid)            // Step 3
}
```

**Why Problematic:**
- Three separate DAO calls without transaction
- If Step 2 fails, tombstone is recorded but chat still exists
- If Step 3 fails, messages are deleted but chat remains
- Data inconsistency on partial failure

**Fix Applied:**
```kotlin
// In ChatTransactionDao.kt:
@Transaction
suspend fun deleteChatWithDependencies(
    guid: String,
    tombstoneDao: TombstoneDao,
    messageDao: MessageDao
) {
    tombstoneDao.recordDeletedChat(guid)
    messageDao.deleteMessagesForChat(guid)
    deleteChatByGuid(guid)
}

// In ChatRepository.kt:
suspend fun deleteChat(guid: String): Result<Unit> = runCatching {
    chatDao.deleteChatWithDependencies(guid, tombstoneDao, messageDao)
}
```

**Status:** FIXED on 2024-12-20 - Chat deletion now uses @Transaction for atomicity

---

## Medium Severity Issues

### 6. Repository-to-Repository Dependencies

**Location:** `data/repository/MessageRepository.kt` (Lines 39-41)

**Issue:**
```kotlin
@Singleton
class MessageRepository @Inject constructor(
    private val attachmentRepository: AttachmentRepository,
    private val chatSyncOperations: ChatSyncOperations,
    private val unifiedChatGroupRepository: UnifiedChatGroupRepository,
    // ...
)
```

**Why Problematic:**
- Creates complex dependency chains
- Risk of circular dependencies
- Makes testing harder (need to mock multiple repositories)
- Violates clean dependency flow

**Fix:**
- Prefer depending on DAOs directly
- Use shared use cases for cross-cutting concerns
- Consider dependency inversion with interfaces

---

### 7. UI Type Imports in Data Layer

**Location:** `data/repository/ChatRepository.kt` (Lines 96-101)

**Issue:**
```kotlin
suspend fun getFilteredGroupChatCount(
    filter: com.bothbubbles.ui.conversations.ConversationFilter,  // UI Type!
    categoryFilter: String?
): Int {
```

**Why Problematic:**
- Data layer depends on UI layer type
- Violates clean architecture layering
- Creates tight coupling between layers
- Makes data layer harder to test independently

**Fix:**
```kotlin
// Create data layer filter type
enum class ChatFilterType {
    ALL, KNOWN, UNKNOWN, UNREAD
}

// Map at UI layer
val dataFilter = when (uiFilter) {
    ConversationFilter.ALL -> ChatFilterType.ALL
    // ...
}
```

---

### 8. Blocking I/O Without Proper Dispatcher

**Location:** `data/repository/GifRepository.kt` (Lines 119-122)

**Issue:**
```kotlin
URL(gif.fullUrl).openStream().use { input ->
    gifFile.outputStream().use { output ->
        input.copyTo(output)
    }
}
// No withContext(Dispatchers.IO)
```

**Why Problematic:**
- Network I/O on calling coroutine's dispatcher
- Could block main thread if called from UI
- Should explicitly use IO dispatcher

**Fix:**
```kotlin
withContext(Dispatchers.IO) {
    URL(gif.fullUrl).openStream().use { input ->
        gifFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
}
```

---

### 9. Sequential Attachment Downloads

**Location:** `data/repository/AttachmentRepository.kt` (Lines 613-635)

**Issue:**
```kotlin
pending.forEachIndexed { index, attachment ->
    onProgress?.invoke(index, pending.size)
    try {
        downloadAttachment(attachment.guid).getOrThrow()  // Sequential!
        downloaded++
    } catch (e: Exception) {
        Timber.w(e, "Failed to download ${attachment.guid}")
    }
}
```

**Why Problematic:**
- Downloads one attachment at a time
- Wastes network bandwidth
- Slow for chats with many attachments
- No concurrency benefit

**Fix:**
```kotlin
val results = pending.mapIndexed { index, attachment ->
    async {
        onProgress?.invoke(index, pending.size)
        downloadAttachment(attachment.guid)
    }
}.awaitAll()
```

---

## Low Severity Issues

### 10. Inconsistent Phone Number Normalization

**Location:** `data/repository/Life360Repository.kt` (Lines 67-77)

**Issue:**
```kotlin
val normalized = PhoneNumberFormatter.normalize(address) ?: address
val digitsOnly = address.filter { it.isDigit() }.takeLast(10)
// Two different normalization strategies used
```

**Why Problematic:**
- Inconsistent comparison logic
- Fallback behavior unclear
- Could lead to missed matches

**Fix:**
Use single, consistent normalization strategy throughout.

---

### 11. Hardcoded Magic Numbers

**Locations:**
- `data/repository/AttachmentRepository.kt` (Lines 48-52)
- `data/repository/PendingMessageRepository.kt` (Lines 78-80)

**Issue:**
```kotlin
// AttachmentRepository
private const val MAX_GIF_PROCESS_SIZE = 10L * 1024 * 1024
private const val MAX_HEIC_CONVERT_SIZE = 15L * 1024 * 1024

// PendingMessageRepository
private val maxGlobalRecentSends = 50
private val globalDuplicateWindowMs = 10 * 60 * 1000L  // 10 minutes
```

**Why Problematic:**
- Magic numbers scattered across codebase
- Not configurable
- Hard to tune for different devices

**Fix:**
Centralize in configuration class or BuildConfig.

---

## Missing Abstraction Interfaces

**Files Needing Interfaces:**
- `AttachmentRepository` - No interface
- `ChatRepository` - No interface
- `MessageRepository` - No interface

Only `PendingMessageRepository` has an interface (`PendingMessageSource`).

**Fix:**
Create interfaces for major repositories to enable testing:
```kotlin
interface ChatRepositoryContract {
    fun observeChat(guid: String): Flow<ChatEntity?>
    suspend fun deleteChat(guid: String): Result<Unit>
    // ...
}
```

---

## Summary Table

| Issue | Severity | File | Lines | Category |
|-------|----------|------|-------|----------|
| Hardcoded API Key | CRITICAL | GifRepository.kt | 28-29 | Security |
| God Repository | HIGH | AttachmentRepository.kt | 808 lines | Architecture |
| God Repository | HIGH | MessageRepository.kt | 580 lines | Architecture |
| God Repository | HIGH | ChatRepository.kt | 536 lines | Architecture |
| Missing Result<T> | HIGH | HandleRepository.kt | 81-128 | Error Handling |
| N+1 Query Pattern | HIGH | ChatParticipantOperations.kt | 109-137 | Performance |
| ~~Missing @Transaction~~ | ~~HIGH~~ | ~~ChatRepository.kt~~ | ~~428-432~~ | ~~Data Integrity~~ FIXED |
| Repository Dependencies | MEDIUM | MessageRepository.kt | 39-41 | Architecture |
| UI Type Import | MEDIUM | ChatRepository.kt | 96-101 | Layering |
| Blocking I/O | MEDIUM | GifRepository.kt | 119-122 | Threading |
| Sequential Downloads | MEDIUM | AttachmentRepository.kt | 613-635 | Performance |
| Phone Normalization | LOW | Life360Repository.kt | 67-77 | Logic |
| Magic Numbers | LOW | Multiple | Various | Maintainability |

---

## Positive Findings

- `PendingMessageRepository` has interface abstraction
- Good use of `runCatching` in many mutation operations
- Proper Room Flow returns for observable data
- Transaction usage in `PendingMessageRepository.queueMessage()`
