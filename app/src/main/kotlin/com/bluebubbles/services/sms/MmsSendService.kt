package com.bluebubbles.services.sms

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.bluebubbles.data.local.db.dao.MessageDao
import com.bluebubbles.data.local.db.entity.MessageEntity
import com.bluebubbles.data.local.db.entity.MessageSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for sending MMS messages.
 * Handles group messages, attachments, and carrier MMS settings.
 */
@Singleton
class MmsSendService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val smsPermissionHelper: SmsPermissionHelper
) {
    companion object {
        private const val TAG = "MmsSendService"

        // MMS PDU constants
        private const val MESSAGE_TYPE = 0x8C
        private const val MESSAGE_TYPE_SEND_REQ = 0x80
        private const val TRANSACTION_ID = 0x98
        private const val MMS_VERSION = 0x8D
        private const val MMS_VERSION_1_3 = 0x93
        private const val FROM = 0x89
        private const val TO = 0x97
        private const val CONTENT_TYPE = 0x84
        private const val SUBJECT = 0x96
        private const val DATE = 0x85
    }

    /**
     * Send an MMS message
     * @param recipients List of recipient phone numbers
     * @param text Optional text content
     * @param attachments List of attachment URIs
     * @param chatGuid The chat GUID for tracking
     * @param subject Optional MMS subject
     * @param subscriptionId Optional SIM slot ID
     */
    suspend fun sendMms(
        recipients: List<String>,
        text: String?,
        attachments: List<Uri>,
        chatGuid: String,
        subject: String? = null,
        subscriptionId: Int = -1
    ): Result<MessageEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val timestamp = System.currentTimeMillis()
            val providerMessage = insertMmsToProvider(recipients, text, attachments, subject)
            val messageGuid = providerMessage?.let { "mms-${it.id}" }
                ?: "mms-outgoing-$timestamp"

            // Create message entity first (optimistic insert)
            val message = MessageEntity(
                guid = messageGuid,
                chatGuid = chatGuid,
                text = text,
                subject = subject,
                dateCreated = timestamp,
                isFromMe = true,
                hasAttachments = attachments.isNotEmpty(),
                messageSource = MessageSource.LOCAL_MMS.name,
                smsStatus = "pending",
                simSlot = if (subscriptionId >= 0) subscriptionId else null,
                smsId = providerMessage?.id,
                smsThreadId = providerMessage?.threadId
            )
            messageDao.insertMessage(message)

            // Build MMS PDU
            val pduBytes = buildMmsPdu(recipients, text, attachments, subject)

            // Write PDU to temp file
            val pduFile = File(context.cacheDir, "mms_${System.currentTimeMillis()}.pdu")
            FileOutputStream(pduFile).use { it.write(pduBytes) }
            val pduUri = Uri.fromFile(pduFile)

            // Send using SmsManager
            val smsManager = if (subscriptionId >= 0) {
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
            } else {
                context.getSystemService(SmsManager::class.java)
            }

            // Create config bundle for MMS settings
            val configOverrides = android.os.Bundle()

            // Send MMS
            smsManager.sendMultimediaMessage(
                context,
                pduUri,
                null, // locationUrl - let system determine
                configOverrides,
                null // sentIntent - we'll use content observer to track status
            )

            Log.d(TAG, "MMS queued for sending: $messageGuid")

            // Clean up temp file after a delay (system needs it briefly)
            pduFile.deleteOnExit()

            message
        }
    }

    /**
     * Build a simple MMS PDU for sending
     * Note: This is a simplified implementation. Production code should use
     * a proper MMS library like android-smsmms.
     */
    private suspend fun buildMmsPdu(
        recipients: List<String>,
        text: String?,
        attachments: List<Uri>,
        subject: String?
    ): ByteArray = withContext(Dispatchers.IO) {
        val output = ByteArrayOutputStream()

        // Message type
        output.write(MESSAGE_TYPE)
        output.write(MESSAGE_TYPE_SEND_REQ)

        // Transaction ID
        val transactionId = UUID.randomUUID().toString().take(8)
        output.write(TRANSACTION_ID)
        output.writeString(transactionId)

        // MMS Version
        output.write(MMS_VERSION)
        output.write(MMS_VERSION_1_3)

        // Date
        output.write(DATE)
        output.writeLong(System.currentTimeMillis() / 1000)

        // From (insert-address-token means device will fill this in)
        output.write(FROM)
        output.write(0x01) // length
        output.write(0x81) // insert-address-token

        // To
        recipients.forEach { recipient ->
            output.write(TO)
            output.writeString("$recipient/TYPE=PLMN")
        }

        // Subject
        if (!subject.isNullOrBlank()) {
            output.write(SUBJECT)
            output.writeString(subject)
        }

        // Content type (multipart/mixed or multipart/related)
        output.write(CONTENT_TYPE)
        val contentType = "application/vnd.wap.multipart.mixed"
        output.writeContentType(contentType)

        // Parts count
        val partsCount = (if (text != null) 1 else 0) + attachments.size
        output.writeUintvar(partsCount)

        // Text part
        if (!text.isNullOrBlank()) {
            writeTextPart(output, text)
        }

        // Attachment parts
        attachments.forEach { uri ->
            writeAttachmentPart(output, uri)
        }

        output.toByteArray()
    }

    private fun writeTextPart(output: ByteArrayOutputStream, text: String) {
        val textBytes = text.toByteArray(Charsets.UTF_8)

        // Headers length
        val headers = ByteArrayOutputStream()
        // Content-Type: text/plain; charset=utf-8
        headers.write(0x83) // Content-Type field
        headers.writeContentType("text/plain; charset=utf-8")
        // Content-ID
        headers.write(0xC0) // Content-ID field
        headers.writeString("<text_0>")

        output.writeUintvar(headers.size())
        output.writeUintvar(textBytes.size)
        output.write(headers.toByteArray())
        output.write(textBytes)
    }

    private suspend fun writeAttachmentPart(output: ByteArrayOutputStream, uri: Uri) {
        val contentResolver: ContentResolver = context.contentResolver

        // Get MIME type
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        // Get file name
        val fileName = uri.lastPathSegment ?: "attachment"

        // Read content
        val content = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot read attachment: $uri")

        // Headers
        val headers = ByteArrayOutputStream()
        headers.write(0x83) // Content-Type
        headers.writeContentType(mimeType)
        headers.write(0xC0) // Content-ID
        headers.writeString("<$fileName>")
        headers.write(0x8E) // Content-Location
        headers.writeString(fileName)

        output.writeUintvar(headers.size())
        output.writeUintvar(content.size)
        output.write(headers.toByteArray())
        output.write(content)
    }

    // Extension functions for PDU encoding
    private fun ByteArrayOutputStream.writeString(str: String) {
        val bytes = str.toByteArray(Charsets.UTF_8)
        write(bytes.size + 1) // length including null terminator
        write(bytes)
        write(0) // null terminator
    }

    private fun ByteArrayOutputStream.writeContentType(contentType: String) {
        val bytes = contentType.toByteArray(Charsets.UTF_8)
        write(bytes.size + 1)
        write(bytes)
        write(0)
    }

    private fun ByteArrayOutputStream.writeLong(value: Long) {
        // Write as variable length integer
        var v = value
        val bytes = mutableListOf<Byte>()
        do {
            bytes.add(0, (v and 0x7F).toByte())
            v = v shr 7
        } while (v > 0)

        // Set continuation bits
        for (i in 0 until bytes.size - 1) {
            bytes[i] = (bytes[i].toInt() or 0x80).toByte()
        }

        write(bytes.size)
        bytes.forEach { write(it.toInt()) }
    }

    private fun ByteArrayOutputStream.writeUintvar(value: Int) {
        var v = value
        val bytes = mutableListOf<Byte>()
        do {
            bytes.add(0, (v and 0x7F).toByte())
            v = v shr 7
        } while (v > 0)

        for (i in 0 until bytes.size - 1) {
            bytes[i] = (bytes[i].toInt() or 0x80).toByte()
        }

        bytes.forEach { write(it.toInt()) }
    }

    /**
     * Insert MMS into system database (required for some carriers)
     */
    suspend fun insertMmsToProvider(
        recipients: List<String>,
        text: String?,
        attachments: List<Uri>,
        subject: String?
    ): MmsProviderRef? = withContext(Dispatchers.IO) {
        if (!smsPermissionHelper.isDefaultSmsApp()) return@withContext null

        try {
            val nowSeconds = System.currentTimeMillis() / 1000
            val values = ContentValues().apply {
                put(Telephony.Mms.DATE, nowSeconds)
                put(Telephony.Mms.DATE_SENT, nowSeconds)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.MESSAGE_TYPE, 128) // m-send-req
                put(Telephony.Mms.MMS_VERSION, 19) // 1.3
                put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
                if (!subject.isNullOrBlank()) {
                    put(Telephony.Mms.SUBJECT, subject)
                }
            }

            val mmsUri = context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values) ?: return@withContext null
            val mmsId = mmsUri.lastPathSegment?.toLongOrNull() ?: return@withContext null

            insertMmsAddresses(mmsId, recipients)
            insertMmsTextPart(mmsId, text)
            attachments.forEach { insertMmsAttachmentPart(mmsId, it) }

            val threadId = queryMmsThreadId(mmsId)
            Log.d(TAG, "Inserted MMS to provider: $mmsUri")
            MmsProviderRef(mmsUri, mmsId, threadId)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission to write MMS provider", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert MMS to provider", e)
            null
        }
    }

    private fun insertMmsAddresses(mmsId: Long, recipients: List<String>) {
        recipients.forEach { recipient ->
            val addrValues = ContentValues().apply {
                put(Telephony.Mms.Addr.ADDRESS, recipient)
                put(Telephony.Mms.Addr.TYPE, 151) // TO
                put(Telephony.Mms.Addr.MSG_ID, mmsId)
            }
            context.contentResolver.insert(
                Uri.parse("content://mms/$mmsId/addr"),
                addrValues
            )
        }
    }

    private fun insertMmsTextPart(mmsId: Long, text: String?) {
        if (text.isNullOrBlank()) return

        val partValues = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, mmsId)
            put(Telephony.Mms.Part.CONTENT_TYPE, "text/plain")
            put(Telephony.Mms.Part.CHARSET, 106) // UTF-8
            put(Telephony.Mms.Part.TEXT, text)
        }
        context.contentResolver.insert(
            Uri.parse("content://mms/$mmsId/part"),
            partValues
        )
    }

    private fun insertMmsAttachmentPart(mmsId: Long, attachmentUri: Uri) {
        val contentResolver: ContentResolver = context.contentResolver
        val mimeType = contentResolver.getType(attachmentUri) ?: "application/octet-stream"
        val fileName = attachmentUri.lastPathSegment ?: "attachment"

        val partValues = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, mmsId)
            put(Telephony.Mms.Part.CONTENT_TYPE, mimeType)
            put(Telephony.Mms.Part.FILENAME, fileName)
            put(Telephony.Mms.Part.NAME, fileName)
        }

        val partUri = contentResolver.insert(Uri.parse("content://mms/part"), partValues) ?: return

        try {
            contentResolver.openOutputStream(partUri)?.use { output ->
                contentResolver.openInputStream(attachmentUri)?.use { input ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist MMS attachment", e)
        }
    }

    private fun queryMmsThreadId(mmsId: Long): Long? {
        return context.contentResolver.query(
            Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, mmsId.toString()),
            arrayOf(Telephony.Mms.THREAD_ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)) else null
        }
    }

    data class MmsProviderRef(
        val uri: Uri,
        val id: Long,
        val threadId: Long?
    )
}
