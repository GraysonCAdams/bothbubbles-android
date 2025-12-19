package com.bothbubbles.services.messaging.sender

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.services.sms.MmsSendService
import com.bothbubbles.services.sms.SmsSendService
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.util.error.SmsError
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strategy for sending messages via local Android SMS/MMS APIs.
 *
 * Handles:
 * - [MessageDeliveryMode.LOCAL_SMS]: Single-recipient text messages
 * - [MessageDeliveryMode.LOCAL_MMS]: Messages with attachments or multiple recipients
 */
@Singleton
class SmsSenderStrategy @Inject constructor(
    private val smsSendService: SmsSendService,
    private val mmsSendService: MmsSendService,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val chatDao: ChatDao
) : MessageSenderStrategy {

    override fun canHandle(deliveryMode: MessageDeliveryMode): Boolean {
        return deliveryMode == MessageDeliveryMode.LOCAL_SMS ||
               deliveryMode == MessageDeliveryMode.LOCAL_MMS
    }

    override suspend fun send(options: SendOptions): SendResult {
        val chat = chatDao.getChatByGuid(options.chatGuid)
        val isGroup = chat?.isGroup == true
        val hasAttachments = options.attachments.isNotEmpty()
        val hasSubject = !options.subject.isNullOrBlank()

        val useMms = isGroup || hasAttachments || hasSubject

        return if (useMms) {
            sendMms(options, isGroup)
        } else {
            sendSms(options)
        }
    }

    private suspend fun sendSms(options: SendOptions): SendResult {
        val status = smsPermissionHelper.getSmsCapabilityStatus()
        if (!status.canSendSms) {
            return SendResult.Failure(
                SmsError.PermissionDenied(Exception("SMS permission denied"))
            )
        }
        if (!status.isDefaultSmsApp) {
            return SendResult.Failure(SmsError.NoDefaultApp())
        }

        val address = extractAddressFromChatGuid(options.chatGuid)
            ?: return SendResult.Failure(
                SmsError.PermissionDenied(Exception("Invalid chat GUID for SMS: ${options.chatGuid}"))
            )

        Timber.d("Sending SMS to $address (tempGuid=${options.tempGuid})")

        val result = smsSendService.sendSms(
            address = address,
            text = options.text,
            chatGuid = options.chatGuid,
            subscriptionId = options.subscriptionId,
            tempGuid = options.tempGuid
        )

        return SendResult.fromResult(result)
    }

    private suspend fun sendMms(options: SendOptions, isGroup: Boolean): SendResult {
        val status = smsPermissionHelper.getSmsCapabilityStatus()
        if (!status.deviceSupportsMms) {
            return SendResult.Failure(
                SmsError.PermissionDenied(Exception("Device does not support MMS"))
            )
        }
        if (!status.isDefaultSmsApp) {
            return SendResult.Failure(SmsError.NoDefaultApp())
        }

        val addresses = extractAddressesFromChatGuid(options.chatGuid)
        if (addresses.isEmpty()) {
            return SendResult.Failure(
                SmsError.PermissionDenied(Exception("Invalid chat GUID for MMS: ${options.chatGuid}"))
            )
        }

        var messageText = options.text
        val captions = options.attachments.mapNotNull { it.caption }.filter { it.isNotBlank() }
        if (captions.isNotEmpty()) {
            if (messageText.isNotBlank()) messageText += "\n"
            messageText += captions.joinToString("\n")
        }

        Timber.d("Sending MMS to ${addresses.size} recipient(s) with ${options.attachments.size} attachment(s) (tempGuid=${options.tempGuid})")

        val result = mmsSendService.sendMms(
            recipients = addresses,
            text = messageText.ifBlank { null },
            attachments = options.attachments.map { it.uri },
            chatGuid = options.chatGuid,
            subject = options.subject,
            subscriptionId = options.subscriptionId,
            tempGuid = options.tempGuid
        )

        return SendResult.fromResult(result)
    }

    private fun extractAddressFromChatGuid(chatGuid: String): String? {
        val parts = chatGuid.split(";-;")
        return if (parts.size == 2) parts[1].takeIf { it.isNotBlank() } else null
    }

    private fun extractAddressesFromChatGuid(chatGuid: String): List<String> {
        val parts = chatGuid.split(";-;")
        if (parts.size != 2) return emptyList()
        return parts[1].split(",").filter { it.isNotBlank() }
    }
}
