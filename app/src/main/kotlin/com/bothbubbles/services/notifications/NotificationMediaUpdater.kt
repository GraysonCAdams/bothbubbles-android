package com.bothbubbles.services.notifications

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.data.local.db.entity.rawDisplayName
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.media.AttachmentDownloadQueue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Updates message notifications with inline media previews after attachments download.
 *
 * When a user receives a message with an image/video attachment:
 * 1. Initial notification shows text description ("Photo", "Video", etc.)
 * 2. Attachment downloads in background
 * 3. This service updates the notification with the actual media preview
 *
 * This provides a better UX than waiting for download before showing notification.
 */
@Singleton
class NotificationMediaUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attachmentDownloadQueue: AttachmentDownloadQueue,
    private val attachmentDao: AttachmentDao,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val chatRepository: ChatRepository,
    private val notificationService: NotificationService,
    private val activeConversationManager: ActiveConversationManager,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "NotificationMediaUpdater"

        // MIME types that support inline notification preview
        private val PREVIEWABLE_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/heic",
            "image/heif"
            // Note: Video thumbnails would need to be generated separately
        )
    }

    /**
     * Start listening for download completions.
     * Should be called once during app initialization.
     */
    fun initialize() {
        Timber.d("$TAG: Initializing notification media updater")

        attachmentDownloadQueue.downloadCompletions
            .onEach { attachmentGuid ->
                handleDownloadComplete(attachmentGuid)
            }
            .launchIn(applicationScope)
    }

    private suspend fun handleDownloadComplete(attachmentGuid: String) {
        applicationScope.launch(ioDispatcher) {
            try {
                // Look up attachment
                val attachment = attachmentDao.getAttachmentByGuid(attachmentGuid)
                if (attachment == null) {
                    Timber.w("$TAG: Attachment not found for GUID: $attachmentGuid")
                    return@launch
                }

                val localPath = attachment.localPath
                if (localPath == null) {
                    Timber.w("$TAG: No local path for attachment: $attachmentGuid")
                    return@launch
                }

                // Check if MIME type supports inline preview
                val mimeType = attachment.mimeType?.lowercase()
                if (mimeType == null || mimeType !in PREVIEWABLE_MIME_TYPES) {
                    Timber.d("$TAG: MIME type not previewable: $mimeType")
                    return@launch
                }

                // Look up message to get chat info
                val messageGuid = attachment.messageGuid
                val message = messageDao.getMessageByGuid(messageGuid)
                if (message == null) {
                    Timber.w("$TAG: Message not found for attachment: $attachmentGuid")
                    return@launch
                }

                // Skip if message is from me (we sent it, no notification to update)
                if (message.isFromMe) {
                    return@launch
                }

                val chatGuid = message.chatGuid
                if (chatGuid == null) {
                    Timber.w("$TAG: No chat GUID for message: ${message.guid}")
                    return@launch
                }

                // Check if user is currently viewing this conversation (no notification shown)
                if (activeConversationManager.isConversationActive(chatGuid)) {
                    Timber.d("$TAG: Chat $chatGuid is active, skipping notification update")
                    return@launch
                }

                // Look up chat
                val chat = chatDao.getChatByGuid(chatGuid)
                if (chat == null) {
                    Timber.w("$TAG: Chat not found: $chatGuid")
                    return@launch
                }

                // Generate content:// URI for the file
                val file = File(localPath)
                if (!file.exists()) {
                    Timber.w("$TAG: File does not exist: $localPath")
                    return@launch
                }

                val attachmentUri: Uri = try {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to get FileProvider URI")
                    return@launch
                }

                // Get participants and chat info
                val participants = chatRepository.getParticipantsForChat(chatGuid)
                val participantNames = participants.map { it.rawDisplayName }
                val participantAvatarPaths = participants.map { it.cachedAvatarPath }

                // Resolve chat title
                val chatTitle = chatRepository.resolveChatTitle(chat, participants)

                // Get sender info
                val senderAddress = message.senderAddress ?: ""
                val senderParticipant = participants.find { it.address == senderAddress }
                val senderName = senderParticipant?.displayName
                val senderAvatarUri = senderParticipant?.cachedAvatarPath

                // Get message text (or generate preview text)
                val messageText = message.text?.takeIf { it.isNotBlank() }
                    ?: getAttachmentPreviewText(mimeType)

                Timber.d("$TAG: Updating notification for chat $chatGuid with attachment: $attachmentUri")

                // Update the notification with inline media
                notificationService.showMessageNotification(
                    chatGuid = chatGuid,
                    chatTitle = chatTitle,
                    messageText = messageText,
                    messageGuid = message.guid,
                    senderName = senderName,
                    senderAddress = senderAddress,
                    isGroup = chat.isGroup,
                    avatarUri = senderAvatarUri,
                    linkPreviewTitle = null,
                    linkPreviewDomain = null,
                    participantNames = participantNames,
                    participantAvatarPaths = participantAvatarPaths,
                    subject = message.subject,
                    attachmentUri = attachmentUri,
                    attachmentMimeType = mimeType
                )
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error updating notification for attachment: $attachmentGuid")
            }
        }
    }

    private fun getAttachmentPreviewText(mimeType: String): String {
        return when {
            mimeType == "image/gif" -> "GIF"
            mimeType.startsWith("image/") -> "Photo"
            mimeType.startsWith("video/") -> "Video"
            else -> "Attachment"
        }
    }
}
