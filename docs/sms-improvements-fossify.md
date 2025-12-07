# SMS/MMS Improvements Based on Fossify Messages Analysis

## Executive Summary

After studying the [Fossify Messages](https://github.com/FossifyOrg/Messages) repository and comparing it to BlueBubbles' SMS implementation, I've identified several areas for improvement. Fossify is a mature, dedicated SMS app with robust handling that BlueBubbles can learn from.

---

## Current State Comparison

| Feature | Fossify Messages | BlueBubbles | Gap |
|---------|------------------|-------------|-----|
| Default SMS App Request | RoleManager (Q+) + Telephony fallback | ✅ Already implemented via `SmsPermissionHelper` | None |
| SMS Import | XML/JSON backup restore with duplicate detection | ✅ System SMS import via ContentProvider | Missing backup/restore |
| SMS Reception | Direct PDU parsing via `SMS_DELIVER` | ✅ `SmsBroadcastReceiver` handles this | None |
| MMS Reception | Klinker library for full WAP Push handling | ⚠️ Relies on ContentObserver fallback | Partial |
| SMS Sending | Multi-part with per-part status tracking | ✅ Implemented in `SmsSendService` | None |
| MMS Sending | Klinker library (deprecated but works) | ⚠️ Custom PDU builder, may have edge cases | Possible issues |
| Delivery Reports | Handles CDMA/GSM differences | ✅ Basic implementation | Missing CDMA handling |
| Spam Filtering | Keyword blocking + unknown number blocking | ✅ SpamRepository integration | None |
| Dual-SIM | Full support with per-message SIM selection | ✅ Implemented | None |
| Message Backup/Export | Full XML/JSON export with progress | ❌ Not implemented | Missing feature |
| Scheduled Messages | Full support with database table | ⚠️ `ScheduledMessageWorker` exists but limited | Partial |

---

## Recommended Improvements

### Priority 1: MMS Reception Robustness

**Current Issue:** BlueBubbles' `MmsBroadcastReceiver` mostly relies on `SmsContentObserver` to detect MMS messages after the system processes them. This can miss messages or have delays.

**Fossify Approach:** Uses Klinker's `MmsReceivedReceiver` base class which handles WAP Push PDU parsing directly.

**Recommendation:**
1. Integrate [android-smsmms library](https://github.com/klinker41/android-smsmms) for MMS handling
2. Parse MMS PDUs directly in the BroadcastReceiver
3. Keep ContentObserver as backup/verification layer

**Files to modify:**
- [MmsBroadcastReceiver.kt](app/src/main/kotlin/com/bluebubbles/services/sms/MmsBroadcastReceiver.kt)
- Add dependency to `build.gradle.kts`

**Implementation:**
```kotlin
// Replace current basic receiver with Klinker-based approach
class MmsBroadcastReceiver : MmsReceivedReceiver() {

    override fun isAddressBlocked(context: Context, address: String): Boolean {
        return runBlocking {
            context.spamRepository.isBlocked(address.normalizePhoneNumber())
        }
    }

    override fun onMessageReceived(context: Context, messageUri: Uri) {
        // Now we have the full MMS message processed
        ensureBackgroundThread {
            val mms = getMmsFromUri(context, messageUri)
            handleIncomingMms(context, mms)
        }
    }

    override fun onError(context: Context, error: String) {
        Timber.e("MMS receive error: $error")
    }
}
```

---

### Priority 2: Delivery Report CDMA Handling

**Current Issue:** `SmsStatusReceiver` treats all delivery status codes the same, but CDMA and GSM have different PDU formats.

**Fossify Approach:** Parses PDU bytes to extract status, handling both 3GPP (GSM) and 3GPP2 (CDMA) formats.

**Recommendation:** Update delivery status receiver to handle both network types.

**Files to modify:**
- [SmsStatusReceiver.kt](app/src/main/kotlin/com/bluebubbles/services/sms/SmsStatusReceiver.kt)

**Implementation:**
```kotlin
private fun extractDeliveryStatus(intent: Intent): Int {
    val pdu = intent.getByteArrayExtra("pdu") ?: return Sms.STATUS_NONE
    val format = intent.getStringExtra("format") ?: return Sms.STATUS_NONE

    return try {
        if (format == "3gpp2") {
            // CDMA format - parse differently
            val message = SmsMessage.createFromPdu(pdu, format)
            val errorClass = message.status and 0x03
            when {
                errorClass == 0x00 && message.status == 0x02 -> Sms.STATUS_COMPLETE
                errorClass == 0x02 -> Sms.STATUS_PENDING
                errorClass == 0x03 -> Sms.STATUS_FAILED
                else -> Sms.STATUS_PENDING
            }
        } else {
            // GSM format - use result code directly
            resultCode
        }
    } catch (e: Exception) {
        Timber.e(e, "Failed to parse delivery PDU")
        Sms.STATUS_NONE
    }
}
```

---

### Priority 3: SMS/MMS Backup & Restore

**Current Issue:** BlueBubbles can import existing system SMS but cannot backup/restore messages independently.

**Fossify Approach:** Full XML export (compatible with SMS Backup & Restore app) and JSON export with:
- Progress tracking
- Duplicate detection on restore
- Support for both SMS and MMS with attachments

**Recommendation:** Add backup/restore functionality for local SMS/MMS.

**New files to create:**
- `app/src/main/kotlin/com/bluebubbles/services/export/SmsBackupService.kt`
- `app/src/main/kotlin/com/bluebubbles/services/export/SmsRestoreService.kt`
- `app/src/main/kotlin/com/bluebubbles/ui/settings/sms/SmsBackupScreen.kt`

**Backup format (JSON):**
```kotlin
@Serializable
data class SmsBackup(
    val exportDate: Long,
    val deviceModel: String,
    val appVersion: String,
    val messages: List<SmsMessageBackup>,
    val mmsMessages: List<MmsMessageBackup>
)

@Serializable
data class SmsMessageBackup(
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val type: Int,  // inbox, sent, draft
    val read: Boolean,
    val status: Int
)
```

**Duplicate detection (from Fossify):**
```kotlin
private fun smsExists(backup: SmsMessageBackup): Boolean {
    val selection = "${Sms.DATE} = ? AND ${Sms.ADDRESS} = ? AND ${Sms.TYPE} = ?"
    val args = arrayOf(backup.date.toString(), backup.address, backup.type.toString())

    return contentResolver.query(Sms.CONTENT_URI, arrayOf(Sms._ID), selection, args, null)
        ?.use { it.count > 0 } ?: false
}
```

---

### Priority 4: Improved Error Handling & User Feedback

**Current Issue:** Error handling in SMS sending is basic. Users may not understand why messages fail.

**Fossify Approach:** Maps all `SmsManager` error codes to user-friendly messages.

**Recommendation:** Add comprehensive error mapping and display.

**Files to modify:**
- [SmsSendService.kt](app/src/main/kotlin/com/bluebubbles/services/sms/SmsSendService.kt)
- [SmsStatusReceiver.kt](app/src/main/kotlin/com/bluebubbles/services/sms/SmsStatusReceiver.kt)

**Implementation:**
```kotlin
fun getSmsErrorMessage(context: Context, resultCode: Int, errorCode: Int): String {
    return when (resultCode) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
            when (errorCode) {
                SmsManager.RESULT_ERROR_NO_SERVICE -> context.getString(R.string.sms_error_no_service)
                SmsManager.RESULT_ERROR_NULL_PDU -> context.getString(R.string.sms_error_invalid_message)
                SmsManager.RESULT_ERROR_RADIO_OFF -> context.getString(R.string.sms_error_airplane_mode)
                else -> context.getString(R.string.sms_error_generic)
            }
        }
        SmsManager.RESULT_ERROR_NO_SERVICE -> context.getString(R.string.sms_error_no_service)
        SmsManager.RESULT_ERROR_RADIO_OFF -> context.getString(R.string.sms_error_airplane_mode)
        SmsManager.RESULT_NO_DEFAULT_SMS_APP -> context.getString(R.string.sms_error_not_default_app)
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> context.getString(R.string.sms_error_rate_limit)
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> context.getString(R.string.sms_error_short_code_blocked)
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> context.getString(R.string.sms_error_short_code_blocked)
        Activity.RESULT_OK -> null // Success
        else -> context.getString(R.string.sms_error_unknown, resultCode)
    }
}
```

---

### Priority 5: Notification Enhancements

**Current Issue:** SMS notifications may lack features users expect from a default SMS app.

**Fossify Features:**
- Lock screen visibility options (show sender+message, sender only, nothing)
- Inline reply action with RemoteInput
- Mark as read action
- Conversation grouping with MessagingStyle

**Recommendation:** Verify all these features are implemented; add any missing ones.

**Files to modify:**
- [SmsNotificationService.kt](app/src/main/kotlin/com/bluebubbles/services/notifications/) (if exists, or create)
- [SettingsDataStore.kt](app/src/main/kotlin/com/bluebubbles/data/local/prefs/SettingsDataStore.kt) - add lock screen preference

**Implementation for lock screen visibility:**
```kotlin
// Add to SettingsDataStore
val lockScreenVisibility = intPreference("lock_screen_sms_visibility", VISIBILITY_PRIVATE)

object LockScreenVisibility {
    const val SHOW_ALL = 0      // Sender + message content
    const val SHOW_SENDER = 1   // Sender only, hide content
    const val SHOW_NOTHING = 2  // "New message" only
}

// In notification builder
when (settings.lockScreenVisibility) {
    LockScreenVisibility.SHOW_ALL -> setVisibility(VISIBILITY_PRIVATE)
    LockScreenVisibility.SHOW_SENDER -> {
        setVisibility(VISIBILITY_PUBLIC)
        setPublicVersion(createSenderOnlyNotification(senderName))
    }
    LockScreenVisibility.SHOW_NOTHING -> setVisibility(VISIBILITY_SECRET)
}
```

---

### Priority 6: MMS PDU Builder Improvements

**Current Issue:** `MmsSendService.buildMmsPdu()` is a simplified implementation that may not handle all edge cases.

**Fossify Approach:** Uses Klinker library which handles:
- Proper MIME multipart encoding
- Correct content-type headers
- Address encoding for international characters
- Attachment size limits and compression

**Recommendation:** Either integrate Klinker library or improve the existing PDU builder.

**Key improvements needed:**
```kotlin
// Current: Basic PDU construction
// Improved: Full MIME-compliant PDU with proper encoding

class MmsPduBuilder(private val context: Context) {

    fun build(
        recipients: List<String>,
        text: String?,
        attachments: List<Attachment>,
        subId: Int
    ): ByteArray {
        val pdu = GenericPdu()

        // Proper recipient encoding
        recipients.forEach { recipient ->
            val encodedAddress = encodeAddress(recipient)
            pdu.addTo(encodedAddress)
        }

        // Handle text as attachment
        text?.let {
            pdu.addPart(createTextPart(it))
        }

        // Handle media attachments with size limits
        attachments.forEach { attachment ->
            val compressedData = compressIfNeeded(attachment)
            pdu.addPart(createMediaPart(attachment.mimeType, compressedData))
        }

        // Set carrier-specific headers
        applyCarrierConfig(pdu, subId)

        return PduComposer(context, pdu).make()
    }

    private fun compressIfNeeded(attachment: Attachment): ByteArray {
        val maxSize = getCarrierMmsLimit() // Usually 300KB-1MB
        // Compress images if over limit
        // Transcode videos if needed
    }
}
```

---

### Priority 7: Android 14+ Compatibility

**Current Issue:** Android 14 introduced stricter restrictions on SMS/MMS permissions and subscription IDs.

**Fossify Workaround:** Resets subscription IDs to -1 when importing on Android 14+.

**Recommendation:** Add similar handling for import and ensure all SmsManager calls handle the new restrictions.

**Files to modify:**
- [SmsRepository.kt](app/src/main/kotlin/com/bluebubbles/data/repository/SmsRepository.kt)
- [SmsSendService.kt](app/src/main/kotlin/com/bluebubbles/services/sms/SmsSendService.kt)

```kotlin
// When writing to SMS ContentProvider on Android 14+
private fun sanitizeForAndroid14(values: ContentValues) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // Remove subscription_id to avoid permission issues
        values.remove(Sms.SUBSCRIPTION_ID)
    }
}
```

---

## Implementation Phases

### Phase 1: Critical Fixes (Week 1)
1. **CDMA delivery report handling** - Easy win, prevents status display issues
2. **Android 14+ compatibility** - Important for newer devices
3. **Error message improvements** - Better user experience

### Phase 2: MMS Reliability (Week 2)
1. **Evaluate Klinker library integration** - Test with various carriers
2. **Improve MMS PDU builder** if not using library
3. **Add MMS receive reliability improvements**

### Phase 3: User Features (Week 3)
1. **Notification enhancements** - Lock screen visibility, messaging style
2. **SMS/MMS backup & restore** - New feature, valuable for users
3. **Settings UI for new options**

---

## Dependencies to Consider

### Klinker android-smsmms Library

**Pros:**
- Battle-tested MMS handling
- Handles carrier-specific quirks
- Active community (used by many SMS apps)

**Cons:**
- Marked as deprecated by author (but still functional)
- Adds ~500KB to APK size
- May conflict with existing implementation

**Recommendation:** Evaluate carefully. If BlueBubbles' current MMS implementation works well in testing, improvements to existing code may be preferable to adding a dependency.

```kotlin
// If using Klinker
implementation("com.klinkerapps:android-smsmms:5.2.6")
```

---

## Testing Plan

### Unit Tests
- [ ] CDMA delivery status parsing
- [ ] Error code to message mapping
- [ ] Phone number normalization (already exists, verify coverage)
- [ ] Backup file serialization/deserialization
- [ ] Duplicate detection logic

### Integration Tests
- [ ] Send SMS on GSM network, verify delivery status
- [ ] Send SMS on CDMA network, verify delivery status
- [ ] Receive MMS with multiple attachments
- [ ] Backup and restore 100+ messages
- [ ] Handle Android 14 restrictions

### Manual Testing Matrix
| Carrier | SMS Send | SMS Receive | MMS Send | MMS Receive | Dual-SIM |
|---------|----------|-------------|----------|-------------|----------|
| T-Mobile | | | | | |
| AT&T | | | | | |
| Verizon | | | | | |
| International | | | | | |

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Klinker library conflicts with existing code | High | Evaluate in isolated branch first |
| Carrier-specific MMS issues | Medium | Extensive carrier testing |
| Breaking existing functionality | High | Feature flags for new implementations |
| Android 14+ restrictions block features | Medium | Graceful degradation, clear user messaging |

---

## Files Summary

| File | Action | Priority |
|------|--------|----------|
| `SmsStatusReceiver.kt` | Modify - add CDMA handling | P1 |
| `SmsSendService.kt` | Modify - improve error handling | P1 |
| `MmsBroadcastReceiver.kt` | Modify or replace - improve reliability | P2 |
| `MmsSendService.kt` | Modify - improve PDU builder | P2 |
| `SmsBackupService.kt` | Create - new backup feature | P3 |
| `SmsRestoreService.kt` | Create - new restore feature | P3 |
| `SmsBackupScreen.kt` | Create - new UI | P3 |
| `SmsNotificationService.kt` | Modify - notification enhancements | P3 |
| `SettingsDataStore.kt` | Modify - add new preferences | P3 |
| `build.gradle.kts` | Modify - if adding Klinker library | P2 |

---

## Questions to Resolve

1. **Is MMS sending currently reliable?** - Need real-world testing data before deciding on Klinker integration
2. **Are there known CDMA delivery status issues?** - If not, P2 may be lower priority
3. **User demand for backup/restore?** - Feature request frequency should influence priority
4. **Carrier-specific issues reported?** - May influence MMS PDU builder priorities

---

## Conclusion

BlueBubbles' SMS implementation is already quite comprehensive. The main improvements from Fossify focus on:

1. **Edge case handling** - CDMA networks, Android 14+, carrier quirks
2. **MMS reliability** - Better PDU parsing for both send and receive
3. **User features** - Backup/restore, notification customization
4. **Error messaging** - Helping users understand failures

The existing architecture is solid; these are incremental improvements rather than fundamental changes.
