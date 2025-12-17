package com.bothbubbles.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Handles attachment and media preferences.
 */
class AttachmentPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    // ===== Attachment Settings =====

    /**
     * Whether to automatically download attachments when opening a chat.
     * When true: attachments are downloaded immediately when a chat is opened.
     * When false: attachments show a placeholder with tap-to-download.
     * Default: true (auto-download for seamless experience)
     */
    val autoDownloadAttachments: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.AUTO_DOWNLOAD_ATTACHMENTS] ?: true
    }

    /**
     * Default image quality for attachment uploads.
     * Values: "AUTO", "STANDARD", "HIGH", "ORIGINAL"
     * Default: "STANDARD" (good balance of quality and file size)
     */
    val defaultImageQuality: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_IMAGE_QUALITY] ?: "STANDARD"
    }

    /**
     * Whether to remember the last-used quality setting per session.
     * When true: if user changes quality for one message, that quality is used for subsequent messages.
     * When false: always use the default quality setting.
     */
    val rememberLastQuality: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.REMEMBER_LAST_QUALITY] ?: false
    }

    // ===== Video Compression =====

    /**
     * Video compression quality for uploads.
     * Values: "original", "high", "medium", "low"
     */
    val videoCompressionQuality: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.VIDEO_COMPRESSION_QUALITY] ?: "medium"
    }

    /**
     * Whether to compress videos before upload.
     */
    val compressVideosBeforeUpload: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.COMPRESS_VIDEOS_BEFORE_UPLOAD] ?: true
    }

    /**
     * Maximum concurrent downloads.
     */
    val maxConcurrentDownloads: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.MAX_CONCURRENT_DOWNLOADS] ?: 2
    }

    // ===== Setters =====

    suspend fun setAutoDownloadAttachments(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_DOWNLOAD_ATTACHMENTS] = enabled
        }
    }

    suspend fun setDefaultImageQuality(quality: String) {
        dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_IMAGE_QUALITY] = quality
        }
    }

    suspend fun setRememberLastQuality(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.REMEMBER_LAST_QUALITY] = enabled
        }
    }

    suspend fun setVideoCompressionQuality(quality: String) {
        dataStore.edit { prefs ->
            prefs[Keys.VIDEO_COMPRESSION_QUALITY] = quality
        }
    }

    suspend fun setCompressVideosBeforeUpload(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.COMPRESS_VIDEOS_BEFORE_UPLOAD] = enabled
        }
    }

    suspend fun setMaxConcurrentDownloads(count: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.MAX_CONCURRENT_DOWNLOADS] = count.coerceIn(1, 5)
        }
    }

    private object Keys {
        val AUTO_DOWNLOAD_ATTACHMENTS = booleanPreferencesKey("auto_download_attachments")
        val DEFAULT_IMAGE_QUALITY = stringPreferencesKey("default_image_quality")
        val REMEMBER_LAST_QUALITY = booleanPreferencesKey("remember_last_quality")

        // Video Compression
        val VIDEO_COMPRESSION_QUALITY = stringPreferencesKey("video_compression_quality")
        val COMPRESS_VIDEOS_BEFORE_UPLOAD = booleanPreferencesKey("compress_videos_before_upload")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
    }
}
