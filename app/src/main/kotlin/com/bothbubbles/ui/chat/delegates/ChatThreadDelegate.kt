package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.ui.chat.MessageTransformationUtils.normalizeAddress
import com.bothbubbles.ui.chat.MessageTransformationUtils.toUiModel
import com.bothbubbles.ui.components.message.ThreadChain
import com.bothbubbles.ui.util.toStable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate responsible for thread overlay functionality.
 * Manages loading and displaying message threads (origin + replies).
 */
class ChatThreadDelegate @Inject constructor(
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val attachmentRepository: AttachmentRepository
) {

    private lateinit var scope: CoroutineScope
    private lateinit var mergedChatGuids: List<String>

    // Thread overlay state
    private val _threadOverlayState = MutableStateFlow<ThreadChain?>(null)
    val threadOverlayState: StateFlow<ThreadChain?> = _threadOverlayState.asStateFlow()

    // Scroll-to-message event
    private val _scrollToGuid = MutableSharedFlow<String>()
    val scrollToGuid: SharedFlow<String> = _scrollToGuid.asSharedFlow()

    /**
     * Initialize the delegate.
     */
    fun initialize(scope: CoroutineScope, mergedChatGuids: List<String>) {
        this.scope = scope
        this.mergedChatGuids = mergedChatGuids
    }

    /**
     * Load a thread chain for display in the thread overlay.
     * Called when user taps a reply indicator.
     */
    fun loadThread(originGuid: String) {
        scope.launch {
            val threadMessages = messageRepository.getThreadMessages(originGuid)
            val origin = threadMessages.find { it.guid == originGuid }
            val replies = threadMessages.filter { it.threadOriginatorGuid == originGuid }

            // Get participants for sender name and avatar resolution
            val participants = chatRepository.getParticipantsForChats(mergedChatGuids)
            val handleIdToName = participants.associate { it.id to it.displayName }
            val addressToName = participants.associate { normalizeAddress(it.address) to it.displayName }
            val addressToAvatarPath = participants.associate { normalizeAddress(it.address) to it.cachedAvatarPath }

            // Batch load attachments for all thread messages
            val allAttachments = attachmentRepository.getAttachmentsForMessages(
                threadMessages.map { it.guid }
            ).groupBy { it.messageGuid }

            // Filter out placed stickers from thread overlay - they're visual overlays, not actual replies
            val filteredReplies = replies.filter { msg ->
                val msgAttachments = allAttachments[msg.guid].orEmpty()
                val isPlacedSticker = msg.associatedMessageGuid != null &&
                    msgAttachments.any { it.mimeType?.contains("sticker") == true }
                !isPlacedSticker
            }

            _threadOverlayState.value = ThreadChain(
                originMessage = origin?.toUiModel(
                    attachments = allAttachments[origin.guid].orEmpty(),
                    handleIdToName = handleIdToName,
                    addressToName = addressToName,
                    addressToAvatarPath = addressToAvatarPath
                ),
                replies = filteredReplies.map { msg ->
                    msg.toUiModel(
                        attachments = allAttachments[msg.guid].orEmpty(),
                        handleIdToName = handleIdToName,
                        addressToName = addressToName,
                        addressToAvatarPath = addressToAvatarPath
                    )
                }.toStable()
            )
        }
    }

    /**
     * Dismiss the thread overlay.
     */
    fun dismissThreadOverlay() {
        _threadOverlayState.value = null
    }

    /**
     * Scroll to a specific message in the main chat.
     * Called when user taps a message in the thread overlay.
     */
    fun scrollToMessage(guid: String) {
        scope.launch {
            dismissThreadOverlay()
            _scrollToGuid.emit(guid)
        }
    }
}
