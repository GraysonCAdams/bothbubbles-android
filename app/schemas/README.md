# Room Database Migrations

This directory contains exported Room database schemas used for migration testing.

## Schema Files

Each JSON file represents a specific database version. Files are auto-generated when building the app and should be committed to version control.

Current database version: **51** (see `BothBubblesDatabase.kt` for latest version)

## Adding New Columns or Tables

When you need to modify the database schema, follow these steps:

### 1. Update the Entity

Add the new column to the entity class with appropriate defaults:

```kotlin
// For nullable columns
@ColumnInfo(name = "new_nullable_column")
val newNullableColumn: String? = null

// For non-null columns with defaults
@ColumnInfo(name = "new_required_column", defaultValue = "0")
val newRequiredColumn: Int = 0
```

### 2. Increment the Database Version

In `BlueBubblesDatabase.kt`, increment the version number:

```kotlin
@Database(
    entities = [...],
    version = 4,  // Was 3, now 4
    exportSchema = true
)
```

### 3. Create the Migration

Add a new migration object in `BlueBubblesDatabase.kt`:

```kotlin
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // For nullable columns
        db.execSQL("ALTER TABLE table_name ADD COLUMN new_nullable_column TEXT DEFAULT NULL")

        // For non-null columns (must have DEFAULT)
        db.execSQL("ALTER TABLE table_name ADD COLUMN new_required_column INTEGER NOT NULL DEFAULT 0")

        // For new indexes
        db.execSQL("CREATE INDEX IF NOT EXISTS index_table_column ON table_name(column_name)")
    }
}
```

### 4. Register the Migration

Add the new migration to `ALL_MIGRATIONS`:

```kotlin
val ALL_MIGRATIONS = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4  // Add new migration here
)
```

### 5. Build and Test

```bash
# Build to generate new schema file
./gradlew assembleDebug

# The new schema (e.g., 4.json) will be created in this directory
```

## Migration Rules

### SQLite ALTER TABLE Limitations

SQLite has limited `ALTER TABLE` support. You can only:
- Add new columns
- Rename tables
- Rename columns (SQLite 3.25+)

You **cannot**:
- Remove columns
- Change column types
- Add/remove constraints

For complex changes, you must recreate the table:

```kotlin
override fun migrate(db: SupportSQLiteDatabase) {
    // 1. Create new table with desired schema
    db.execSQL("""
        CREATE TABLE table_name_new (
            id INTEGER PRIMARY KEY NOT NULL,
            column1 TEXT,
            column2 INTEGER NOT NULL DEFAULT 0
        )
    """)

    // 2. Copy data from old table
    db.execSQL("""
        INSERT INTO table_name_new (id, column1, column2)
        SELECT id, column1, COALESCE(column2, 0) FROM table_name
    """)

    // 3. Drop old table
    db.execSQL("DROP TABLE table_name")

    // 4. Rename new table
    db.execSQL("ALTER TABLE table_name_new RENAME TO table_name")

    // 5. Recreate indexes
    db.execSQL("CREATE INDEX IF NOT EXISTS index_name ON table_name(column)")
}
```

### Column Defaults

- **Nullable columns**: Use `DEFAULT NULL`
- **Non-null INTEGER**: Use `DEFAULT 0` or appropriate value
- **Non-null TEXT**: Use `DEFAULT ''` or `DEFAULT 'value'`
- **Non-null REAL**: Use `DEFAULT 0.0`

### Testing Migrations

The schema JSON files enable automated migration testing:

```kotlin
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BlueBubblesDatabase::class.java
    )

    @Test
    fun migrate3To4() {
        // Create database at version 3
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL("INSERT INTO chats (guid) VALUES ('test')")
            close()
        }

        // Run migration to version 4
        helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)
    }
}
```

## Fallback Behavior

The app is configured with `fallbackToDestructiveMigrationOnDowngrade()`:

- **Missing migration**: App crashes (intentional - catches issues during development)
- **Downgrade**: Data is cleared (acceptable for rolling back app versions)

This ensures we never silently lose user data due to a missing migration.

## Version History

See `DatabaseMigrations.kt` for the complete migration history. The database has grown significantly with many tables and columns added over time.

Current version: **51**

Key entities include: ChatEntity, MessageEntity, HandleEntity, AttachmentEntity, PendingMessageEntity, ScheduledMessageEntity, UnifiedChatEntity, and more.
