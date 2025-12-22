package com.bothbubbles.services.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.media.MediaMetadataRetriever
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
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
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

        // Image MIME types that support direct inline notification preview
        private val IMAGE_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/heic",
            "image/heif"
        )

        // Video MIME types that need thumbnail generation
        private val VIDEO_MIME_TYPES = setOf(
            "video/mp4",
            "video/quicktime",
            "video/x-m4v",
            "video/3gpp",
            "video/webm",
            "video/mpeg"
        )

        // Thumbnail cache directory name
        private const val THUMBNAIL_CACHE_DIR = "notification_thumbnails"

        // Thumbnail size for notifications
        private const val THUMBNAIL_SIZE = 512
    }

    /**
     * Start listening for download completions.
     * Should be called once during app initialization.
     */
    fun initialize() {
        Timber.d("$TAG: Initializing notification media updater")

        applicationScope.launch(ioDispatcher) {
            Timber.d("$TAG: Starting to collect downloadCompletions flow")
            attachmentDownloadQueue.downloadCompletions.collect { attachmentGuid ->
                Timber.d("$TAG: Received download completion: $attachmentGuid")
                handleDownloadComplete(attachmentGuid)
            }
        }
    }

    private suspend fun handleDownloadComplete(attachmentGuid: String) {
        try {
            // Look up attachment
            val attachment = attachmentDao.getAttachmentByGuid(attachmentGuid)
            if (attachment == null) {
                Timber.w("$TAG: Attachment not found for GUID: $attachmentGuid")
                return
            }

            val localPath = attachment.localPath
            if (localPath == null) {
                Timber.w("$TAG: No local path for attachment: $attachmentGuid")
                return
            }

            // Check if MIME type supports inline preview
            val mimeType = attachment.mimeType?.lowercase()
            val isImage = mimeType != null && mimeType in IMAGE_MIME_TYPES
            val isVideo = mimeType != null && (mimeType in VIDEO_MIME_TYPES || mimeType.startsWith("video/"))
            if (!isImage && !isVideo) {
                Timber.d("$TAG: MIME type not previewable: $mimeType")
                return
            }

            // Look up message to get chat info
            val messageGuid = attachment.messageGuid
            val message = messageDao.getMessageByGuid(messageGuid)
            if (message == null) {
                Timber.w("$TAG: Message not found for attachment: $attachmentGuid")
                return
            }

            // Note: We don't check isFromMe here because:
            // 1. Self-messages (messaging yourself) trigger notifications
            // 2. The download was only enqueued because a notification was shown
            // 3. We should update any notification that exists for this chat

            val chatGuid = message.chatGuid
            if (chatGuid == null) {
                Timber.w("$TAG: No chat GUID for message: ${message.guid}")
                return
            }

            // Check if user is currently viewing this conversation (no notification shown)
            if (activeConversationManager.isConversationActive(chatGuid)) {
                Timber.d("$TAG: Chat $chatGuid is active, skipping notification update")
                return
            }

            // Look up chat
            val chat = chatDao.getChatByGuid(chatGuid)
            if (chat == null) {
                Timber.w("$TAG: Chat not found: $chatGuid")
                return
            }

            // Generate content:// URI for the file
            val file = File(localPath)
            if (!file.exists()) {
                Timber.w("$TAG: File does not exist: $localPath")
                return
            }

            // For videos, generate a thumbnail with play icon overlay
            // For images, use the file directly
            val (attachmentUri: Uri, notificationMimeType: String) = if (isVideo) {
                val thumbnailFile = generateVideoThumbnail(file, attachmentGuid)
                if (thumbnailFile == null) {
                    Timber.w("$TAG: Failed to generate video thumbnail for: $attachmentGuid")
                    return
                }
                try {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        thumbnailFile
                    )
                    uri to "image/png"
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to get FileProvider URI for thumbnail")
                    return
                }
            } else {
                try {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    uri to (mimeType ?: "image/jpeg")
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to get FileProvider URI")
                    return
                }
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
                MessageNotificationParams(
                    chatGuid = chatGuid,
                    chatTitle = chatTitle,
                    messageText = messageText,
                    messageGuid = message.guid,
                    senderName = senderName,
                    senderAddress = senderAddress,
                    isGroup = chat.isGroup,
                    avatarUri = senderAvatarUri,
                    participantNames = participantNames,
                    participantAvatarPaths = participantAvatarPaths,
                    groupAvatarPath = chat.effectiveGroupPhotoPath,
                    subject = message.subject,
                    attachmentUri = attachmentUri,
                    attachmentMimeType = notificationMimeType
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error updating notification for attachment: $attachmentGuid")
        }
    }

    /**
     * Generate a video thumbnail with a play icon overlay.
     * Returns the thumbnail file, or null if generation failed.
     */
    private fun generateVideoThumbnail(videoFile: File, attachmentGuid: String): File? {
        return try {
            // Create thumbnail cache directory
            val cacheDir = File(context.cacheDir, THUMBNAIL_CACHE_DIR)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            // Check if thumbnail already exists
            val thumbnailFile = File(cacheDir, "${attachmentGuid}_thumb.png")
            if (thumbnailFile.exists()) {
                return thumbnailFile
            }

            // Extract frame from video
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoFile.absolutePath)
                val frameBitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return null

                // Scale bitmap to thumbnail size
                val scaledBitmap = scaleBitmapToFit(frameBitmap, THUMBNAIL_SIZE)
                if (frameBitmap != scaledBitmap) {
                    frameBitmap.recycle()
                }

                // Draw play icon overlay
                val thumbnailWithIcon = drawPlayIconOverlay(scaledBitmap)
                scaledBitmap.recycle()

                // Save to cache
                FileOutputStream(thumbnailFile).use { out ->
                    thumbnailWithIcon.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                thumbnailWithIcon.recycle()

                Timber.d("$TAG: Generated video thumbnail: ${thumbnailFile.absolutePath}")
                thumbnailFile
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error generating video thumbnail")
            null
        }
    }

    /**
     * Scale bitmap to fit within max dimension while preserving aspect ratio.
     */
    private fun scaleBitmapToFit(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        val scale = if (width > height) {
            maxSize.toFloat() / width
        } else {
            maxSize.toFloat() / height
        }

        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Draw a semi-transparent play icon in the center of the bitmap.
     */
    private fun drawPlayIconOverlay(bitmap: Bitmap): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val centerX = result.width / 2f
        val centerY = result.height / 2f

        // Circle background (semi-transparent dark)
        val circleRadius = minOf(result.width, result.height) / 6f
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(centerX, centerY, circleRadius, circlePaint)

        // Play triangle (white)
        val trianglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        val triangleSize = circleRadius * 0.7f
        val trianglePath = Path().apply {
            // Offset slightly right to center the triangle visually
            val offsetX = triangleSize * 0.15f
            moveTo(centerX - triangleSize * 0.4f + offsetX, centerY - triangleSize * 0.6f)
            lineTo(centerX + triangleSize * 0.6f + offsetX, centerY)
            lineTo(centerX - triangleSize * 0.4f + offsetX, centerY + triangleSize * 0.6f)
            close()
        }
        canvas.drawPath(trianglePath, trianglePaint)

        return result
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
