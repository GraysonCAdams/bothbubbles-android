# Performance Anti-Patterns

**Scope:** Memory, CPU, database, network, UI performance

---

## High Impact Issues

### 1. SimpleDateFormat Instantiation in Hot Paths

SimpleDateFormat is expensive to create (contains internal regex compilation) and is NOT thread-safe.

#### 1.1 CursorChatMessageListDelegate - insertDateSeparators()

**Location:** `ui/chat/delegates/CursorChatMessageListDelegate.kt` (Lines 385-388)

```kotlin
val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

for (message in messages) {
    val dateKey = dateFormat.format(Date(message.dateCreated))
```

**Impact:** Created for EVERY message list emission. Hundreds of messages = hundreds of allocations.

#### 1.2 CursorChatMessageListDelegate - formatDisplayDate()

**Location:** Lines 417, 420

```kotlin
SimpleDateFormat("MMMM d", Locale.getDefault()).format(Date(timestamp))
SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
```

**Impact:** Two instances created PER DATE SEPARATOR.

#### 1.3 ChatScreenUtils - formatTimeSeparator()

**Location:** `ui/chat/ChatScreenUtils.kt` (Lines 199-201)

```kotlin
val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
val dayOfWeekFormat = SimpleDateFormat("EEEE", Locale.getDefault())
val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
```

**Impact:** THREE instances created every call from Compose.

#### 1.4 MediaGalleryViewModel - groupByMonth()

**Location:** `ui/chat/details/MediaGalleryViewModel.kt` (Lines 95, 106-108)

```kotlin
val dateFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
```

**Impact:** Created per emission for media gallery grouping.

#### 1.5 SnoozeDuration - formatRemainingTime() & formatEndTime()

**Location:** `ui/components/common/SnoozeDuration.kt` (Lines 40, 70, 74, 78)

```kotlin
val formatter = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())
val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
```

**Impact:** Created on snooze display, potentially in conversation lists.

**Fix for All:**
```kotlin
companion object {
    private val DATE_FORMAT_YYYY_MM_DD = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    // Or better: use java.time.format.DateTimeFormatter (thread-safe)
}
```

---

### 2. Multiple Calendar.get() Calls

**Location:** `ui/chat/details/MediaGalleryViewModel.kt` (Lines 101-108)

```kotlin
when {
    year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH) -> "This Month"
    year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH) - 1 -> "Last Month"
    year == now.get(Calendar.YEAR) - 1 && now.get(Calendar.MONTH) == 0 && month == 11 -> "Last Month"
```

**Problem:** Calls `now.get(Calendar.YEAR)` and `now.get(Calendar.MONTH)` 6+ times. Calendar.get() involves timezone calculations.

**Fix:**
```kotlin
val nowYear = now.get(Calendar.YEAR)
val nowMonth = now.get(Calendar.MONTH)

when {
    year == nowYear && month == nowMonth -> "This Month"
    year == nowYear && month == nowMonth - 1 -> "Last Month"
    // ...
}
```

---

## Medium Impact Issues

### 3. Chained Collection Operations

**Location:** `ui/chat/delegates/CursorChatMessageListDelegate.kt` (Lines 443-478)

```kotlin
val dedupedEntities = entities.distinctBy { it.guid }  // Pass 1

val messageGuids = dedupedEntities.map { it.guid }  // Pass 2

val reactionsByMessage = reactions.groupBy { reaction ->
    reaction.associatedMessageGuid?.let { guid ->
        if (guid.contains("/")) guid.substringAfter("/") else guid  // String ops
    }
}  // Pass 3

val allAttachments = attachmentRepository.getAttachmentsForMessages(messageGuids)
    .groupBy { it.messageGuid }  // Pass 4

val allEntitiesForHandles = dedupedEntities + reactions  // New list allocation

val missingHandleIds = allEntitiesForHandles
    .filter { !it.isFromMe && it.handleId != null && it.handleId !in mutableHandleIdToName }
    .mapNotNull { it.handleId }
    .distinct()  // Pass 5, 6, 7
```

**Problem:**
- 7+ passes over collections
- String operations in hot loops
- Multiple intermediate allocations

**Fix:**
- Combine passes where possible
- Use sequences for lazy evaluation
- Pre-compute lookups as Sets

---

### 4. buildReplyPreviewMap() Multiple Passes

**Location:** `ui/chat/delegates/CursorChatMessageListDelegate.kt` (Lines 521-577)

```kotlin
val replyGuids = messages.mapNotNull { it.threadOriginatorGuid }.distinct()  // 2 passes
val loadedMessagesMap = messages.associateBy { it.guid }  // Pass 3
val missingGuids = replyGuids.filter { it !in loadedMessagesMap }  // Pass 4
val allMessagesMap = loadedMessagesMap + fetchedOriginals  // Map concatenation
val originGuidsWithAttachments = allMessagesMap.values
    .filter { it.hasAttachments }  // Pass 5
    .map { it.guid }  // Pass 6
return replyGuids.mapNotNull { ... }.toMap()  // Pass 7, 8
```

**Problem:** 8+ passes, multiple map allocations.

---

### 5. Mutable Collection Conversions

**Location:** `ui/chat/delegates/CursorChatMessageListDelegate.kt` (Lines 515-517, 468, 476)

```kotlin
handleIdToName = cachedParticipants.associate { it.id to it.displayName }.toMutableMap()
// Creates immutable map, then copies to mutable

val mutableHandleIdToName = handleIdToName.toMutableMap()  // Defensive copy
val mutableAddressToName = addressToName.toMutableMap()  // Another copy
```

**Problem:** Double allocations - immutable then mutable.

**Fix:**
```kotlin
handleIdToName = cachedParticipants.associateTo(mutableMapOf()) { it.id to it.displayName }
```

---

## Low Impact Issues

### 6. Calendar Instance Allocations

**Location:** `ui/components/common/SnoozeDuration.kt` (Lines 56-57, 63)

```kotlin
val today = java.util.Calendar.getInstance()
val snoozeDay = java.util.Calendar.getInstance().apply { time = snoozeDate }
val tomorrow = java.util.Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
```

**Problem:** 3 Calendar instances per call. Calendar.getInstance() is expensive.

---

### 7. Excessive Logging

**Finding:** 1,610 logging statements throughout codebase.

**Problem:** Logging in release builds impacts battery and performance.

**Fix:** Ensure Timber strips debug logs in release builds.

---

## Summary Table

| Category | Count | Severity | Primary Impact |
|----------|-------|----------|----------------|
| SimpleDateFormat in hot paths | 6 | **HIGH** | CPU, Memory |
| Excessive Calendar.get() | 2 | MEDIUM | CPU |
| Chained collection ops | 2 | MEDIUM | Memory, CPU |
| Mutable collection conversions | 2 | LOW | Memory |
| Calendar allocations | 1 | LOW | Memory |
| Excessive logging | 1 | LOW | Battery |

---

## Positive Findings

- Database queries well-optimized with LIMIT/WHERE
- FTS5 indices for search
- Cursor-based pagination (no O(n) OFFSET)
- ImmutableList usage in UI state
- Proper Flow operators for reactive patterns
- Room as single source of truth
