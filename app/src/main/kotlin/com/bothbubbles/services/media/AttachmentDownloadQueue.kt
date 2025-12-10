package com.bothbubbles.services.media

import android.util.Log
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.repository.AttachmentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
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
    private val attachmentDao: AttachmentDao
) {
    companion object {
        private const val TAG = "AttachmentDownloadQueue"
        private const val MAX_CONCURRENT_DOWNLOADS = 2
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
        val error: String? = null
    ) {
        val progress: Float
            get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
            Log.d(TAG, "Attachment $attachmentGuid already queued, skipping")
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

        Log.d(TAG, "Enqueued download: $attachmentGuid with priority ${effectivePriority.name}")

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
        Log.d(TAG, "Enqueued ${pending.size} attachments for chat $chatGuid")
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
        Log.d(TAG, "Cancelled download: $attachmentGuid")
    }

    /**
     * Cancel all pending downloads for a chat.
     */
    fun cancelForChat(chatGuid: String) {
        pendingQueue.removeIf { it.chatGuid == chatGuid }
        updateQueueSize()
        Log.d(TAG, "Cancelled all downloads for chat: $chatGuid")
    }

    /**
     * Clear the entire queue.
     */
    fun clearQueue() {
        pendingQueue.clear()
        queuedGuids.clear()
        updateQueueSize()
        Log.d(TAG, "Queue cleared")
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
        scope.launch {
            Log.d("ChatScroll", "[DownloadQueue] processQueue started, pendingSize=${pendingQueue.size}")
            while (pendingQueue.isNotEmpty()) {
                val request = pendingQueue.poll() ?: break

                // Skip if already downloaded or being downloaded
                if (activeDownloads.containsKey(request.attachmentGuid)) {
                    Log.d("ChatScroll", "[DownloadQueue] Skip ${request.attachmentGuid} - already downloading")
                    continue
                }

                // Check if already downloaded
                val attachment = attachmentDao.getAttachmentByGuid(request.attachmentGuid)
                if (attachment?.localPath != null) {
                    Log.d("ChatScroll", "[DownloadQueue] Skip ${request.attachmentGuid} - already downloaded locally")
                    queuedGuids.remove(request.attachmentGuid)
                    updateQueueSize()
                    continue
                }

                // Acquire semaphore (blocks if at max concurrent)
                Log.d("ChatScroll", "[DownloadQueue] Waiting for semaphore, activeDownloads=${activeDownloads.size}")
                semaphore.withPermit {
                    Log.d("ChatScroll", "[DownloadQueue] >>> START download: ${request.attachmentGuid}, priority=${request.priority}")
                    activeDownloads[request.attachmentGuid] = request
                    updateQueueSize()

                    try {
                        downloadAttachment(request)
                    } finally {
                        activeDownloads.remove(request.attachmentGuid)
                        queuedGuids.remove(request.attachmentGuid)
                        updateQueueSize()
                        Log.d("ChatScroll", "[DownloadQueue] <<< END download: ${request.attachmentGuid}")
                    }
                }
            }
            Log.d("ChatScroll", "[DownloadQueue] processQueue finished")
        }
    }

    private suspend fun downloadAttachment(request: DownloadRequest) {
        Log.d(TAG, "Starting download: ${request.attachmentGuid}")

        updateProgress(request.attachmentGuid, 0, 0, false)

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
                    Log.d(TAG, "Download complete: ${request.attachmentGuid}")
                },
                onFailure = { error ->
                    updateProgress(
                        request.attachmentGuid, 0, 0, true,
                        error = error.message ?: "Download failed"
                    )
                    Log.e(TAG, "Download failed: ${request.attachmentGuid}", error)
                }
            )
        } catch (e: Exception) {
            updateProgress(
                request.attachmentGuid, 0, 0, true,
                error = e.message ?: "Download failed"
            )
            Log.e(TAG, "Download exception: ${request.attachmentGuid}", e)
        }
    }

    private fun updateProgress(
        attachmentGuid: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        isComplete: Boolean,
        error: String? = null
    ) {
        val progress = DownloadProgress(
            attachmentGuid = attachmentGuid,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            isComplete = isComplete,
            error = error
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
            Log.d(TAG, "Reprioritized ${toReprioritize.size} downloads for active chat $chatGuid")
        }
    }
}
