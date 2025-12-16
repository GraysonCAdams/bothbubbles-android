# SMS/MMS Service

## Purpose

Native Android SMS and MMS integration. Handles sending, receiving, and syncing SMS/MMS messages when the app is the default SMS handler.

## Files

| File | Description |
|------|-------------|
| `Android14Utils.kt` | Android 14+ specific SMS utilities |
| `HeadlessSmsSendService.kt` | Service for sending SMS without UI |
| `MmsBroadcastReceiver.kt` | Receive MMS delivery broadcasts |
| `MmsContentTypeHandler.kt` | Handle MMS content types |
| `MmsImageCompressor.kt` | Compress images for MMS size limits |
| `MmsPduBuilder.kt` | Build MMS PDU for sending |
| `MmsPduEncodingHelpers.kt` | PDU encoding utilities |
| `MmsPduHeaders.kt` | MMS PDU header constants |
| `MmsPduParser.kt` | Parse incoming MMS PDUs |
| `MmsSendService.kt` | Send MMS messages |
| `SmsBroadcastReceiver.kt` | Receive SMS delivery broadcasts |
| `SmsContentObserver.kt` | Monitor SMS content provider for external changes |
| `SmsContentProvider.kt` | SMS content provider implementation |
| `SmsContentProviderHelpers.kt` | Content provider utilities |
| `SmsDataModels.kt` | SMS/MMS data models |
| `SmsErrorHelper.kt` | Map SMS error codes to user messages |
| `SmsPermissionHelper.kt` | Check SMS-related permissions |
| `SmsProviderChangedReceiver.kt` | Detect default SMS app changes |
| `SmsSendService.kt` | Send SMS messages |
| `SmsStatusReceiver.kt` | Receive SMS status updates |

## Architecture

```
SMS Architecture:

┌─────────────────────────────────────────────────────────────┐
│                    SMS Send Flow                            │
├─────────────────────────────────────────────────────────────┤
│ SmsSendService → SmsManager.sendTextMessage()               │
│              → SmsBroadcastReceiver (delivery status)       │
│              → Update message status in DB                  │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                    MMS Send Flow                            │
├─────────────────────────────────────────────────────────────┤
│ MmsSendService → MmsImageCompressor (if needed)             │
│              → MmsPduBuilder (build PDU)                    │
│              → SmsManager.sendMultimediaMessage()           │
│              → MmsBroadcastReceiver (delivery status)       │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                    SMS Receive Flow                         │
├─────────────────────────────────────────────────────────────┤
│ SmsReceiver → Parse incoming SMS                            │
│            → Store in ContentProvider                       │
│            → Store in local database                        │
│            → Show notification                              │
└─────────────────────────────────────────────────────────────┘
```

## Required Patterns

### SMS Sending

```kotlin
class SmsSendService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao
) {
    fun send(recipient: String, text: String): Result<Unit> {
        val smsManager = context.getSystemService(SmsManager::class.java)

        val sentIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_SMS_SENT).putExtra(EXTRA_MESSAGE_ID, messageId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deliveryIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_SMS_DELIVERED).putExtra(EXTRA_MESSAGE_ID, messageId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        smsManager.sendTextMessage(
            recipient,
            null,
            text,
            sentIntent,
            deliveryIntent
        )

        return Result.success(Unit)
    }
}
```

### MMS Building

```kotlin
class MmsPduBuilder @Inject constructor() {
    fun build(
        recipients: List<String>,
        text: String?,
        attachments: List<MmsAttachment>
    ): ByteArray {
        val pdu = SendReq()
        pdu.to = recipients.map { EncodedStringValue(it) }.toTypedArray()
        pdu.subject = EncodedStringValue("")

        val body = PduBody()

        // Add text part
        text?.let {
            body.addPart(buildTextPart(it))
        }

        // Add attachment parts
        attachments.forEach { attachment ->
            body.addPart(buildAttachmentPart(attachment))
        }

        pdu.body = body
        return PduComposer(context, pdu).make()
    }
}
```

### Content Observer

```kotlin
class SmsContentObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smsRepository: SmsRepository
) : ContentObserver(Handler(Looper.getMainLooper())) {

    fun startObserving() {
        context.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            this
        )
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        // Detect external SMS (Android Auto, Google Assistant, etc.)
        scope.launch {
            smsRepository.syncExternalMessages()
        }
    }
}
```

## Best Practices

1. Check default SMS app status before sending
2. Handle multipart SMS for long messages
3. Compress images for MMS size limits (typically 1MB)
4. Use content observer to detect external SMS
5. Handle carrier-specific quirks
6. Store in both ContentProvider and local DB
