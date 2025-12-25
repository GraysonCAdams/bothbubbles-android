package com.bothbubbles.services.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.repository.LinkPreviewCompletion
import com.bothbubbles.data.repository.LinkPreviewRepository
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.ActiveConversationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Updates message notifications with link preview data after metadata is fetched.
 *
 * When a user receives a message containing a URL:
 * 1. Initial notification shows just the URL or domain
 * 2. Link preview metadata is fetched in background
 * 3. This service updates the notification with title, domain, and preview image
 *
 * Only processes recent messages (within last 5 minutes) to avoid updating
 * stale notifications the user may have already dismissed.
 */
@Singleton
class NotificationLinkPreviewUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val linkPreviewRepository: LinkPreviewRepository,
    private val messageDao: MessageDao,
    private val notificationService: NotificationService,
    private val notificationParamsBuilder: NotificationParamsBuilder,
    private val activeConversationManager: ActiveConversationManager,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "NotificationLinkPreviewUpdater"

        // Only update notifications for messages received within this window
        private const val MAX_MESSAGE_AGE_MS = 5 * 60 * 1000L // 5 minutes

        // Preview image download settings
        private const val IMAGE_TIMEOUT_MS = 10_000L
        private const val MAX_IMAGE_SIZE = 1024 * 1024 // 1MB max
        private const val PREVIEW_IMAGE_SIZE = 512

        // Cache directory for downloaded preview images
        private const val PREVIEW_IMAGE_CACHE_DIR = "link_preview_images"
    }

    // HTTP client for downloading preview images
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(IMAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(IMAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Start listening for link preview completions.
     * Should be called once during app initialization.
     */
    fun initialize() {
        Timber.d("$TAG: Initializing notification link preview updater")

        applicationScope.launch(ioDispatcher) {
            Timber.d("$TAG: Starting to collect previewCompletions flow")
            linkPreviewRepository.previewCompletions.collect { completion ->
                Timber.d("$TAG: Received preview completion for: ${completion.url}")
                handlePreviewCompletion(completion)
            }
        }
    }

    private suspend fun handlePreviewCompletion(completion: LinkPreviewCompletion) {
        try {
            val preview = completion.preview

            // Skip if no useful preview data
            if (preview.title.isNullOrBlank() && preview.imageUrl.isNullOrBlank()) {
                Timber.d("$TAG: No useful preview data for: ${completion.url}")
                return
            }

            // Look up message to verify it exists and check age
            val message = messageDao.getMessageByGuid(completion.messageGuid)
            if (message == null) {
                Timber.w("$TAG: Message not found: ${completion.messageGuid}")
                return
            }

            // Check if message is recent enough to update notification
            val messageAge = System.currentTimeMillis() - message.dateCreated
            if (messageAge > MAX_MESSAGE_AGE_MS) {
                Timber.d("$TAG: Message too old (${messageAge}ms), skipping notification update")
                return
            }

            // Check if user is currently viewing this conversation
            if (activeConversationManager.isConversationActive(completion.chatGuid)) {
                Timber.d("$TAG: Chat ${completion.chatGuid} is active, skipping notification update")
                return
            }

            // Download preview image if available
            var attachmentUri: Uri? = null
            var attachmentMimeType: String? = null

            val imageUrl = preview.imageUrl
            if (!imageUrl.isNullOrBlank()) {
                val imageFile = downloadPreviewImage(imageUrl, completion.messageGuid)
                if (imageFile != null) {
                    try {
                        attachmentUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            imageFile
                        )
                        attachmentMimeType = "image/png"
                        Timber.d("$TAG: Downloaded preview image: $attachmentUri")
                    } catch (e: Exception) {
                        Timber.w(e, "$TAG: Failed to get FileProvider URI for preview image")
                    }
                }
            }

            // Get message text
            val messageText = message.text ?: ""

            Timber.d("$TAG: Updating notification for chat ${completion.chatGuid} with link preview")

            // Build notification params using shared builder (ensures consistent contact caching)
            val params = notificationParamsBuilder.buildParams(
                messageGuid = message.guid,
                chatGuid = completion.chatGuid,
                messageText = messageText,
                subject = message.subject,
                senderAddress = message.senderAddress,
                linkPreviewTitle = preview.title,
                linkPreviewDomain = preview.domain ?: preview.siteName,
                attachmentUri = attachmentUri,
                attachmentMimeType = attachmentMimeType,
                fetchLinkPreview = false // Already have the link preview data
            )

            if (params != null) {
                notificationService.showMessageNotification(params)
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error updating notification for link preview")
        }
    }

    /**
     * Downloads the preview image and saves it to cache.
     * Returns the local file, or null if download failed.
     */
    private suspend fun downloadPreviewImage(imageUrl: String, messageGuid: String): File? {
        return withContext(ioDispatcher) {
            try {
                // Create cache directory
                val cacheDir = File(context.cacheDir, PREVIEW_IMAGE_CACHE_DIR)
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }

                // Generate unique filename from URL hash
                val urlHash = hashUrl(imageUrl)
                val imageFile = File(cacheDir, "${urlHash}_preview.png")

                // Check if already cached
                if (imageFile.exists()) {
                    Timber.d("$TAG: Using cached preview image: ${imageFile.absolutePath}")
                    return@withContext imageFile
                }

                // Download image
                val request = Request.Builder()
                    .url(imageUrl)
                    .header("User-Agent", "Mozilla/5.0 (compatible; BothBubbles/1.0)")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Timber.w("$TAG: Failed to download preview image: ${response.code}")
                    return@withContext null
                }

                val body = response.body ?: return@withContext null
                val contentLength = body.contentLength()

                // Check file size
                if (contentLength > MAX_IMAGE_SIZE) {
                    Timber.w("$TAG: Preview image too large: $contentLength bytes")
                    body.close()
                    return@withContext null
                }

                // Decode and resize bitmap
                val inputStream = body.byteStream()
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (originalBitmap == null) {
                    Timber.w("$TAG: Failed to decode preview image")
                    return@withContext null
                }

                // Scale to reasonable size
                val scaledBitmap = scaleBitmapToFit(originalBitmap, PREVIEW_IMAGE_SIZE)
                if (originalBitmap != scaledBitmap) {
                    originalBitmap.recycle()
                }

                // Save to cache
                FileOutputStream(imageFile).use { out ->
                    scaledBitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                scaledBitmap.recycle()

                Timber.d("$TAG: Downloaded preview image: ${imageFile.absolutePath}")
                imageFile
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Error downloading preview image: $imageUrl")
                null
            }
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
     * Generate a short hash for the URL to use as filename.
     */
    private fun hashUrl(url: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(url.toByteArray())
            hashBytes.take(16).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            url.hashCode().toString(16)
        }
    }
}
