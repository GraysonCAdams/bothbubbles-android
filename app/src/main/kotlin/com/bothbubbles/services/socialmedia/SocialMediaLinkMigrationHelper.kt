package com.bothbubbles.services.socialmedia

import android.content.Context
import android.content.SharedPreferences
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.SocialMediaLinkDao
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import com.bothbubbles.core.model.entity.SocialMediaLinkEntity
import com.bothbubbles.core.model.entity.SocialMediaPlatform

/**
 * Handles one-time migration of social media links from existing messages.
 *
 * This class:
 * 1. Scans existing messages for social media URLs (filtering out reactions)
 * 2. Populates the social_media_links table with correct sender attribution
 * 3. Repairs cached video metadata that has incorrect sender info (e.g., "You" for received messages)
 *
 * Migration is idempotent and tracks completion state in SharedPreferences.
 */
@Singleton
class SocialMediaLinkMigrationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val socialMediaLinkDao: SocialMediaLinkDao,
    private val handleRepository: HandleRepository,
    private val cacheManager: SocialMediaCacheManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val PREFS_NAME = "social_media_link_migration"
        private const val KEY_MIGRATION_COMPLETED = "migration_completed_v1"
        private const val KEY_METADATA_REPAIR_COMPLETED = "metadata_repair_completed_v1"

        // Instagram URL patterns
        private val INSTAGRAM_PATTERN = Regex(
            """https?://(?:www\.)?instagram\.com/(?:reel|reels|p|share/reel|share/p)/[A-Za-z0-9_-]+[^\s]*"""
        )

        // TikTok URL patterns
        private val TIKTOK_PATTERN = Regex(
            """https?://(?:www\.|vm\.|vt\.)?tiktok\.com/[^\s]+"""
        )
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Run migration if not already completed.
     * Should be called once at app startup.
     */
    suspend fun runMigrationIfNeeded() {
        withContext(ioDispatcher) {
            // Run link migration
            if (!prefs.getBoolean(KEY_MIGRATION_COMPLETED, false)) {
                try {
                    migrateExistingLinks()
                    prefs.edit().putBoolean(KEY_MIGRATION_COMPLETED, true).apply()
                    Timber.i("[SocialMediaMigration] Link migration completed successfully")
                } catch (e: Exception) {
                    Timber.e(e, "[SocialMediaMigration] Link migration failed")
                }
            }

            // Run metadata repair
            if (!prefs.getBoolean(KEY_METADATA_REPAIR_COMPLETED, false)) {
                try {
                    repairCachedMetadata()
                    prefs.edit().putBoolean(KEY_METADATA_REPAIR_COMPLETED, true).apply()
                    Timber.i("[SocialMediaMigration] Metadata repair completed successfully")
                } catch (e: Exception) {
                    Timber.e(e, "[SocialMediaMigration] Metadata repair failed")
                }
            }

            // Always deduplicate cache to clean up any duplicate entries
            // (from before we normalized URLs before hashing)
            try {
                val removed = cacheManager.deduplicateCache()
                if (removed > 0) {
                    Timber.i("[SocialMediaMigration] Removed $removed duplicate cache entries")
                }
            } catch (e: Exception) {
                Timber.w(e, "[SocialMediaMigration] Cache deduplication failed")
            }
        }
    }

    /**
     * Migrate existing messages with social media URLs to the social_media_links table.
     * Filters out reaction messages that quote the URL.
     *
     * @throws IllegalStateException if no messages exist yet (defers migration until sync completes)
     */
    private suspend fun migrateExistingLinks() {
        Timber.d("[SocialMediaMigration] Starting link migration from existing messages")

        // Query messages with social media URLs, excluding reactions
        // Reactions have associated_message_type set (e.g., "love", "like", etc.)
        val messages = messageDao.getMessagesWithSocialMediaUrlsExcludingReactions()
        Timber.d("[SocialMediaMigration] Found ${messages.size} messages with social media URLs (excluding reactions)")

        // If no messages with social URLs found, check if we should defer migration
        // We defer if there are cached videos but no messages - suggests sync hasn't completed
        if (messages.isEmpty()) {
            val cachedVideos = cacheManager.getAllCachedVideos()
            if (cachedVideos.isNotEmpty()) {
                Timber.d("[SocialMediaMigration] Found ${cachedVideos.size} cached videos but no messages - deferring migration")
                throw IllegalStateException("Messages not synced yet, deferring migration")
            }
            // No cached videos and no messages - nothing to migrate
            Timber.d("[SocialMediaMigration] No cached videos and no messages - migration complete (nothing to do)")
            return
        }

        var insertedCount = 0
        val linksToInsert = mutableListOf<SocialMediaLinkEntity>()

        for (message in messages) {
            val text = message.text ?: continue
            val urls = extractSocialMediaUrls(text)

            for (url in urls) {
                val platform = detectPlatform(url) ?: continue
                val urlHash = hashUrl(url)

                val link = SocialMediaLinkEntity(
                    urlHash = urlHash,
                    url = url,
                    messageGuid = message.guid,
                    chatGuid = message.chatGuid,
                    platform = platform.name,
                    senderAddress = message.senderAddress,
                    isFromMe = message.isFromMe,
                    sentTimestamp = message.dateCreated,
                    isDownloaded = false, // Will be updated by sync with cache manager
                    createdAt = System.currentTimeMillis()
                )

                linksToInsert.add(link)
            }

            // Batch insert every 100 links
            if (linksToInsert.size >= 100) {
                socialMediaLinkDao.insertAll(linksToInsert)
                insertedCount += linksToInsert.size
                linksToInsert.clear()
            }
        }

        // Insert remaining
        if (linksToInsert.isNotEmpty()) {
            socialMediaLinkDao.insertAll(linksToInsert)
            insertedCount += linksToInsert.size
        }

        Timber.i("[SocialMediaMigration] Inserted $insertedCount social media links")

        // Now sync downloaded status with cache manager
        syncDownloadedStatus()
    }

    /**
     * Sync the is_downloaded flag with the cache manager.
     * Marks links as downloaded if they exist in the video cache.
     */
    private suspend fun syncDownloadedStatus() {
        val cachedVideos = cacheManager.getAllCachedVideos()
        Timber.d("[SocialMediaMigration] Syncing downloaded status for ${cachedVideos.size} cached videos")

        var updatedCount = 0
        for (video in cachedVideos) {
            val urlHash = hashUrl(video.originalUrl)
            if (socialMediaLinkDao.exists(urlHash)) {
                socialMediaLinkDao.markAsDownloaded(urlHash)
                updatedCount++
            }
        }

        Timber.d("[SocialMediaMigration] Marked $updatedCount links as downloaded")
    }

    /**
     * Repair cached video metadata that has incorrect sender info.
     *
     * The bug: When a user adds a tapback to a social media link, the tapback message
     * includes the URL in its text (e.g., 'Loved "https://instagram.com/..."').
     * The auto-cache used the tapback message's is_from_me=true to set senderName="You",
     * even though the original video was sent by someone else.
     *
     * Fix: Look up the correct sender info from messages table directly (not social_media_links)
     * since the link migration may not have run yet.
     */
    private suspend fun repairCachedMetadata() {
        val cachedVideos = cacheManager.getAllCachedVideos()
        Timber.d("[SocialMediaMigration] Checking ${cachedVideos.size} cached videos for metadata repair")

        if (cachedVideos.isEmpty()) {
            Timber.d("[SocialMediaMigration] No cached videos to repair")
            return
        }

        // Get all messages with social media URLs (excluding reactions) to lookup sender info
        val socialMediaMessages = messageDao.getMessagesWithSocialMediaUrlsExcludingReactions()
        Timber.d("[SocialMediaMigration] Found ${socialMediaMessages.size} messages for sender lookup")

        if (socialMediaMessages.isEmpty()) {
            // Messages haven't synced yet - don't mark repair as complete
            Timber.d("[SocialMediaMigration] No messages available for repair lookup - will retry later")
            throw IllegalStateException("No messages available for metadata repair")
        }

        // Build a map of normalized URL -> message info for quick lookup
        // Normalize URLs to just the video ID portion to handle query param differences
        val urlToMessage = mutableMapOf<String, com.bothbubbles.core.model.entity.MessageEntity>()
        for (message in socialMediaMessages) {
            val text = message.text ?: continue
            val urls = extractSocialMediaUrls(text)
            for (url in urls) {
                val normalizedUrl = normalizeUrl(url)
                // First match wins (oldest message with this URL)
                if (!urlToMessage.containsKey(normalizedUrl)) {
                    urlToMessage[normalizedUrl] = message
                }
            }
        }

        var repairedCount = 0
        for (video in cachedVideos) {
            val normalizedVideoUrl = normalizeUrl(video.originalUrl)
            val originalMessage = urlToMessage[normalizedVideoUrl]

            // Skip if we can't find the original message
            if (originalMessage == null) continue

            // Check if this video needs repair:
            // 1. senderName is "You" but message is not from me
            // 2. senderName is empty/blank but we have sender info
            val needsRepair = when {
                video.senderName == "You" && !originalMessage.isFromMe -> true
                video.senderName.isNullOrBlank() && originalMessage.senderAddress != null -> true
                else -> false
            }

            if (needsRepair) {
                val senderAddress = originalMessage.senderAddress
                val handle = senderAddress?.let { handleRepository.getHandleByAddressAny(it) }
                val correctSenderName = if (originalMessage.isFromMe) {
                    "You"
                } else {
                    handle?.cachedDisplayName
                        ?: handle?.inferredName
                        ?: senderAddress
                        ?: "Unknown"
                }

                // Update the cached metadata
                cacheManager.updateVideoMetadata(
                    originalUrl = video.originalUrl,
                    senderName = correctSenderName,
                    senderAddress = senderAddress
                )

                repairedCount++
                Timber.d("[SocialMediaMigration] Repaired metadata for ${video.originalUrl.take(50)}: '$correctSenderName' (was '${video.senderName}')")
            }
        }

        Timber.i("[SocialMediaMigration] Repaired $repairedCount cached video metadata entries")
    }

    /**
     * Extract social media URLs from message text.
     */
    private fun extractSocialMediaUrls(text: String): List<String> {
        val urls = mutableListOf<String>()

        // Find Instagram URLs
        urls.addAll(INSTAGRAM_PATTERN.findAll(text).map { it.value })

        // Find TikTok URLs
        urls.addAll(TIKTOK_PATTERN.findAll(text).map { it.value })

        return urls.distinct()
    }

    /**
     * Normalize URL by extracting just the base path (without query params or trailing junk).
     * This handles URLs like:
     * - https://www.instagram.com/reel/DSleht5E4HE/?igsh=MTZ0ejBzdjlrNjM3Mg=="
     * - https://www.instagram.com/reel/DSleht5E4HE/?igsh=MTZ0ejBzdjlrNjM3Mg==
     * Both normalize to: https://www.instagram.com/reel/DSleht5E4HE/
     */
    private fun normalizeUrl(url: String): String {
        return try {
            // Strip query params and any trailing quotes/junk
            val cleanUrl = url.replace("\"", "").replace("'", "")
            val uri = android.net.Uri.parse(cleanUrl)
            val pathOnly = uri.path?.trimEnd('/') ?: ""
            "${uri.scheme}://${uri.host}$pathOnly"
        } catch (e: Exception) {
            // Fallback: just strip everything after ?
            url.substringBefore("?").trimEnd('/')
        }
    }

    /**
     * Detect platform from URL.
     */
    private fun detectPlatform(url: String): SocialMediaPlatform? {
        return when {
            url.contains("instagram.com") -> SocialMediaPlatform.INSTAGRAM
            url.contains("tiktok.com") -> SocialMediaPlatform.TIKTOK
            else -> null
        }
    }

    /**
     * Hash URL for deduplication (matches SocialMediaCacheManager.hashUrl).
     */
    private fun hashUrl(url: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Reset migration state (for testing).
     */
    fun resetMigrationState() {
        prefs.edit()
            .remove(KEY_MIGRATION_COMPLETED)
            .remove(KEY_METADATA_REPAIR_COMPLETED)
            .apply()
    }
}
