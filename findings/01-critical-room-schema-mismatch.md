# CRITICAL: Room Database Schema Mismatch

## Severity: CRITICAL (App Crashes)

## Timestamp
- 2025-12-17 00:04:45 (first occurrence)
- 2025-12-17 00:06:16 (repeat crash)

## Error
```
java.lang.IllegalStateException: Room cannot verify the data integrity.
Looks like you've changed schema but forgot to update the version number.
You can simply fix this by increasing the version number.

Expected identity hash: d4350dc3aa6f35f0eeb6a3e116996f14
Found identity hash: e0bedd5e4685778ca13a941f2906e7d4
```

## Stack Trace
```
at androidx.room.RoomOpenHelper.checkIdentity(RoomOpenHelper.kt:146)
at androidx.room.RoomOpenHelper.onOpen(RoomOpenHelper.kt:127)
at androidx.sqlite.db.framework.FrameworkSQLiteOpenHelper$OpenHelper.onOpen(FrameworkSQLiteOpenHelper.kt:287)
at android.database.sqlite.SQLiteOpenHelper.getDatabaseLocked(SQLiteOpenHelper.java:444)
at android.database.sqlite.SQLiteOpenHelper.getWritableDatabase(SQLiteOpenHelper.java:332)
...
at com.bothbubbles.data.local.db.dao.QuickReplyTemplateDao_Impl$21.call(QuickReplyTemplateDao_Impl.java:579)
```

## Affected Process
- `com.bothbubbles.messaging:acra`

## Root Cause Analysis
The database schema has been modified but the version number in `AppDatabase.kt` was not incremented. This causes Room to detect a schema mismatch and crash the app.

The crash occurs when accessing `QuickReplyTemplateDao`, specifically in:
- `QuickReplyTemplateDao_Impl.java:579` (first crash)
- `QuickReplyTemplateDao_Impl.java:387` (second crash)

## Recommended Fix
1. Review recent changes to database entities, especially any related to `QuickReplyTemplate`
2. Increment the database version in `AppDatabase.kt`
3. Add proper migration if data preservation is needed
4. OR use `.fallbackToDestructiveMigration()` if data loss is acceptable

## Files to Check
- `app/src/main/kotlin/com/bothbubbles/data/local/db/AppDatabase.kt`
- `app/src/main/kotlin/com/bothbubbles/data/local/db/entity/` (QuickReplyTemplate entity)
- `app/schemas/` (schema export files)
