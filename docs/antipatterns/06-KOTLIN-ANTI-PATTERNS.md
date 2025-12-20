# Kotlin-Specific Anti-Patterns

**Scope:** Kotlin idioms, coroutines, null safety, collections

---

## Findings

### 1. Redundant Collection Operations

**Location:** `services/socket/SocketIOConnection.kt` (Line 147)

**Issue:**
```kotlin
args.drop(1).forEachIndexed { index: Int, arg: Any? ->
    val preview = arg?.toString()?.take(200) ?: "null"
    Timber.d("    arg[$index]: $preview")
}
```

**Problem:**
- `.drop(1)` creates an intermediate list allocation
- Index from `forEachIndexed` starts at 0, misaligned with original positions

**Fix:**
```kotlin
for (i in 1 until args.size) {
    val preview = args[i]?.toString()?.take(200) ?: "null"
    Timber.d("    arg[$i]: $preview")
}
```

---

### 2. Side Effects in While Condition

**Location:** `core/network/.../StreamingRequestBody.kt` (Lines 44-46)

**Issue:**
```kotlin
while (source.read(okio.Buffer(), BUFFER_SIZE.toLong()).also {
    bytesRead = it.toInt()
} != -1L && bytesRead > 0) {
```

**Problem:**
- Using `.also` for side effects in loop condition reduces readability
- Mutating `bytesRead` in condition, not loop body
- Anti-idiomatic Kotlin

**Fix:**
```kotlin
var bytesRead: Long = source.read(buffer, BUFFER_SIZE.toLong())
while (bytesRead != -1L && bytesRead > 0) {
    // ... process
    bytesRead = source.read(buffer, BUFFER_SIZE.toLong())
}
```

---

### 3. Mutable Set for One-Time Filtering

**Location:** `data/local/db/DatabaseMigrations.kt` (Lines 46-51)

**Issue:**
```kotlin
val existingColumns = mutableSetOf<String>()
db.query("PRAGMA table_info(chats)").use { cursor ->
    val nameIndex = cursor.getColumnIndex("name")
    while (cursor.moveToNext()) {
        existingColumns.add(cursor.getString(nameIndex))
    }
}
```

**Problem:**
- Verbose imperative style
- `mutableSetOf()` unnecessary for immediate population

**Fix:**
```kotlin
val existingColumns: Set<String> = db.query("PRAGMA table_info(chats)").use { cursor ->
    val nameIndex = cursor.getColumnIndex("name")
    buildSet {
        while (cursor.moveToNext()) {
            add(cursor.getString(nameIndex))
        }
    }
}
```

---

### 4. Clever But Non-Obvious Pattern

**Location:** `ui/chat/MessageTransformationUtils.kt` (Lines 38-47)

**Issue:**
```kotlin
val uniqueReactions = reactions
    .distinctBy { it.guid }
    .sortedByDescending { it.dateCreated }
    .let { sorted ->
        val seenSenders = mutableSetOf<Long>()
        sorted.filter { reaction ->
            val senderId = if (reaction.isFromMe) 0L else (reaction.handleId ?: 0L)
            seenSenders.add(senderId) // Returns true if not already present
        }
    }
```

**Problem:**
- Relies on `Set.add()` returning boolean for filtering
- Not self-documenting - intent unclear
- Mutable collection inside functional chain

**Fix:**
```kotlin
val uniqueReactions = reactions
    .distinctBy { it.guid }
    .sortedByDescending { it.dateCreated }
    .distinctBy { reaction ->
        if (reaction.isFromMe) 0L else (reaction.handleId ?: 0L)
    }
    .filter { !isReactionRemoval(it.associatedMessageType) }
```

---

### 5. Wildcard Import

**Location:** `ui/components/message/MessageTransformations.kt` (Line 12)

**Issue:**
```kotlin
import java.util.*
```

**Problem:**
- Wildcard imports can cause name collisions
- Makes dependencies unclear
- Most linters flag this

**Fix:**
```kotlin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
```

---

## Summary

| Issue | Severity | Category |
|-------|----------|----------|
| Redundant collection operations | LOW | Performance |
| Side effects in conditions | LOW | Readability |
| Mutable sets for one-time ops | LOW | Style |
| Clever boolean patterns | LOW | Maintainability |
| Wildcard imports | LOW | Best Practice |

**Overall Assessment:** The codebase demonstrates excellent Kotlin practices. These are minor style issues rather than critical bugs.
