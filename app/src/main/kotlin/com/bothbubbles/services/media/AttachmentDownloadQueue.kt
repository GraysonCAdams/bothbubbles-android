package com.bothbubbles.services.media

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.core.model.entity.AttachmentErrorState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Priority-based download queue for attachments.
 *
 * Features:
 * - Prioritizes active chat downloads over background downloads
 * - Limits concurrent downloads to prevent network congestion
 * - Provides progress tracking for individual downloads
 * - Supports cancellation of queued downloads
 */
@Singleton
class AttachmentDownloadQueue @Inject constructor(
    private val attachmentRepository: AttachmentRepository,
    private val attachmentDao: AttachmentDao,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val MAX_CONCURRENT_DOWNLOADS = 2

        /** Maximum number of retry attempts before permanent failure */
        const val MAX_RETRY_COUNT = 3

        /** Initial delay for exponential backoff (1 second) */
        private const val INITIAL_BACKOFF_MS = 1000L

        /** Maximum delay for exponential backoff (30 seconds) */
        private const val MAX_BACKOFF_MS = 30_000L

        /** Maximum file size for auto-download (50MB) - larger files require manual download */
        private const val MAX_AUTO_DOWNLOAD_SIZE = 50L * 1024 * 1024

        /** Minimum free memory required to start a download (20MB) */
        private const val MIN_FREE_MEMORY_BYTES = 20L * 1024 * 1024
    }

    /**
     * Priority levels for downloads.
     * Lower values = higher priority.
     */
    enum class Priority(val value: Int) {
        IMMEDIATE(0),      // User manually tapped download
        ACTIVE_CHAT(1),    // Currently viewed chat
        VISIBLE(2),        // Visible in scroll area but not active
        BACKGROUND(3)      // Background sync
    }

    /**
     * Represents a queued download request.
     */
    data class DownloadRequest(
        val attachmentGuid: String,
        val chatGuid: String,
        val priority: Priority,
        val timestamp: Long = System.currentTimeMillis()
    ) : Comparable<DownloadRequest> {
        override fun compareTo(other: DownloadRequest): Int {
            // First compare by priority
            val priorityCompare = priority.value.compareTo(other.priority.value)
            if (priorityCompare != 0) return priorityCompare
            // Then by timestamp (older first)
            return timestamp.compareTo(other.timestamp)
        }
    }

    /**
     * Download progress for a single attachment.
     */
    data class DownloadProgress(
        val attachmentGuid: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val isComplete: Boolean = false,
        val error: String? = null,
        val errorType: String? = null,
        val isRetryable: Boolean = true
    ) {
        val progress: Float
            get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f

        val hasFailed: Boolean
            get() = isComplete && error != null
    }

    private val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    // Priority queue for pending downloads
    private val pendingQueue = PriorityBlockingQueue<DownloadRequest>()

    // Track active downloads
    private val activeDownloads = ConcurrentHashMap<String, DownloadRequest>()

    // Track which attachments are already queued
    private val queuedGuids = ConcurrentHashMap.newKeySet<String>()

    // Progress tracking
    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadProgress>> = _downloadProgress.asStateFlow()

    // Queue size for UI
    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

    // Emit completed downloads for UI refresh
    private val _downloadCompletions = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val downloadCompletions: SharedFlow<String> = _downloadCompletions.asSharedFlow()

    // Active chat for prioritization
    @Volatile
    private var activeChatGuid: String? = null

    /**
     * Set the currently active chat for priority boost.
     */
    fun setActiveChat(chatGuid: String?) {
        val previousActive = activeChatGuid
        activeChatGuid = chatGuid

        // If changed, reprioritize existing queue items for the new active chat
        if (chatGuid != null && chatGuid != previousActive) {
            reprioritizeForChat(chatGuid)
        }
    }

    /**
     * Queue an attachment for download.
     *
     * @param attachmentGuid GUID of the attachment to download
     * @param chatGuid GUID of the chat (for prioritization)
     * @param priority Download priority
     */
    fun enqueue(
        attachmentGuid: String,
        chatGuid: String,
        priority: Priority = Priority.BACKGROUND
    ) {
        if (queuedGuids.contains(attachmentGuid)) {
            Timber.d("Attachment $attachmentGuid already queued, skipping")
            return
        }

        val effectivePriority = when {
            priority == Priority.IMMEDIATE -> Priority.IMMEDIATE
            chatGuid == activeChatGuid -> Priority.ACTIVE_CHAT
            else -> priority
        }

        val request = DownloadRequest(
            attachmentGuid = attachmentGuid,
            chatGuid = chatGuid,
            priority = effectivePriority
        )

        queuedGuids.add(attachmentGuid)
        pendingQueue.offer(request)
        updateQueueSize()

        Timber.d("Enqueued download: $attachmentGuid with priority ${effectivePriority.name}")

        // Start processing if not already running
        processQueue()
    }

    /**
     * Queue all pending attachments for a chat.
     */
    suspend fun enqueueAllForChat(
        chatGuid: String,
        priority: Priority = Priority.ACTIVE_CHAT
    ) {
        val pending = attachmentDao.getPendingDownloadsForChat(chatGuid)
        pending.forEach { attachment ->
            enqueue(attachment.guid, chatGuid, priority)
        }
        Timber.d("Enqueued ${pending.size} attachments for chat $chatGuid")
    }

    /**
     * Queue all pending attachments for multiple chats (merged conversations).
     */
    suspend fun enqueueAllForChats(
        chatGuids: List<String>,
        priority: Priority = Priority.ACTIVE_CHAT
    ) {
        chatGuids.forEach { chatGuid ->
            enqueueAllForChat(chatGuid, priority)
        }
    }

    /**
     * Cancel a pending download.
     */
    fun cancel(attachmentGuid: String) {
        pendingQueue.removeIf { it.attachmentGuid == attachmentGuid }
        queuedGuids.remove(attachmentGuid)
        updateQueueSize()
        Timber.d("Cancelled download: $attachmentGuid")
    }

    /**
     * Cancel all pending downloads for a chat.
     */
    fun cancelForChat(chatGuid: String) {
        pendingQueue.removeIf { it.chatGuid == chatGuid }
        updateQueueSize()
        Timber.d("Cancelled all downloads for chat: $chatGuid")
    }

    /**
     * Clear the entire queue.
     */
    fun clearQueue() {
        pendingQueue.clear()
        queuedGuids.clear()
        updateQueueSize()
        Timber.d("Queue cleared")
    }

    /**
     * Get the current position of an attachment in the queue.
     * Returns null if not queued.
     */
    fun getQueuePosition(attachmentGuid: String): Int? {
        val queue = pendingQueue.toTypedArray()
        queue.sortWith(Comparator { a, b -> a.compareTo(b) })
        return queue.indexOfFirst { it.attachmentGuid == attachmentGuid }.takeIf { it >= 0 }
    }

    private fun processQueue() {
        applicationScope.launch(ioDispatcher) {
            Timber.tag("ChatScroll").d("[DownloadQueue] processQueue started, pendingSize=${pendingQueue.size}")
            while (pendingQueue.isNotEmpty()) {
                val request = pendingQueue.poll() ?: break

                // Skip if already downloaded or being downloaded
                if (activeDownloads.containsKey(request.attachmentGuid)) {
                    Timber.tag("ChatScroll").d("[DownloadQueue] Skip ${request.attachmentGuid} - already downloading")
                    continue
                }

                // Check if already downloaded
                val attachment = attachmentDao.getAttachmentByGuid(request.attachmentGuid)
                if (attachment?.localPath != null) {
                    Timber.tag("ChatScroll").d("[DownloadQueue] Skip ${request.attachmentGuid} - already downloaded locally")
                    queuedGuids.remove(request.attachmentGuid)
                    updateQueueSize()
                    continue
                }

                // Acquire semaphore (blocks if at max concurrent)
                Timber.tag("ChatScroll").d("[DownloadQueue] Waiting for semaphore, activeDownloads=${activeDownloads.size}")
                semaphore.withPermit {
                    Timber.tag("ChatScroll").d("[DownloadQueue] >>> START download: ${request.attachmentGuid}, priority=${request.priority}")
                    activeDownloads[request.attachmentGuid] = request
                    updateQueueSize()

                    try {
                        downloadAttachment(request)
                    } finally {
                        activeDownloads.remove(request.attachmentGuid)
                        queuedGuids.remove(request.attachmentGuid)
                        updateQueueSize()
                        Timber.tag("ChatScroll").d("[DownloadQueue] <<< END download: ${request.attachmentGuid}")
                    }
                }
            }
            Timber.tag("ChatScroll").d("[DownloadQueue] processQueue finished")
        }
    }

    private suspend fun downloadAttachment(request: DownloadRequest) {
        Timber.d("Starting download: ${request.attachmentGuid}")

        // Get current attachment to check retry count and size
        val attachment = attachmentDao.getAttachmentByGuid(request.attachmentGuid)
        val currentRetryCount = attachment?.retryCount ?: 0

        // Check memory availability before downloading
        val runtime = Runtime.getRuntime()
        val freeMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        if (freeMemory < MIN_FREE_MEMORY_BYTES) {
            Timber.w("Low memory (${freeMemory / 1024 / 1024}MB free), skipping download: ${request.attachmentGuid}")
            // Don't mark as failed - will retry when memory is available
            return
        }

        // Skip auto-download of large files (user can manually download)
        val fileSize = attachment?.totalBytes ?: 0L
        if (fileSize > MAX_AUTO_DOWNLOAD_SIZE && request.priority != Priority.IMMEDIATE) {
            Timber.d("Skipping large file (${fileSize / 1024 / 1024}MB) for auto-download: ${request.attachmentGuid}")
            // Mark as requiring manual download
            attachmentDao.markTransferFailedWithError(
                guid = request.attachmentGuid,
                errorType = "large_file",
                errorMessage = "File too large for auto-download. Tap to download."
            )
            updateProgress(
                attachmentGuid = request.attachmentGuid,
                bytesDownloaded = 0,
                totalBytes = fileSize,
                isComplete = true,
                error = "File too large for auto-download. Tap to download.",
                errorType = "large_file",
                isRetryable = true
            )
            return
        }

        // Check if max retries exceeded
        if (currentRetryCount >= MAX_RETRY_COUNT) {
            Timber.w("Max retries exceeded for ${request.attachmentGuid}, skipping")
            val errorState = AttachmentErrorState.MaxRetriesExceeded
            attachmentDao.markTransferFailedWithError(
                guid = request.attachmentGuid,
                errorType = errorState.type,
                errorMessage = errorState.userMessage
            )
            updateProgress(
                attachmentGuid = request.attachmentGuid,
                bytesDownloaded = 0,
                totalBytes = 0,
                isComplete = true,
                error = errorState.userMessage,
                errorType = errorState.type,
                isRetryable = false
            )
            return
        }

        // Apply exponential backoff if this is a retry
        if (currentRetryCount > 0) {
            val backoffMs = calculateBackoff(currentRetryCount)
            Timber.d("Retry #$currentRetryCount for ${request.attachmentGuid}, waiting ${backoffMs}ms")
            delay(backoffMs)
        }

        updateProgress(request.attachmentGuid, 0, 0, false)

        // Update transfer state to DOWNLOADING
        attachmentDao.updateTransferState(request.attachmentGuid, "DOWNLOADING")

        try {
            val result = attachmentRepository.downloadAttachment(
                attachmentGuid = request.attachmentGuid,
                onProgress = { progress ->
                    // Convert 0-1 progress to bytes (estimate)
                    val estimated = (progress * 100).toLong()
                    updateProgress(request.attachmentGuid, estimated, 100, false)
                }
            )

            result.fold(
                onSuccess = { file ->
                    updateProgress(request.attachmentGuid, file.length(), file.length(), true)
                    // Emit completion event for UI refresh
                    _downloadCompletions.tryEmit(request.attachmentGuid)
                    Timber.d("Download complete: ${request.attachmentGuid}")
                },
                onFailure = { error ->
                    handleDownloadError(request.attachmentGuid, error)
                }
            )
        } catch (e: Exception) {
            handleDownloadError(request.attachmentGuid, e)
        }
    }

    /**
     * Handle download errors by categorizing them and updating the database.
     */
    private suspend fun handleDownloadError(attachmentGuid: String, error: Throwable) {
        val errorState = AttachmentErrorState.fromException(error)

        Timber.e(error, "Download failed: $attachmentGuid - ${errorState.type}: ${errorState.userMessage}")

        // Update database with error details
        attachmentDao.markTransferFailedWithError(
            guid = attachmentGuid,
            errorType = errorState.type,
            errorMessage = errorState.userMessage
        )

        // Update progress state for UI
        updateProgress(
            attachmentGuid = attachmentGuid,
            bytesDownloaded = 0,
            totalBytes = 0,
            isComplete = true,
            error = errorState.userMessage,
            errorType = errorState.type,
            isRetryable = errorState.isRetryable
        )
    }

    /**
     * Calculate exponential backoff delay for retries.
     * Formula: min(INITIAL_BACKOFF * 2^retryCount, MAX_BACKOFF)
     */
    private fun calculateBackoff(retryCount: Int): Long {
        val exponentialDelay = INITIAL_BACKOFF_MS * (1L shl retryCount.coerceAtMost(5))
        return exponentialDelay.coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun updateProgress(
        attachmentGuid: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        isComplete: Boolean,
        error: String? = null,
        errorType: String? = null,
        isRetryable: Boolean = true
    ) {
        val progress = DownloadProgress(
            attachmentGuid = attachmentGuid,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            isComplete = isComplete,
            error = error,
            errorType = errorType,
            isRetryable = isRetryable
        )

        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            if (isComplete && error == null) {
                remove(attachmentGuid) // Clear completed successful downloads
            } else {
                put(attachmentGuid, progress)
            }
        }
    }

    private fun updateQueueSize() {
        _queueSize.value = pendingQueue.size + activeDownloads.size
    }

    private fun reprioritizeForChat(chatGuid: String) {
        // Remove items for this chat and re-add with higher priority
        val toReprioritize = mutableListOf<DownloadRequest>()
        pendingQueue.removeIf { request ->
            if (request.chatGuid == chatGuid && request.priority > Priority.ACTIVE_CHAT) {
                toReprioritize.add(request)
                true
            } else {
                false
            }
        }

        toReprioritize.forEach { request ->
            pendingQueue.offer(request.copy(priority = Priority.ACTIVE_CHAT))
        }

        if (toReprioritize.isNotEmpty()) {
            Timber.d("Reprioritized ${toReprioritize.size} downloads for active chat $chatGuid")
        }
    }

    /**
     * Retry a failed download.
     * Clears the error state in the database and re-enqueues with immediate priority.
     *
     * @param attachmentGuid GUID of the failed attachment
     * @param chatGuid GUID of the chat (for prioritization)
     * @param resetRetryCount If true, resets the retry counter (for manual user retries)
     */
    suspend fun retryDownload(
        attachmentGuid: String,
        chatGuid: String,
        resetRetryCount: Boolean = true
    ) {
        Timber.d("Retrying download: $attachmentGuid, resetRetryCount=$resetRetryCount")

        // Clear error state in database
        attachmentDao.clearErrorForRetry(attachmentGuid)

        // Reset retry count if this is a manual user retry
        if (resetRetryCount) {
            // Get attachment and update with reset retry count
            val attachment = attachmentDao.getAttachmentByGuid(attachmentGuid)
            if (attachment != null) {
                attachmentDao.updateAttachment(attachment.copy(retryCount = 0))
            }
        }

        // Clear from progress state
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            remove(attachmentGuid)
        }

        // Remove from queued guids if present (allows re-enqueue)
        queuedGuids.remove(attachmentGuid)

        // Enqueue with immediate priority
        enqueue(attachmentGuid, chatGuid, Priority.IMMEDIATE)
    }

    /**
     * Retry all failed downloads for a chat.
     */
    suspend fun retryAllFailedForChat(chatGuid: String) {
        val failedAttachments = attachmentDao.getFailedAttachmentsForChat(chatGuid)
        Timber.d("Retrying ${failedAttachments.size} failed downloads for chat $chatGuid")

        failedAttachments.forEach { attachment ->
            retryDownload(attachment.guid, chatGuid, resetRetryCount = true)
        }
    }
}
