package com.bothbubbles.ui.chat.delegates

import android.util.Log
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.services.media.AttachmentDownloadQueue
import com.bothbubbles.services.media.AttachmentPreloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate that handles attachment download operations for ChatViewModel.
 *
 * Responsibilities:
 * - Download progress tracking
 * - Queueing downloads with priority
 * - Auto-download mode handling
 * - Preloading attachments for visible messages
 *
 * This delegate follows the composition pattern where ChatViewModel
 * delegates specific concerns to focused helper classes.
 *
 * Usage in ChatViewModel:
 * ```kotlin
 * class ChatViewModel @Inject constructor(
 *     private val attachmentDelegate: ChatAttachmentDelegate,
 *     ...
 * ) : ViewModel() {
 *     init {
 *         attachmentDelegate.initialize(chatGuid, viewModelScope, mergedChatGuids)
 *     }
 *
 *     fun downloadAttachment(guid: String) = attachmentDelegate.downloadAttachment(guid)
 * }
 * ```
 */
class ChatAttachmentDelegate @Inject constructor(
    private val attachmentRepository: AttachmentRepository,
    private val attachmentDownloadQueue: AttachmentDownloadQueue,
    private val attachmentPreloader: AttachmentPreloader
) {
    companion object {
        private const val TAG = "ChatAttachmentDelegate"
    }

    // State
    private lateinit var chatGuid: String
    private lateinit var scope: CoroutineScope
    private var mergedChatGuids: List<String> = emptyList()
    private var isMergedChat: Boolean = false

    // Download progress tracking
    // Maps attachment GUID to download progress (0.0 to 1.0, or null if not downloading)
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    // Refresh trigger - incremented when downloads complete
    private val _refreshTrigger = MutableStateFlow(0)
    val refreshTrigger: StateFlow<Int> = _refreshTrigger.asStateFlow()

    /**
     * Initialize the delegate with the chat context.
     * Must be called before any download operations.
     */
    fun initialize(
        chatGuid: String,
        scope: CoroutineScope,
        mergedChatGuids: List<String> = listOf(chatGuid)
    ) {
        this.chatGuid = chatGuid
        this.scope = scope
        this.mergedChatGuids = mergedChatGuids
        this.isMergedChat = mergedChatGuids.size > 1

        // Set this chat as active for download queue prioritization
        attachmentDownloadQueue.setActiveChat(chatGuid)

        // Observe download completions
        observeDownloadCompletions()
    }

    /**
     * Observe download completions from the queue and update state.
     */
    private fun observeDownloadCompletions() {
        scope.launch {
            attachmentDownloadQueue.downloadCompletions.collect { attachmentGuid ->
                // Remove from progress map when complete
                _downloadProgress.update { it - attachmentGuid }
                // Trigger UI refresh
                _refreshTrigger.value++
            }
        }
    }

    /**
     * Download all pending attachments for this chat (or merged chats).
     * Uses ACTIVE_CHAT priority for downloads.
     */
    fun downloadPendingAttachments() {
        scope.launch {
            if (isMergedChat) {
                mergedChatGuids.forEach { guid ->
                    attachmentDownloadQueue.enqueueAllForChat(
                        guid,
                        AttachmentDownloadQueue.Priority.ACTIVE_CHAT
                    )
                }
            } else {
                attachmentDownloadQueue.enqueueAllForChat(
                    chatGuid,
                    AttachmentDownloadQueue.Priority.ACTIVE_CHAT
                )
            }
        }
    }

    /**
     * Manually download a specific attachment.
     * Uses IMMEDIATE priority to jump ahead of background downloads.
     */
    fun downloadAttachment(attachmentGuid: String) {
        // Mark as downloading
        _downloadProgress.update { it + (attachmentGuid to 0f) }

        // Enqueue with IMMEDIATE priority
        attachmentDownloadQueue.enqueue(
            attachmentGuid,
            chatGuid,
            AttachmentDownloadQueue.Priority.IMMEDIATE
        )
    }

    /**
     * Check if an attachment is currently downloading.
     */
    fun isDownloading(attachmentGuid: String): Boolean {
        return attachmentGuid in _downloadProgress.value
    }

    /**
     * Get download progress for an attachment (0.0 to 1.0).
     */
    fun getDownloadProgress(attachmentGuid: String): Float {
        return _downloadProgress.value[attachmentGuid] ?: 0f
    }

    /**
     * Update download progress for an attachment.
     * Called by the download queue progress observer.
     */
    fun updateProgress(attachmentGuid: String, progress: Float) {
        _downloadProgress.update { it + (attachmentGuid to progress) }
    }

    /**
     * Get the download completions SharedFlow for external observers.
     */
    val downloadCompletions: SharedFlow<String>
        get() = attachmentDownloadQueue.downloadCompletions

    /**
     * Clear active chat when leaving.
     */
    fun onChatLeave() {
        attachmentDownloadQueue.setActiveChat(null)
    }
}
