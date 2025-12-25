package com.bothbubbles.services.storage

import android.content.Context
import com.bothbubbles.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage usage breakdown by content type.
 */
data class StorageBreakdown(
    val images: Long = 0L,
    val videos: Long = 0L,
    val documents: Long = 0L,
    val linkPreviews: Long = 0L,
    val socialMedia: Long = 0L,
    val other: Long = 0L
) {
    val total: Long get() = images + videos + documents + linkPreviews + socialMedia + other

    companion object {
        val EMPTY = StorageBreakdown()
    }
}

/**
 * Content type categories for storage management.
 */
enum class StorageCategory {
    IMAGES,
    VIDEOS,
    DOCUMENTS,
    LINK_PREVIEWS,
    SOCIAL_MEDIA,
    ALL
}

/**
 * Service for managing app storage and cache.
 * Provides methods to calculate storage usage and clear specific content types.
 */
@Singleton
class StorageManagementService @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    companion object {
        // File extensions for categorization
        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tiff"
        )
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mov", "avi", "mkv", "webm", "m4v", "3gp", "wmv"
        )
        private val DOCUMENT_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf",
            "csv", "xml", "json", "html", "htm", "md", "zip", "rar", "7z"
        )
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "wav", "aac", "ogg", "flac", "wma"
        )

        // Cache directory names
        private const val LINK_PREVIEW_CACHE_DIR = "link_previews"
        private const val SOCIAL_MEDIA_CACHE_DIR = "social_media_videos"
        private const val COIL_CACHE_DIR = "image_cache"
    }

    /**
     * Calculates storage usage breakdown by content type.
     */
    suspend fun calculateStorageBreakdown(): StorageBreakdown = withContext(ioDispatcher) {
        var images = 0L
        var videos = 0L
        var documents = 0L
        var linkPreviews = 0L
        var socialMedia = 0L
        var other = 0L

        try {
            // Calculate cache directory usage
            context.cacheDir?.let { cacheDir ->
                val cacheBreakdown = calculateDirectoryBreakdown(cacheDir)
                images += cacheBreakdown.images
                videos += cacheBreakdown.videos
                documents += cacheBreakdown.documents
                linkPreviews += cacheBreakdown.linkPreviews
                socialMedia += cacheBreakdown.socialMedia
                other += cacheBreakdown.other
            }

            // Calculate files directory usage (attachments)
            context.filesDir?.let { filesDir ->
                val filesBreakdown = calculateDirectoryBreakdown(filesDir)
                images += filesBreakdown.images
                videos += filesBreakdown.videos
                documents += filesBreakdown.documents
                socialMedia += filesBreakdown.socialMedia
                other += filesBreakdown.other
            }

            // Calculate external cache usage if available
            context.externalCacheDir?.let { externalCache ->
                val externalBreakdown = calculateDirectoryBreakdown(externalCache)
                images += externalBreakdown.images
                videos += externalBreakdown.videos
                documents += externalBreakdown.documents
                linkPreviews += externalBreakdown.linkPreviews
                socialMedia += externalBreakdown.socialMedia
                other += externalBreakdown.other
            }

            // Coil image cache (in cache directory)
            val coilCacheDir = File(context.cacheDir, COIL_CACHE_DIR)
            if (coilCacheDir.exists()) {
                val coilSize = calculateDirectorySize(coilCacheDir)
                images += coilSize
            }

        } catch (e: Exception) {
            Timber.e(e, "Error calculating storage breakdown")
        }

        StorageBreakdown(
            images = images,
            videos = videos,
            documents = documents,
            linkPreviews = linkPreviews,
            socialMedia = socialMedia,
            other = other
        )
    }

    private fun calculateDirectoryBreakdown(directory: File): StorageBreakdown {
        var images = 0L
        var videos = 0L
        var documents = 0L
        var linkPreviews = 0L
        var socialMedia = 0L
        var other = 0L

        try {
            directory.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val size = file.length()
                    val extension = file.extension.lowercase()
                    val parentName = file.parentFile?.name ?: ""

                    // Check if it's in social media cache (must check first - these are mp4 files)
                    if (parentName.contains(SOCIAL_MEDIA_CACHE_DIR, ignoreCase = true)) {
                        socialMedia += size
                    // Check if it's in link preview cache
                    } else if (parentName.contains(LINK_PREVIEW_CACHE_DIR, ignoreCase = true)) {
                        linkPreviews += size
                    } else when {
                        IMAGE_EXTENSIONS.contains(extension) -> images += size
                        VIDEO_EXTENSIONS.contains(extension) -> videos += size
                        DOCUMENT_EXTENSIONS.contains(extension) -> documents += size
                        AUDIO_EXTENSIONS.contains(extension) -> documents += size // Group audio with docs
                        else -> other += size
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error calculating breakdown for ${directory.path}")
        }

        return StorageBreakdown(
            images = images,
            videos = videos,
            documents = documents,
            linkPreviews = linkPreviews,
            socialMedia = socialMedia,
            other = other
        )
    }

    private fun calculateDirectorySize(directory: File): Long {
        var size = 0L
        try {
            directory.walkTopDown().forEach { file ->
                if (file.isFile) {
                    size += file.length()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error calculating size for ${directory.path}")
        }
        return size
    }

    /**
     * Clears cache for a specific content category.
     * @param category The category to clear
     * @return The number of bytes freed
     */
    suspend fun clearCategory(category: StorageCategory): Long = withContext(ioDispatcher) {
        when (category) {
            StorageCategory.ALL -> clearAllCache()
            StorageCategory.IMAGES -> clearImages()
            StorageCategory.VIDEOS -> clearVideos()
            StorageCategory.DOCUMENTS -> clearDocuments()
            StorageCategory.LINK_PREVIEWS -> clearLinkPreviews()
            StorageCategory.SOCIAL_MEDIA -> clearSocialMedia()
        }
    }

    private fun clearAllCache(): Long {
        var freedBytes = 0L

        try {
            // Clear main cache directory (excluding databases)
            context.cacheDir?.let { cacheDir ->
                freedBytes += clearDirectoryContents(cacheDir)
            }

            // Clear external cache
            context.externalCacheDir?.let { externalCache ->
                freedBytes += clearDirectoryContents(externalCache)
            }

            Timber.d("Cleared all cache: freed ${formatBytes(freedBytes)}")
        } catch (e: Exception) {
            Timber.e(e, "Error clearing all cache")
        }

        return freedBytes
    }

    private fun clearImages(): Long {
        var freedBytes = 0L

        try {
            // Clear Coil image cache
            val coilCacheDir = File(context.cacheDir, COIL_CACHE_DIR)
            if (coilCacheDir.exists()) {
                freedBytes += clearDirectoryContents(coilCacheDir)
            }

            // Clear image files from cache and files directories
            freedBytes += clearFilesByExtensions(context.cacheDir, IMAGE_EXTENSIONS)
            freedBytes += clearFilesByExtensions(context.filesDir, IMAGE_EXTENSIONS)
            context.externalCacheDir?.let {
                freedBytes += clearFilesByExtensions(it, IMAGE_EXTENSIONS)
            }

            Timber.d("Cleared images: freed ${formatBytes(freedBytes)}")
        } catch (e: Exception) {
            Timber.e(e, "Error clearing images")
        }

        return freedBytes
    }

    private fun clearVideos(): Long {
        var freedBytes = 0L

        try {
            freedBytes += clearFilesByExtensions(context.cacheDir, VIDEO_EXTENSIONS)
            freedBytes += clearFilesByExtensions(context.filesDir, VIDEO_EXTENSIONS)
            context.externalCacheDir?.let {
                freedBytes += clearFilesByExtensions(it, VIDEO_EXTENSIONS)
            }

            Timber.d("Cleared videos: freed ${formatBytes(freedBytes)}")
        } catch (e: Exception) {
            Timber.e(e, "Error clearing videos")
        }

        return freedBytes
    }

    private fun clearDocuments(): Long {
        var freedBytes = 0L

        try {
            val allDocExtensions = DOCUMENT_EXTENSIONS + AUDIO_EXTENSIONS
            freedBytes += clearFilesByExtensions(context.cacheDir, allDocExtensions)
            freedBytes += clearFilesByExtensions(context.filesDir, allDocExtensions)
            context.externalCacheDir?.let {
                freedBytes += clearFilesByExtensions(it, allDocExtensions)
            }

            Timber.d("Cleared documents: freed ${formatBytes(freedBytes)}")
        } catch (e: Exception) {
            Timber.e(e, "Error clearing documents")
        }

        return freedBytes
    }

    private fun clearLinkPreviews(): Long {
        var freedBytes = 0L

        try {
            // Find and clear link preview cache directories
            context.cacheDir?.let { cacheDir ->
                freedBytes += clearDirectoriesMatching(cacheDir, LINK_PREVIEW_CACHE_DIR)
            }
            context.externalCacheDir?.let { externalCache ->
                freedBytes += clearDirectoriesMatching(externalCache, LINK_PREVIEW_CACHE_DIR)
            }

            Timber.d("Cleared link previews: freed ${formatBytes(freedBytes)}")
        } catch (e: Exception) {
            Timber.e(e, "Error clearing link previews")
        }

        return freedBytes
    }

    private fun clearSocialMedia(): Long {
        var freedBytes = 0L

        try {
            // Find and clear social media video cache directories
            context.cacheDir?.let { cacheDir ->
                freedBytes += clearDirectoriesMatching(cacheDir, SOCIAL_MEDIA_CACHE_DIR)
            }
            context.externalCacheDir?.let { externalCache ->
                freedBytes += clearDirectoriesMatching(externalCache, SOCIAL_MEDIA_CACHE_DIR)
            }

            Timber.d("Cleared social media: freed ${formatBytes(freedBytes)}")
        } catch (e: Exception) {
            Timber.e(e, "Error clearing social media")
        }

        return freedBytes
    }

    private fun clearDirectoryContents(directory: File?): Long {
        if (directory == null || !directory.exists()) return 0L

        var freedBytes = 0L
        try {
            directory.listFiles()?.forEach { file ->
                // Skip database files
                if (file.name.endsWith(".db") || file.name.endsWith(".db-journal") ||
                    file.name.endsWith(".db-shm") || file.name.endsWith(".db-wal")) {
                    return@forEach
                }

                if (file.isDirectory) {
                    // Skip databases directory
                    if (file.name == "databases") return@forEach
                    freedBytes += clearDirectoryContents(file)
                    file.delete()
                } else {
                    freedBytes += file.length()
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error clearing directory: ${directory.path}")
        }
        return freedBytes
    }

    private fun clearFilesByExtensions(directory: File?, extensions: Set<String>): Long {
        if (directory == null || !directory.exists()) return 0L

        var freedBytes = 0L
        try {
            directory.walkTopDown().forEach { file ->
                if (file.isFile && extensions.contains(file.extension.lowercase())) {
                    freedBytes += file.length()
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error clearing files by extension in: ${directory.path}")
        }
        return freedBytes
    }

    private fun clearDirectoriesMatching(rootDir: File?, namePattern: String): Long {
        if (rootDir == null || !rootDir.exists()) return 0L

        var freedBytes = 0L
        try {
            rootDir.walkTopDown().forEach { file ->
                if (file.isDirectory && file.name.contains(namePattern, ignoreCase = true)) {
                    freedBytes += clearDirectoryContents(file)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error clearing directories matching pattern in: ${rootDir.path}")
        }
        return freedBytes
    }

    /**
     * Formats bytes to human-readable string.
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
