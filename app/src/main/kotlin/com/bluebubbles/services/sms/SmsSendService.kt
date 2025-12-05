package com.bluebubbles.services.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.bluebubbles.data.local.db.dao.MessageDao
import com.bluebubbles.data.local.db.entity.MessageEntity
import com.bluebubbles.data.local.db.entity.MessageSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for sending SMS/MMS messages.
 * Handles multipart SMS, delivery reports, and SIM selection.
 */
@Singleton
class SmsSendService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao
) {
    companion object {
        private const val TAG = "SmsSendService"
        const val ACTION_SMS_SENT = "com.bluebubbles.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.bluebubbles.SMS_DELIVERED"
        const val EXTRA_MESSAGE_GUID = "message_guid"
        const val EXTRA_PART_INDEX = "part_index"
    }

    /**
     * Send an SMS message to the specified address
     * @param address The recipient phone number
     * @param text The message text
     * @param chatGuid The chat GUID for tracking
     * @param subscriptionId Optional SIM slot ID (-1 for default)
     * @return The created message entity
     */
    suspend fun sendSms(
        address: String,
        text: String,
        chatGuid: String,
        subscriptionId: Int = -1
    ): Result<MessageEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val messageGuid = "sms-outgoing-${System.currentTimeMillis()}-${address.hashCode()}"

            // Create message entity first (optimistic insert)
            val message = MessageEntity(
                guid = messageGuid,
                chatGuid = chatGuid,
                text = text,
                dateCreated = System.currentTimeMillis(),
                isFromMe = true,
                messageSource = MessageSource.LOCAL_SMS.name,
                smsStatus = "pending",
                simSlot = if (subscriptionId >= 0) subscriptionId else null
            )
            messageDao.insertMessage(message)

            // Get appropriate SmsManager
            val smsManager = getSmsManager(subscriptionId)

            // Split message if needed (SMS limit is 160 chars for GSM, 70 for Unicode)
            val parts = smsManager.divideMessage(text)

            if (parts.size == 1) {
                // Single part message
                val sentIntent = createSentIntent(messageGuid, 0)
                val deliveredIntent = createDeliveredIntent(messageGuid, 0)

                smsManager.sendTextMessage(
                    address,
                    null, // service center
                    text,
                    sentIntent,
                    deliveredIntent
                )
            } else {
                // Multipart message
                val sentIntents = ArrayList<PendingIntent>()
                val deliveredIntents = ArrayList<PendingIntent>()

                parts.forEachIndexed { index, _ ->
                    sentIntents.add(createSentIntent(messageGuid, index))
                    deliveredIntents.add(createDeliveredIntent(messageGuid, index))
                }

                smsManager.sendMultipartTextMessage(
                    address,
                    null,
                    parts,
                    sentIntents,
                    deliveredIntents
                )
            }

            Log.d(TAG, "SMS queued for sending: $messageGuid (${parts.size} parts)")
            message
        }
    }

    /**
     * Update message status after send result
     */
    suspend fun updateMessageStatus(messageGuid: String, status: String, errorCode: Int? = null) {
        withContext(Dispatchers.IO) {
            val message = messageDao.getMessageByGuid(messageGuid)
            message?.let {
                val updatedMessage = it.copy(
                    smsStatus = status,
                    error = if (status == "failed") (errorCode ?: 1) else 0
                )
                messageDao.updateMessage(updatedMessage)
                Log.d(TAG, "Updated message $messageGuid status to $status")
            }
        }
    }

    /**
     * Get available SIM subscriptions
     */
    fun getAvailableSubscriptions(): List<SimInfo> {
        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

        return try {
            subscriptionManager?.activeSubscriptionInfoList?.map { info ->
                SimInfo(
                    subscriptionId = info.subscriptionId,
                    displayName = info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}",
                    carrierName = info.carrierName?.toString() ?: "",
                    slotIndex = info.simSlotIndex,
                    number = info.number ?: ""
                )
            } ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read subscription info", e)
            emptyList()
        }
    }

    /**
     * Get the default SMS subscription ID
     */
    fun getDefaultSubscriptionId(): Int {
        return SmsManager.getDefaultSmsSubscriptionId()
    }

    private fun getSmsManager(subscriptionId: Int): SmsManager {
        return if (subscriptionId >= 0) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
        } else {
            context.getSystemService(SmsManager::class.java)
        }
    }

    private fun createSentIntent(messageGuid: String, partIndex: Int): PendingIntent {
        val intent = Intent(ACTION_SMS_SENT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_MESSAGE_GUID, messageGuid)
            putExtra(EXTRA_PART_INDEX, partIndex)
        }
        return PendingIntent.getBroadcast(
            context,
            "${messageGuid}_sent_$partIndex".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createDeliveredIntent(messageGuid: String, partIndex: Int): PendingIntent {
        val intent = Intent(ACTION_SMS_DELIVERED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_MESSAGE_GUID, messageGuid)
            putExtra(EXTRA_PART_INDEX, partIndex)
        }
        return PendingIntent.getBroadcast(
            context,
            "${messageGuid}_delivered_$partIndex".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * Data class representing a SIM card
 */
data class SimInfo(
    val subscriptionId: Int,
    val displayName: String,
    val carrierName: String,
    val slotIndex: Int,
    val number: String
)
