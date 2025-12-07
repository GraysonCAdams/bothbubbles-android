package com.bothbubbles.services.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
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
import kotlinx.serialization.json.Json
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsRestoreService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SmsRestoreService"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _restoreProgress = MutableStateFlow<SmsRestoreProgress>(SmsRestoreProgress.Idle)
    val restoreProgress: StateFlow<SmsRestoreProgress> = _restoreProgress.asStateFlow()

    /**
     * Restore SMS/MMS messages from a backup file URI.
     * Performs duplicate detection based on date, address, and type.
     * Returns a Flow that emits progress updates.
     */
    fun restoreBackup(fileUri: Uri): Flow<SmsRestoreProgress> = flow {
        try {
            _restoreProgress.value = SmsRestoreProgress.Reading("backup file")
            emit(SmsRestoreProgress.Reading("backup file"))

            // Read and parse backup file
            val backup = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                    parseBackup(inputStream)
                }
            }

            if (backup == null) {
                val error = SmsRestoreProgress.Error("Failed to read backup file")
                _restoreProgress.value = error
                emit(error)
                return@flow
            }

            val totalMessages = backup.messages.size + backup.mmsMessages.size
            if (totalMessages == 0) {
                val error = SmsRestoreProgress.Error("No messages found in backup")
                _restoreProgress.value = error
                emit(error)
                return@flow
            }

            var smsRestored = 0
            var mmsRestored = 0
            var duplicatesSkipped = 0
            var processed = 0

            // Restore SMS messages
            val progress1 = SmsRestoreProgress.Restoring(0, totalMessages, 0)
            _restoreProgress.value = progress1
            emit(progress1)

            for (sms in backup.messages) {
                currentCoroutineContext().ensureActive()

                if (smsExists(sms)) {
                    duplicatesSkipped++
                } else {
                    if (insertSms(sms)) {
                        smsRestored++
                    }
                }

                processed++
                if (processed % 100 == 0) {
                    val progress = SmsRestoreProgress.Restoring(processed, totalMessages, duplicatesSkipped)
                    _restoreProgress.value = progress
                    emit(progress)
                }
            }

            // Restore MMS messages
            for (mms in backup.mmsMessages) {
                currentCoroutineContext().ensureActive()

                if (mmsExists(mms)) {
                    duplicatesSkipped++
                } else {
                    if (insertMms(mms)) {
                        mmsRestored++
                    }
                }

                processed++
                if (processed % 50 == 0) {
                    val progress = SmsRestoreProgress.Restoring(processed, totalMessages, duplicatesSkipped)
                    _restoreProgress.value = progress
                    emit(progress)
                }
            }

            val completeProgress = SmsRestoreProgress.Complete(
                smsRestored = smsRestored,
                mmsRestored = mmsRestored,
                duplicatesSkipped = duplicatesSkipped
            )
            _restoreProgress.value = completeProgress
            emit(completeProgress)

        } catch (e: kotlinx.coroutines.CancellationException) {
            _restoreProgress.value = SmsRestoreProgress.Cancelled
            emit(SmsRestoreProgress.Cancelled)
            throw e
        } catch (e: Exception) {
            val error = SmsRestoreProgress.Error(e.message ?: "Restore failed")
            _restoreProgress.value = error
            emit(error)
        }
    }

    private fun parseBackup(inputStream: InputStream): SmsBackup? {
        return try {
            val content = inputStream.bufferedReader().readText()
            json.decodeFromString<SmsBackup>(content)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if an SMS message already exists (duplicate detection).
     * Uses date, address, and type as unique identifiers.
     * This matches the Fossify approach.
     */
    private fun smsExists(backup: SmsMessageBackup): Boolean {
        val selection = "${Telephony.Sms.DATE} = ? AND ${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.TYPE} = ?"
        val args = arrayOf(backup.date.toString(), backup.address, backup.type.toString())

        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            selection,
            args,
            null
        )?.use { it.count > 0 } ?: false
    }

    /**
     * Check if an MMS message already exists (duplicate detection).
     * Uses date and message box as unique identifiers.
     */
    private fun mmsExists(backup: MmsMessageBackup): Boolean {
        // MMS dates are stored in seconds in the provider
        val dateInSeconds = backup.date / 1000
        val selection = "${Telephony.Mms.DATE} = ? AND ${Telephony.Mms.MESSAGE_BOX} = ?"
        val args = arrayOf(dateInSeconds.toString(), backup.messageBox.toString())

        return context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID),
            selection,
            args,
            null
        )?.use { it.count > 0 } ?: false
    }

    /**
     * Insert an SMS message into the system SMS database.
     */
    private fun insertSms(backup: SmsMessageBackup): Boolean {
        return try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, backup.address)
                put(Telephony.Sms.BODY, backup.body ?: "")
                put(Telephony.Sms.DATE, backup.date)
                put(Telephony.Sms.DATE_SENT, backup.dateSent)
                put(Telephony.Sms.TYPE, backup.type)
                put(Telephony.Sms.READ, if (backup.read) 1 else 0)
                put(Telephony.Sms.STATUS, backup.status)
                put(Telephony.Sms.SEEN, 1)

                // Android 14+ restriction: Don't set subscription_id
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    if (backup.subscriptionId >= 0) {
                        put(Telephony.Sms.SUBSCRIPTION_ID, backup.subscriptionId)
                    }
                }
            }

            context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Insert an MMS message into the system MMS database.
     * This is more complex as MMS has multiple tables (pdu, addr, part).
     */
    private fun insertMms(backup: MmsMessageBackup): Boolean {
        return try {
            // Insert into MMS PDU table
            val pduValues = ContentValues().apply {
                put(Telephony.Mms.DATE, backup.date / 1000) // MMS dates are in seconds
                put(Telephony.Mms.DATE_SENT, backup.dateSent / 1000)
                put(Telephony.Mms.MESSAGE_BOX, backup.messageBox)
                put(Telephony.Mms.READ, if (backup.read) 1 else 0)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.MESSAGE_TYPE, 128) // MESSAGE_TYPE_RETRIEVE_CONF
                put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
                backup.subject?.let { put(Telephony.Mms.SUBJECT, it) }
            }

            val mmsUri = context.contentResolver.insert(Telephony.Mms.CONTENT_URI, pduValues)
                ?: return false

            val mmsId = mmsUri.lastPathSegment?.toLongOrNull() ?: return false

            // Insert addresses
            for (address in backup.addresses) {
                insertMmsAddress(mmsId, address)
            }

            // Insert text parts
            for (text in backup.textParts) {
                insertMmsTextPart(mmsId, text)
            }

            // Insert attachment parts
            for (attachment in backup.attachments) {
                insertMmsAttachment(mmsId, attachment)
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    private fun insertMmsAddress(mmsId: Long, address: MmsAddressBackup): Boolean {
        return try {
            val values = ContentValues().apply {
                put("address", address.address)
                put("type", address.type)
                put("charset", 106) // UTF-8
            }

            context.contentResolver.insert(
                Uri.parse("content://mms/$mmsId/addr"),
                values
            ) != null
        } catch (e: Exception) {
            false
        }
    }

    private fun insertMmsTextPart(mmsId: Long, text: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put("mid", mmsId)
                put("ct", "text/plain")
                put("text", text)
                put("chset", 106) // UTF-8
            }

            context.contentResolver.insert(
                Uri.parse("content://mms/$mmsId/part"),
                values
            ) != null
        } catch (e: Exception) {
            false
        }
    }

    private fun insertMmsAttachment(mmsId: Long, attachment: MmsAttachmentBackup): Boolean {
        return try {
            val values = ContentValues().apply {
                put("mid", mmsId)
                put("ct", attachment.contentType)
                attachment.fileName?.let { put("name", it) }
            }

            val partUri = context.contentResolver.insert(
                Uri.parse("content://mms/$mmsId/part"),
                values
            ) ?: return false

            // Write attachment data
            val data = Base64.decode(attachment.data, Base64.NO_WRAP)
            context.contentResolver.openOutputStream(partUri)?.use { outputStream ->
                outputStream.write(data)
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    fun resetProgress() {
        _restoreProgress.value = SmsRestoreProgress.Idle
    }
}
