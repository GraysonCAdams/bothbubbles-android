package com.bothbubbles.services.sms

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
    private val smsPermissionHelper: SmsPermissionHelper,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "MmsSendService"

        // MMS address types (from PduHeaders)
        private const val PDU_ADDR_TYPE_TO = 151

        // Character set for UTF-8 (IANA MIBenum)
        private const val CHARSET_UTF8 = 106
    }

    // Use the improved PDU builder
    private val pduBuilder = MmsPduBuilder(context)

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
    ): Result<MessageEntity> = withContext(ioDispatcher) {
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

            // Convert attachments to AttachmentData for builder
            val attachmentData = attachments.mapNotNull { uri ->
                readAttachmentData(uri)
            }

            // Build MMS PDU using improved builder
            val pduBytes = pduBuilder.build(
                recipients = recipients,
                text = text,
                attachments = attachmentData,
                subject = subject,
                subscriptionId = subscriptionId
            )

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

            // Clean up temp file after a delay (system needs it briefly for sending)
            // Using a coroutine with delay is more reliable than deleteOnExit()
            applicationScope.launch(ioDispatcher) {
                delay(30_000) // Wait 30 seconds for MMS to be sent
                try {
                    if (pduFile.exists()) {
                        pduFile.delete()
                        Log.d(TAG, "Cleaned up temp PDU file: ${pduFile.name}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clean up temp PDU file", e)
                }
            }

            message
        }
    }

    /**
     * Read attachment data from URI for MMS sending
     */
    private fun readAttachmentData(uri: Uri): MmsPduBuilder.AttachmentData? {
        return try {
            val contentResolver: ContentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val fileName = getFileName(uri) ?: "attachment_${System.currentTimeMillis()}"
            val data = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null

            MmsPduBuilder.AttachmentData(
                mimeType = mimeType,
                fileName = fileName,
                data = data
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read attachment: $uri", e)
            null
        }
    }

    /**
     * Get file name from URI, handling content:// and file:// schemes
     */
    private fun getFileName(uri: Uri): String? {
        // Try to get display name from content resolver
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        }

        // Fall back to last path segment
        return uri.lastPathSegment
    }

    /**
     * Insert MMS into system database (required for some carriers)
     */
    suspend fun insertMmsToProvider(
        recipients: List<String>,
        text: String?,
        attachments: List<Uri>,
        subject: String?
    ): MmsProviderRef? = withContext(ioDispatcher) {
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
                put(Telephony.Mms.Addr.TYPE, PDU_ADDR_TYPE_TO)
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
            put(Telephony.Mms.Part.CHARSET, CHARSET_UTF8)
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
