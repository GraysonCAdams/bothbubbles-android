package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.media.AttachmentDownloadQueue
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Delegate that handles attachment download operations for ChatViewModel.
 *
 * Responsibilities:
 * - Download progress tracking
 * - Queueing downloads with priority
 * - Auto-download mode handling
 * - Preloading attachments for visible messages
 *
 * Uses AssistedInject to receive runtime parameters at construction time,
 * eliminating the need for a separate initialize() call.
 */
class ChatAttachmentDelegate @AssistedInject constructor(
    private val attachmentDownloadQueue: AttachmentDownloadQueue,
    private val settingsDataStore: SettingsDataStore,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope,
    @Assisted private val mergedChatGuids: List<String>
) {

    @AssistedFactory
    interface Factory {
        fun create(
            chatGuid: String,
            scope: CoroutineScope,
            mergedChatGuids: List<String>
        ): ChatAttachmentDelegate
    }

    companion object {
        private const val TAG = "ChatAttachmentDelegate"
    }

    private val isMergedChat: Boolean = mergedChatGuids.size > 1

    // Download progress tracking
    // Maps attachment GUID to download progress (0.0 to 1.0, or null if not downloading)
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    // Refresh trigger - incremented when downloads complete
    private val _refreshTrigger = MutableStateFlow(0)
    val refreshTrigger: StateFlow<Int> = _refreshTrigger.asStateFlow()

    // Whether to use auto-download mode (true) or manual download mode (false)
    private val _autoDownloadEnabled = MutableStateFlow(true)
    val autoDownloadEnabled: StateFlow<Boolean> = _autoDownloadEnabled.asStateFlow()

    init {
        // Set this chat as active for download queue prioritization
        attachmentDownloadQueue.setActiveChat(chatGuid)

        // Observe download completions
        observeDownloadCompletions()

        // Observe auto-download setting
        observeAutoDownloadSetting()
    }

    /**
     * Observe the auto-download setting and trigger downloads when chat is opened.
     */
    private fun observeAutoDownloadSetting() {
        scope.launch {
            settingsDataStore.autoDownloadAttachments.collect { autoDownload ->
                _autoDownloadEnabled.value = autoDownload
                if (autoDownload) {
                    // Auto-download pending attachments for this chat
                    downloadPendingAttachments()
                }
            }
        }
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
