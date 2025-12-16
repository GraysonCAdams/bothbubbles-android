# Message Export Service

## Purpose

Export message history in various formats (PDF, HTML) and backup/restore SMS messages.

## Files

| File | Description |
|------|-------------|
| `ExportModels.kt` | Data models for export configuration |
| `HtmlExporter.kt` | Export messages to HTML format |
| `MessageExportService.kt` | Orchestrates export process |
| `MessageExporter.kt` | Base exporter interface |
| `PdfExporter.kt` | Export messages to PDF format |
| `SmsBackupModels.kt` | Models for SMS backup format |
| `SmsBackupService.kt` | Backup SMS to standard XML format |
| `SmsRestoreService.kt` | Restore SMS from backup |

## Architecture

```
Export Flow:

ChatSelection → MessageExportService
                ├── Query messages from DB
                ├── Load attachments
                ├── Format with selected exporter
                │   ├── PdfExporter
                │   └── HtmlExporter
                └── Save to chosen location

Backup Flow:

SmsBackupService → Query SMS from ContentProvider
                 → Convert to XML format
                 → Save backup file

SmsRestoreService → Parse XML backup
                  → Insert into ContentProvider
```

## Required Patterns

### Export Interface

```kotlin
interface MessageExporter {
    suspend fun export(
        messages: List<Message>,
        chat: Chat,
        options: ExportOptions
    ): Uri
}

class PdfExporter : MessageExporter {
    override suspend fun export(...): Uri {
        // Generate PDF document
    }
}
```

### Export Options

```kotlin
data class ExportOptions(
    val includeAttachments: Boolean = true,
    val includeTimestamps: Boolean = true,
    val dateRange: DateRange? = null,
    val format: ExportFormat = ExportFormat.PDF
)
```

### SMS Backup Format

Uses standard SMS backup XML format for compatibility:

```xml
<smses count="100">
    <sms protocol="0" address="+1234567890"
         date="1234567890123" type="1"
         body="Message text" read="1" />
</smses>
```

## Best Practices

1. Support standard backup formats for interoperability
2. Handle large exports with streaming
3. Compress attachments in exports
4. Show progress for long exports
5. Allow date range filtering
