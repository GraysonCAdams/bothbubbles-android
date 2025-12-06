package com.bluebubbles.data.repository

import android.util.Log
import android.util.LruCache
import com.bluebubbles.data.local.db.dao.LinkPreviewDao
import com.bluebubbles.data.local.db.entity.LinkPreviewEntity
import com.bluebubbles.data.local.db.entity.LinkPreviewFetchStatus
import com.bluebubbles.services.linkpreview.LinkMetadataResult
import com.bluebubbles.services.linkpreview.LinkPreviewService
import com.bluebubbles.ui.components.UrlParsingUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for link preview data with multi-level caching.
 *
 * Caching hierarchy:
 * 1. In-memory LRU cache (fastest, cleared on app restart)
 * 2. Room database (persistent, with expiration)
 * 3. Network fetch (only if not cached)
 *
 * Features:
 * - Request deduplication (same URL across messages shares a single fetch)
 * - Background fetching with coroutines
 * - Automatic cache eviction
 */
@Singleton
class LinkPreviewRepository @Inject constructor(
    private val linkPreviewDao: LinkPreviewDao,
    private val linkPreviewService: LinkPreviewService
) {
    companion object {
        private const val TAG = "LinkPreviewRepository"
        private const val MEMORY_CACHE_SIZE = 100
        private const val DB_CACHE_MAX_ENTRIES = 1000
        private const val RETRY_DELAY_MS = 60 * 60 * 1000L // 1 hour
    }

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // In-memory LRU cache
    private val memoryCache = object : LruCache<String, LinkPreviewEntity>(MEMORY_CACHE_SIZE) {
        override fun sizeOf(key: String, value: LinkPreviewEntity): Int = 1
    }

    // In-flight requests for deduplication
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<LinkPreviewEntity?>>()

    // ===== Public API =====

    /**
     * Gets a link preview for a URL.
     * Checks memory cache → database → network (with deduplication)
     */
    suspend fun getLinkPreview(url: String): LinkPreviewEntity? {
        val urlHash = hashUrl(url)

        // 1. Check memory cache
        memoryCache.get(urlHash)?.let { cached ->
            // Update last accessed in background
            repositoryScope.launch {
                linkPreviewDao.updateLastAccessed(urlHash)
            }
            return cached
        }

        // 2. Check for in-flight request (deduplication)
        inFlightRequests[urlHash]?.let { deferred ->
            return deferred.await()
        }

        // 3. Check database
        linkPreviewDao.getByUrlHash(urlHash)?.let { dbEntry ->
            memoryCache.put(urlHash, dbEntry)
            repositoryScope.launch {
                linkPreviewDao.updateLastAccessed(urlHash)
            }

            // If pending or failed (and old enough to retry), trigger fetch
            if (dbEntry.fetchStatus == LinkPreviewFetchStatus.PENDING.name ||
                (dbEntry.fetchStatus == LinkPreviewFetchStatus.FAILED.name &&
                    System.currentTimeMillis() - dbEntry.lastAccessed > RETRY_DELAY_MS)
            ) {
                fetchAndUpdate(url, urlHash)
            }

            return dbEntry
        }

        // 4. Not cached - create pending entry and fetch
        val domain = UrlParsingUtils.extractDomain(url)
        val pendingEntry = LinkPreviewEntity.createPending(url, urlHash, domain)

        linkPreviewDao.insert(pendingEntry)
        memoryCache.put(urlHash, pendingEntry)

        // Start fetch and return pending entry
        fetchAndUpdate(url, urlHash)
        return pendingEntry
    }

    /**
     * Observes a link preview by URL (reactive updates for UI).
     * Triggers fetch if not cached.
     */
    fun observeLinkPreview(url: String): Flow<LinkPreviewEntity?> {
        val urlHash = hashUrl(url)
        return linkPreviewDao.observeByUrlHash(urlHash)
            .onStart {
                // Ensure preview exists/is being fetched
                repositoryScope.launch {
                    getLinkPreview(url)
                }
            }
    }

    /**
     * Gets link previews for multiple URLs (batch operation).
     * Efficiently looks up multiple URLs at once.
     */
    suspend fun getLinkPreviews(urls: List<String>): Map<String, LinkPreviewEntity?> {
        if (urls.isEmpty()) return emptyMap()

        val urlHashes = urls.map { it to hashUrl(it) }
        val results = mutableMapOf<String, LinkPreviewEntity?>()
        val missingFromMemory = mutableListOf<Pair<String, String>>()

        // 1. Check memory cache
        urlHashes.forEach { (url, hash) ->
            memoryCache.get(hash)?.let {
                results[url] = it
            } ?: run {
                missingFromMemory.add(url to hash)
            }
        }

        if (missingFromMemory.isEmpty()) return results

        // 2. Batch fetch from database
        val dbEntries = linkPreviewDao.getByUrlHashes(missingFromMemory.map { it.second })
        val dbEntriesByHash = dbEntries.associateBy { it.urlHash }

        missingFromMemory.forEach { (url, hash) ->
            val dbEntry = dbEntriesByHash[hash]
            if (dbEntry != null) {
                memoryCache.put(hash, dbEntry)
                results[url] = dbEntry
            } else {
                // Not found - create pending entry and queue fetch
                val domain = UrlParsingUtils.extractDomain(url)
                val pendingEntry = LinkPreviewEntity.createPending(url, hash, domain)
                results[url] = pendingEntry

                repositoryScope.launch {
                    linkPreviewDao.insert(pendingEntry)
                    fetchAndUpdate(url, hash)
                }
            }
        }

        return results
    }

    /**
     * Forces a refresh of a link preview (clears cache and re-fetches)
     */
    suspend fun refreshLinkPreview(url: String): LinkPreviewEntity? {
        val urlHash = hashUrl(url)

        // Clear from caches
        memoryCache.remove(urlHash)
        linkPreviewDao.deleteByUrlHash(urlHash)

        // Re-fetch
        return getLinkPreview(url)
    }

    /**
     * Clears all cached link previews
     */
    suspend fun clearCache() {
        memoryCache.evictAll()
        linkPreviewDao.deleteAll()
    }

    /**
     * Performs cache maintenance (evict expired/old entries)
     */
    suspend fun performCacheMaintenance() {
        linkPreviewDao.deleteExpired()
        linkPreviewDao.evictOldEntries(DB_CACHE_MAX_ENTRIES)
    }

    // ===== Private Methods =====

    /**
     * Fetches metadata from network and updates cache
     */
    private fun fetchAndUpdate(url: String, urlHash: String) {
        // Create deferred for deduplication
        val deferred = repositoryScope.async {
            try {
                // Mark as loading
                linkPreviewDao.updateFetchStatus(urlHash, LinkPreviewFetchStatus.LOADING.name)

                when (val result = linkPreviewService.fetchMetadata(url)) {
                    is LinkMetadataResult.Success -> {
                        val metadata = result.metadata
                        val domain = UrlParsingUtils.extractDomain(url)

                        val entry = LinkPreviewEntity(
                            url = url,
                            urlHash = urlHash,
                            domain = domain,
                            title = metadata.title,
                            description = metadata.description,
                            imageUrl = metadata.imageUrl,
                            faviconUrl = metadata.faviconUrl,
                            siteName = metadata.siteName,
                            contentType = metadata.contentType,
                            embedHtml = metadata.embedHtml,
                            authorName = metadata.authorName,
                            authorUrl = metadata.authorUrl,
                            fetchStatus = LinkPreviewFetchStatus.SUCCESS.name,
                            lastAccessed = System.currentTimeMillis()
                        )

                        linkPreviewDao.insert(entry)
                        memoryCache.put(urlHash, entry)
                        Log.d(TAG, "Successfully fetched preview for: $url")
                        entry
                    }

                    is LinkMetadataResult.Error -> {
                        Log.w(TAG, "Failed to fetch preview for $url: ${result.message}")
                        linkPreviewDao.updateFetchStatus(urlHash, LinkPreviewFetchStatus.FAILED.name)
                        null
                    }

                    is LinkMetadataResult.NoPreview -> {
                        Log.d(TAG, "No preview available for: $url")
                        linkPreviewDao.updateFetchStatus(urlHash, LinkPreviewFetchStatus.NO_PREVIEW.name)
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching preview for $url", e)
                linkPreviewDao.updateFetchStatus(urlHash, LinkPreviewFetchStatus.FAILED.name)
                null
            } finally {
                inFlightRequests.remove(urlHash)
            }
        }

        inFlightRequests[urlHash] = deferred
    }

    /**
     * Generates a hash for URL caching
     */
    private fun hashUrl(url: String): String {
        // Normalize URL for consistent caching
        val normalized = UrlParsingUtils.stripTrackingParams(url)
            .lowercase()
            .trimEnd('/')

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(normalized.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback to simple hash
            normalized.hashCode().toString(16)
        }
    }
}
