package com.bothbubbles.services.export

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Telephony
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsBackupService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val EXPORT_DIR = "BlueBubbles"
        private const val TAG = "SmsBackupService"
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val _backupProgress = MutableStateFlow<SmsBackupProgress>(SmsBackupProgress.Idle)
    val backupProgress: StateFlow<SmsBackupProgress> = _backupProgress.asStateFlow()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())

    /**
     * Export all SMS/MMS messages to a JSON backup file.
     * Returns a Flow that emits progress updates.
     */
    fun exportBackup(): Flow<SmsBackupProgress> = flow {
        try {
            _backupProgress.value = SmsBackupProgress.Exporting(0, 0, "Counting messages...")
            emit(SmsBackupProgress.Exporting(0, 0, "Counting messages..."))

            // Count total messages for progress
            val smsCount = countSmsMessages()
            val mmsCount = countMmsMessages()
            val totalMessages = smsCount + mmsCount

            if (totalMessages == 0) {
                val error = SmsBackupProgress.Error("No messages to backup")
                _backupProgress.value = error
                emit(error)
                return@flow
            }

            // Export SMS messages
            val smsMessages = mutableListOf<SmsMessageBackup>()
            var processed = 0

            val progress1 = SmsBackupProgress.Exporting(0, totalMessages, "Exporting SMS messages...")
            _backupProgress.value = progress1
            emit(progress1)

            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                null,
                null,
                null,
                "${Telephony.Sms.DATE} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()

                    smsMessages.add(cursorToSmsBackup(cursor))
                    processed++

                    if (processed % 100 == 0) {
                        val progress = SmsBackupProgress.Exporting(processed, totalMessages, "Exporting SMS messages...")
                        _backupProgress.value = progress
                        emit(progress)
                    }
                }
            }

            // Export MMS messages
            val mmsMessages = mutableListOf<MmsMessageBackup>()

            val progress2 = SmsBackupProgress.Exporting(processed, totalMessages, "Exporting MMS messages...")
            _backupProgress.value = progress2
            emit(progress2)

            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                null,
                null,
                null,
                "${Telephony.Mms.DATE} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()

                    val mmsId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms._ID))
                    mmsMessages.add(cursorToMmsBackup(cursor, mmsId))
                    processed++

                    if (processed % 50 == 0) {
                        val progress = SmsBackupProgress.Exporting(processed, totalMessages, "Exporting MMS messages...")
                        _backupProgress.value = progress
                        emit(progress)
                    }
                }
            }

            currentCoroutineContext().ensureActive()

            // Create backup object
            val backup = SmsBackup(
                exportDate = System.currentTimeMillis(),
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                appVersion = getAppVersion(),
                smsCount = smsMessages.size,
                mmsCount = mmsMessages.size,
                messages = smsMessages,
                mmsMessages = mmsMessages
            )

            // Serialize to JSON
            val progress3 = SmsBackupProgress.Exporting(totalMessages, totalMessages, "Creating backup file...")
            _backupProgress.value = progress3
            emit(progress3)

            val jsonContent = json.encodeToString(backup)

            // Save to file
            val timestamp = dateFormat.format(Date())
            val fileName = "BlueBubbles_SMS_Backup_$timestamp.json"

            val savingProgress = SmsBackupProgress.Saving(fileName)
            _backupProgress.value = savingProgress
            emit(savingProgress)

            val tempFile = File(context.cacheDir, fileName)
            tempFile.writeText(jsonContent)

            val savedUri = saveToDownloads(tempFile, fileName)
            tempFile.delete()

            if (savedUri != null) {
                val completeProgress = SmsBackupProgress.Complete(
                    filePath = savedUri,
                    fileName = fileName,
                    smsCount = smsMessages.size,
                    mmsCount = mmsMessages.size
                )
                _backupProgress.value = completeProgress
                emit(completeProgress)
            } else {
                val error = SmsBackupProgress.Error("Failed to save backup file")
                _backupProgress.value = error
                emit(error)
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            _backupProgress.value = SmsBackupProgress.Cancelled
            emit(SmsBackupProgress.Cancelled)
            throw e
        } catch (e: Exception) {
            val error = SmsBackupProgress.Error(e.message ?: "Backup failed")
            _backupProgress.value = error
            emit(error)
        }
    }

    private fun countSmsMessages(): Int {
        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("COUNT(*) as count"),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        } ?: 0
    }

    private fun countMmsMessages(): Int {
        return context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf("COUNT(*) as count"),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        } ?: 0
    }

    private fun cursorToSmsBackup(cursor: Cursor): SmsMessageBackup {
        return SmsMessageBackup(
            address = cursor.getStringOrEmpty(Telephony.Sms.ADDRESS),
            body = cursor.getStringOrNull(Telephony.Sms.BODY),
            date = cursor.getLongOrDefault(Telephony.Sms.DATE, 0),
            dateSent = cursor.getLongOrDefault(Telephony.Sms.DATE_SENT, 0),
            type = cursor.getIntOrDefault(Telephony.Sms.TYPE, 1),
            read = cursor.getIntOrDefault(Telephony.Sms.READ, 0) == 1,
            status = cursor.getIntOrDefault(Telephony.Sms.STATUS, -1),
            threadId = cursor.getLongOrNull(Telephony.Sms.THREAD_ID),
            subscriptionId = cursor.getIntOrDefault(Telephony.Sms.SUBSCRIPTION_ID, -1)
        )
    }

    private fun cursorToMmsBackup(cursor: Cursor, mmsId: Long): MmsMessageBackup {
        return MmsMessageBackup(
            addresses = getMmsAddresses(mmsId),
            date = cursor.getLongOrDefault(Telephony.Mms.DATE, 0) * 1000, // MMS dates are in seconds
            dateSent = cursor.getLongOrDefault(Telephony.Mms.DATE_SENT, 0) * 1000,
            messageBox = cursor.getIntOrDefault(Telephony.Mms.MESSAGE_BOX, 1),
            read = cursor.getIntOrDefault(Telephony.Mms.READ, 0) == 1,
            subject = cursor.getStringOrNull(Telephony.Mms.SUBJECT),
            textParts = getMmsTextParts(mmsId),
            attachments = getMmsAttachments(mmsId),
            threadId = cursor.getLongOrNull(Telephony.Mms.THREAD_ID)
        )
    }

    private fun getMmsAddresses(mmsId: Long): List<MmsAddressBackup> {
        val addresses = mutableListOf<MmsAddressBackup>()

        context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val address = cursor.getString(0)
                if (!address.isNullOrBlank() && !address.contains("insert-address-token")) {
                    addresses.add(
                        MmsAddressBackup(
                            address = address,
                            type = cursor.getInt(1)
                        )
                    )
                }
            }
        }

        return addresses
    }

    private fun getMmsTextParts(mmsId: Long): List<String> {
        val parts = mutableListOf<String>()

        context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/part"),
            arrayOf("_id", "ct", "text"),
            "ct = 'text/plain'",
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(2)?.let { parts.add(it) }
            }
        }

        return parts
    }

    private fun getMmsAttachments(mmsId: Long): List<MmsAttachmentBackup> {
        val attachments = mutableListOf<MmsAttachmentBackup>()

        context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/part"),
            arrayOf("_id", "ct", "name", "_data"),
            "ct LIKE 'image/%' OR ct LIKE 'video/%' OR ct LIKE 'audio/%'",
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val partId = cursor.getLong(0)
                val contentType = cursor.getString(1) ?: "application/octet-stream"
                val fileName = cursor.getString(2)

                // Read attachment data and encode as Base64
                val data = try {
                    context.contentResolver.openInputStream(
                        Uri.parse("content://mms/part/$partId")
                    )?.use { inputStream ->
                        Base64.encodeToString(inputStream.readBytes(), Base64.NO_WRAP)
                    } ?: ""
                } catch (e: Exception) {
                    ""
                }

                if (data.isNotEmpty()) {
                    attachments.add(
                        MmsAttachmentBackup(
                            contentType = contentType,
                            fileName = fileName,
                            data = data
                        )
                    )
                }
            }
        }

        return attachments
    }

    private suspend fun saveToDownloads(file: File, fileName: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_DIR")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }

                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return@withContext null

                    resolver.openOutputStream(uri)?.use { outputStream ->
                        file.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)

                    uri.toString()
                } else {
                    @Suppress("DEPRECATION")
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )
                    val exportDir = File(downloadsDir, EXPORT_DIR)
                    exportDir.mkdirs()

                    val destFile = File(exportDir, fileName)
                    file.copyTo(destFile, overwrite = true)
                    destFile.absolutePath
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun getAppVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    fun resetProgress() {
        _backupProgress.value = SmsBackupProgress.Idle
    }

    // Cursor extension functions
    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.getStringOrEmpty(column: String): String {
        return getStringOrNull(column) ?: ""
    }

    private fun Cursor.getIntOrDefault(column: String, default: Int): Int {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else default
    }

    private fun Cursor.getLongOrDefault(column: String, default: Long): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else default
    }

    private fun Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }
}
