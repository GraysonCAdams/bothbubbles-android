package com.bluebubbles.services.sms

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.MessageDao
import com.bluebubbles.data.local.db.entity.ChatEntity
import com.bluebubbles.data.local.db.entity.MessageSource
import com.bluebubbles.services.notifications.NotificationService
import com.bluebubbles.ui.components.PhoneAndCodeParsingUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Content observer for monitoring SMS/MMS database changes.
 * Used to detect:
 * - MMS messages (which can't be received via broadcast)
 * - Messages sent from other apps
 * - Message status changes
 */
@Singleton
class SmsContentObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smsContentProvider: SmsContentProvider,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val notificationService: NotificationService
) {
    companion object {
        private const val TAG = "SmsContentObserver"
        private const val DEBOUNCE_MS = 500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var smsObserver: ContentObserver? = null
    private var mmsObserver: ContentObserver? = null

    private val _isObserving = MutableStateFlow(false)
    val isObserving: StateFlow<Boolean> = _isObserving.asStateFlow()

    private var lastSmsId: Long = 0
    private var lastMmsId: Long = 0
    private var debounceJob: Job? = null

    /**
     * Start observing SMS/MMS content changes
     */
    fun startObserving() {
        if (_isObserving.value) return

        Log.d(TAG, "Starting SMS/MMS content observers")

        // Initialize last IDs
        scope.launch {
            lastSmsId = getLatestSmsId()
            lastMmsId = getLatestMmsId()
        }

        val handler = Handler(Looper.getMainLooper())

        // SMS observer
        smsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "SMS content changed: $uri")
                debounceAndProcess { processSmsChanges() }
            }
        }

        // MMS observer
        mmsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "MMS content changed: $uri")
                debounceAndProcess { processMmsChanges() }
            }
        }

        context.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            smsObserver!!
        )

        context.contentResolver.registerContentObserver(
            Telephony.Mms.CONTENT_URI,
            true,
            mmsObserver!!
        )

        _isObserving.value = true
    }

    /**
     * Stop observing SMS/MMS content changes
     */
    fun stopObserving() {
        Log.d(TAG, "Stopping SMS/MMS content observers")

        smsObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        mmsObserver?.let { context.contentResolver.unregisterContentObserver(it) }

        smsObserver = null
        mmsObserver = null
        debounceJob?.cancel()

        _isObserving.value = false
    }

    private fun debounceAndProcess(action: suspend () -> Unit) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            action()
        }
    }

    private suspend fun processSmsChanges() {
        try {
            val latestId = getLatestSmsId()
            if (latestId <= lastSmsId) return

            Log.d(TAG, "Processing new SMS messages (last: $lastSmsId, current: $latestId)")

            // Query for new messages
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                null,
                "${Telephony.Sms._ID} > ?",
                arrayOf(lastSmsId.toString()),
                "${Telephony.Sms._ID} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID))
                    val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
                    val address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: continue
                    val body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY))
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE))
                    val type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))

                    // Check if we already have this message
                    val existingGuid = "sms-$id"
                    if (messageDao.getMessageByGuid(existingGuid) != null) continue

                    // Determine if this is an incoming or outgoing message
                    val isFromMe = type == Telephony.Sms.MESSAGE_TYPE_SENT ||
                            type == Telephony.Sms.MESSAGE_TYPE_OUTBOX ||
                            type == Telephony.Sms.MESSAGE_TYPE_QUEUED

                    // Normalize address to prevent duplicate conversations
                    val normalizedAddress = PhoneAndCodeParsingUtils.normalizePhoneNumber(address)
                    // Get or create chat
                    val chatGuid = "sms;-;$normalizedAddress"
                    ensureChatExists(chatGuid, normalizedAddress, date, body)

                    // Create message entity
                    val message = com.bluebubbles.data.local.db.entity.MessageEntity(
                        guid = existingGuid,
                        chatGuid = chatGuid,
                        text = body,
                        dateCreated = date,
                        isFromMe = isFromMe,
                        messageSource = MessageSource.LOCAL_SMS.name,
                        smsId = id,
                        smsThreadId = threadId
                    )
                    messageDao.insertMessage(message)

                    // Show notification for incoming messages
                    if (!isFromMe) {
                        val chat = chatDao.getChatByGuid(chatGuid)
                        notificationService.showMessageNotification(
                            chatGuid = chatGuid,
                            chatTitle = chat?.displayName ?: address,
                            messageText = body ?: "",
                            messageGuid = existingGuid,
                            senderName = null
                        )
                    }

                    Log.d(TAG, "Imported SMS $id from content observer")
                }
            }

            lastSmsId = latestId
        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS changes", e)
        }
    }

    private suspend fun processMmsChanges() {
        try {
            val latestId = getLatestMmsId()
            if (latestId <= lastMmsId) return

            Log.d(TAG, "Processing new MMS messages (last: $lastMmsId, current: $latestId)")

            // Query for new MMS messages
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                null,
                "${Telephony.Mms._ID} > ?",
                arrayOf(lastMmsId.toString()),
                "${Telephony.Mms._ID} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms._ID))
                    val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID))
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)) * 1000 // MMS uses seconds
                    val messageBox = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX))

                    // Check if we already have this message
                    val existingGuid = "mms-$id"
                    if (messageDao.getMessageByGuid(existingGuid) != null) continue

                    val isFromMe = messageBox == Telephony.Mms.MESSAGE_BOX_SENT ||
                            messageBox == Telephony.Mms.MESSAGE_BOX_OUTBOX

                    // Get MMS details using content provider
                    val mmsMessages = smsContentProvider.getMmsMessages(threadId, limit = 1)
                    val mmsMessage = mmsMessages.find { it.id == id } ?: continue

                    // Get primary address (for chat creation)
                    val rawPrimaryAddress = if (isFromMe) {
                        mmsMessage.addresses.find { it.type == 151 }?.address // TO
                    } else {
                        mmsMessage.addresses.find { it.type == 137 }?.address // FROM
                    } ?: continue
                    // Normalize to prevent duplicate conversations
                    val primaryAddress = PhoneAndCodeParsingUtils.normalizePhoneNumber(rawPrimaryAddress)

                    // Determine chat GUID based on participants
                    val isGroup = mmsMessage.addresses.size > 2
                    val chatGuid = if (isGroup) {
                        "mms;-;${mmsMessage.addresses.map { PhoneAndCodeParsingUtils.normalizePhoneNumber(it.address) }.sorted().joinToString(",")}"
                    } else {
                        "sms;-;$primaryAddress"
                    }

                    // Get text content
                    val textContent = mmsMessage.textParts.joinToString("\n").takeIf { it.isNotBlank() }

                    // Check for duplicate SMS message with matching content and timestamp
                    // This prevents the same message from appearing twice when recorded as both SMS and MMS
                    if (textContent != null) {
                        val matchingMessage = messageDao.findMatchingMessage(
                            chatGuid = chatGuid,
                            text = textContent,
                            isFromMe = isFromMe,
                            dateCreated = date,
                            toleranceMs = 10000 // 10 second window for MMS which can have delayed timestamps
                        )
                        if (matchingMessage != null) {
                            Log.d(TAG, "Skipping duplicate MMS $id - matches existing message ${matchingMessage.guid}")
                            continue
                        }
                    }

                    // Ensure chat exists
                    ensureChatExists(chatGuid, primaryAddress, date, textContent, isGroup)

                    // Create message entity
                    val message = mmsMessage.toMessageEntity(chatGuid)
                    messageDao.insertMessage(message)

                    // Show notification for incoming messages
                    if (!isFromMe) {
                        val chat = chatDao.getChatByGuid(chatGuid)
                        notificationService.showMessageNotification(
                            chatGuid = chatGuid,
                            chatTitle = chat?.displayName ?: primaryAddress,
                            messageText = textContent ?: "[MMS]",
                            messageGuid = existingGuid,
                            senderName = if (isGroup) primaryAddress else null
                        )
                    }

                    Log.d(TAG, "Imported MMS $id from content observer")
                }
            }

            lastMmsId = latestId
        } catch (e: Exception) {
            Log.e(TAG, "Error processing MMS changes", e)
        }
    }

    private suspend fun ensureChatExists(
        chatGuid: String,
        address: String,
        date: Long,
        lastMessage: String?,
        isGroup: Boolean = false
    ) {
        val existingChat = chatDao.getChatByGuid(chatGuid)
        if (existingChat == null) {
            val chat = ChatEntity(
                guid = chatGuid,
                chatIdentifier = address,
                displayName = null,
                isGroup = isGroup,
                lastMessageDate = date,
                lastMessageText = lastMessage,
                unreadCount = 1
            )
            chatDao.insertChat(chat)
        } else {
            chatDao.updateLastMessage(chatGuid, date, lastMessage)
            if (!existingChat.isGroup && isGroup) {
                // Update to group if needed
                chatDao.insertChat(existingChat.copy(isGroup = true))
            }
        }
    }

    private fun getLatestSmsId(): Long {
        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            null,
            null,
            "${Telephony.Sms._ID} DESC LIMIT 1"
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }

    private fun getLatestMmsId(): Long {
        return context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID),
            null,
            null,
            "${Telephony.Mms._ID} DESC LIMIT 1"
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }

    private fun MmsMessage.toMessageEntity(chatGuid: String): com.bluebubbles.data.local.db.entity.MessageEntity {
        return com.bluebubbles.data.local.db.entity.MessageEntity(
            guid = "mms-$id",
            chatGuid = chatGuid,
            text = textParts.joinToString("\n").takeIf { it.isNotBlank() },
            subject = subject,
            dateCreated = date,
            isFromMe = isFromMe,
            hasAttachments = imageParts.isNotEmpty(),
            messageSource = MessageSource.LOCAL_MMS.name,
            smsId = id,
            smsThreadId = threadId
        )
    }
}
