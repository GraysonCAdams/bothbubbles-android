# Database and Room Anti-Patterns (Extended)

**Scope:** Queries, schema, migrations, transactions

---

## Migration Issues

### 1. Missing Schema Export Files

**Location:** `app/schemas/com.bothbubbles.data.local.db.BothBubblesDatabase/`

**Missing versions:** 25, 26, 28, 30

**Problem:** Schema files not exported for these versions. Makes migration testing/debugging harder.

**Fix:** Run `./gradlew exportSchema` for missing versions.

---

### 2. System.currentTimeMillis() in Migrations

**Location:** `data/local/db/DatabaseMigrations.kt` (Multiple lines: 81, 200, 303, 805)

**Issue:**
```kotlin
db.execSQL("ALTER TABLE chats ADD COLUMN date_created INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}")
```

**Problem:** `System.currentTimeMillis()` evaluated at migration TIME, not row insertion. All migrated rows get same timestamp.

**Fix:**
```kotlin
// Use SQLite function:
db.execSQL("ALTER TABLE chats ADD COLUMN date_created INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)")

// Or use fixed fallback:
db.execSQL("ALTER TABLE chats ADD COLUMN date_created INTEGER NOT NULL DEFAULT 0")
```

---

## Query Anti-Patterns

### 3. Unnecessary DISTINCT

**Location:** `data/local/db/dao/ChatParticipantDao.kt` (Lines 28-32)

**Issue:**
```kotlin
@Query("""
    SELECT DISTINCT h.* FROM handles h
    INNER JOIN chat_handle_cross_ref chr ON h.id = chr.handle_id
    WHERE chr.chat_guid IN (:chatGuids)
""")
suspend fun getParticipantsForChats(chatGuids: List<String>): List<HandleEntity>
```

**Problem:** Junction table has unique constraint. DISTINCT adds O(n log n) sorting overhead for no benefit.

**Fix:** Remove DISTINCT.

---

### 4. OR Conditions Prevent Index Use

**Location:** `data/local/db/dao/ChatDeleteDao.kt` (Lines 28-33)

**Issue:**
```kotlin
@Query("""
    DELETE FROM chats
    WHERE guid LIKE 'iMessage%'
    OR (guid NOT LIKE 'sms:%' AND guid NOT LIKE 'mms:%')
""")
```

**Problem:** OR between LIKE patterns prevents efficient index use. Causes full table scan.

**Fix:** Consider adding a `message_source` column to avoid pattern matching:
```kotlin
@Query("DELETE FROM chats WHERE message_source = 'IMESSAGE'")
```

---

### 5. OR in MIME Type Queries

**Location:** `data/local/db/dao/AttachmentDao.kt` (Multiple: Lines 61, 81, 94, 104-105, 144-147)

**Issue:**
```kotlin
WHERE a.mime_type LIKE 'image/%' OR a.mime_type LIKE 'video/%'
```

**Problem:** Multiple LIKE with OR reduces index efficiency.

**Fix:** Add composite index and consider `media_category` column:
```kotlin
@Entity(
    indices = [
        Index(value = ["mime_type"]),
        Index(value = ["message_guid", "mime_type"])
    ]
)
```

---

### 6. Inefficient NOT IN Subquery

**Location:** `data/local/db/dao/UnifiedChatGroupDao.kt` (Lines 313-317)

**Issue:**
```kotlin
@Query("""
    DELETE FROM unified_chat_groups
    WHERE id NOT IN (SELECT DISTINCT group_id FROM unified_chat_members)
""")
```

**Problem:** NOT IN with DISTINCT subquery is less efficient than NOT EXISTS.

**Fix:**
```kotlin
@Query("""
    DELETE FROM unified_chat_groups
    WHERE NOT EXISTS (SELECT 1 FROM unified_chat_members WHERE group_id = unified_chat_groups.id)
""")
```

---

## Schema Anti-Patterns

### 7. Missing Composite Indices

**Location:** `core/model/.../MessageEntity.kt`

**Missing high-value indices:**
```kotlin
// Would improve common filters:
Index(value = ["chat_guid", "is_from_me", "date_deleted"]),
Index(value = ["chat_guid", "has_attachments", "date_deleted"]),
Index(value = ["date_deleted", "date_created"])
```

---

### 8. Duplicate DAO Methods - ✅ FIXED

**Location:** `data/local/db/dao/MessageDao.kt` (Lines 611-612)

**Status:** FIXED - Removed `deleteMessageByGuid()` duplicate.

**Fixed Implementation:**
```kotlin
@Query("DELETE FROM messages WHERE guid = :guid")
suspend fun deleteMessage(guid: String)
```

---

## Transaction Anti-Patterns

### 9. Transaction Scope Too Wide

**Location:** `data/local/db/dao/UnifiedChatGroupDao.kt` (Lines 133-152)

**Issue:**
```kotlin
@Transaction
suspend fun getOrCreateGroup(group: UnifiedChatGroupEntity): UnifiedChatGroupEntity {
    val existing = getGroupByIdentifier(group.identifier)
    if (existing != null) return existing

    val insertedId = insertGroupIfNotExists(group)
    if (insertedId > 0) return group.copy(id = insertedId)

    // This query is inside transaction but doesn't need to be
    return getGroupByIdentifier(group.identifier)
        ?: throw IllegalStateException("...")
}
```

**Problem:** Holds database lock longer than necessary.

**Fix:** Exit transaction before final query.

---

## Summary Table

| Issue | Severity | Category | File |
|-------|----------|----------|------|
| Missing schema exports | MEDIUM | Migration | Schemas 25,26,28,30 |
| System.currentTimeMillis() | MEDIUM | Migration | DatabaseMigrations.kt |
| Unnecessary DISTINCT | LOW | Query | ChatParticipantDao.kt |
| OR prevents index | MEDIUM | Query | ChatDeleteDao.kt |
| OR in mime_type | LOW | Query | AttachmentDao.kt |
| NOT IN subquery | LOW | Query | UnifiedChatGroupDao.kt |
| Missing composite indices | MEDIUM | Schema | MessageEntity.kt |
| Duplicate DAO methods | LOW | Code | MessageDao.kt |
| Transaction too wide | LOW | Transaction | UnifiedChatGroupDao.kt |

---

## Positive Patterns

- Cursor-based pagination for main scroll (not OFFSET)
- FTS5 for text search with graceful fallback
- Proper use of `@Transaction` for multi-table operations
- Room Flow for reactive queries
- Proper foreign key definitions with CASCADE
