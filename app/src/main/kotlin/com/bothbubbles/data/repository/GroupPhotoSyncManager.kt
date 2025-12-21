package com.bothbubbles.data.repository

import android.content.Context
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.util.NetworkConfig
import com.bothbubbles.util.retryWithBackoff
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages synchronization of iMessage group photos from the BlueBubbles server.
 *
 * When iMessage groups have custom photos set by participants, the server exposes
 * these via the groupPhotoGuid field on chat objects. This manager:
 * - Downloads group photos when they are new or changed
 * - Stores them locally in the app's files directory
 * - Updates the chat entity with the local path
 *
 * This is separate from customAvatarPath which is set by the user on the Android device.
 * Priority for avatar display: customAvatarPath > serverGroupPhotoPath > participant collage
 */
@Singleton
class GroupPhotoSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatDao: ChatDao,
    private val settingsDataStore: SettingsDataStore,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "GroupPhotoSync"
        private const val GROUP_PHOTOS_DIR = "group_photos"
    }

    private val groupPhotosDir: File by lazy {
        File(context.filesDir, GROUP_PHOTOS_DIR).also { it.mkdirs() }
    }

    /**
     * Sync a group photo for a chat if the server provides one.
     *
     * @param chatGuid The chat's GUID
     * @param serverGroupPhotoGuid The attachment GUID from the server (e.g., "at_0_xxx")
     * @return true if a photo was downloaded/updated, false if already up-to-date or no photo
     */
    suspend fun syncGroupPhoto(
        chatGuid: String,
        serverGroupPhotoGuid: String?
    ): Boolean = withContext(Dispatchers.IO) {
        // No group photo on server
        if (serverGroupPhotoGuid == null) {
            // Check if we had one before and should clear it
            val chat = chatDao.getChatByGuid(chatGuid)
            if (chat?.serverGroupPhotoGuid != null) {
                Timber.tag(TAG).d("Group photo removed for chat $chatGuid, clearing local copy")
                clearGroupPhoto(chatGuid)
            }
            return@withContext false
        }

        // Check if we already have this photo
        val chat = chatDao.getChatByGuid(chatGuid)
        if (chat?.serverGroupPhotoGuid == serverGroupPhotoGuid && chat.serverGroupPhotoPath != null) {
            // Verify the file still exists
            val existingFile = File(chat.serverGroupPhotoPath)
            if (existingFile.exists()) {
                Timber.tag(TAG).d("Group photo for chat $chatGuid already downloaded, skipping")
                return@withContext false
            }
            // File was deleted, need to re-download
            Timber.tag(TAG).d("Group photo file missing for chat $chatGuid, re-downloading")
        }

        // Download the new photo
        try {
            val localPath = downloadGroupPhoto(chatGuid, serverGroupPhotoGuid)
            chatDao.updateServerGroupPhoto(chatGuid, serverGroupPhotoGuid, localPath)
            Timber.tag(TAG).i("Downloaded group photo for chat $chatGuid")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to download group photo for chat $chatGuid")
            false
        }
    }

    /**
     * Download a group photo from the server.
     *
     * @param chatGuid The chat's GUID (used for filename)
     * @param attachmentGuid The attachment GUID for the group photo
     * @return The local file path where the photo was saved
     */
    private suspend fun downloadGroupPhoto(
        chatGuid: String,
        attachmentGuid: String
    ): String = withContext(Dispatchers.IO) {
        val serverAddress = settingsDataStore.serverAddress.first()
            ?: throw IllegalStateException("No server address configured")

        val downloadUrl = "$serverAddress/api/v1/attachment/$attachmentGuid/download"

        // Use a stable filename based on chat GUID (not attachment GUID)
        // This way old photos are automatically overwritten
        val sanitizedChatGuid = chatGuid.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outputFile = File(groupPhotosDir, "$sanitizedChatGuid.jpg")

        retryWithBackoff(
            times = NetworkConfig.ATTACHMENT_RETRY_ATTEMPTS,
            initialDelayMs = NetworkConfig.ATTACHMENT_INITIAL_DELAY_MS,
            maxDelayMs = NetworkConfig.ATTACHMENT_MAX_DELAY_MS
        ) {
            val request = Request.Builder()
                .url(downloadUrl)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Download failed: ${response.code}")
                }

                val body = response.body
                    ?: throw IOException("Empty response body")

                FileOutputStream(outputFile).use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
        }

        outputFile.absolutePath
    }

    /**
     * Clear the group photo for a chat (e.g., when the server no longer provides one).
     */
    private suspend fun clearGroupPhoto(chatGuid: String) {
        val chat = chatDao.getChatByGuid(chatGuid) ?: return

        // Delete the local file if it exists
        chat.serverGroupPhotoPath?.let { path ->
            try {
                File(path).delete()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to delete group photo file: $path")
            }
        }

        // Clear the database fields
        chatDao.updateServerGroupPhoto(chatGuid, null, null)
    }

    /**
     * Sync group photos for multiple chats in batch.
     * Used during initial sync or full refresh.
     *
     * @param chatPhotos Map of chatGuid to groupPhotoGuid
     * @return Number of photos successfully downloaded
     */
    suspend fun syncGroupPhotos(chatPhotos: Map<String, String?>): Int {
        var downloadCount = 0
        for ((chatGuid, photoGuid) in chatPhotos) {
            if (syncGroupPhoto(chatGuid, photoGuid)) {
                downloadCount++
            }
        }
        return downloadCount
    }
}
