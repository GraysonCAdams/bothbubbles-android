package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.services.contacts.DisplayNameResolver
import com.bothbubbles.ui.chat.MessageTransformationUtils.normalizeAddress
import com.bothbubbles.ui.chat.MessageTransformationUtils.toUiModel
import com.bothbubbles.ui.chat.state.ThreadState
import com.bothbubbles.ui.components.message.ReactionUiModel
import com.bothbubbles.ui.components.message.Tapback
import com.bothbubbles.ui.components.message.ThreadChain
import com.bothbubbles.ui.util.toStable
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Delegate responsible for thread overlay functionality.
 * Manages loading and displaying message threads (origin + replies).
 *
 * Uses AssistedInject to receive runtime parameters at construction time,
 * eliminating the need for a separate initialize() call.
 */
class ChatThreadDelegate @AssistedInject constructor(
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val attachmentRepository: AttachmentRepository,
    private val displayNameResolver: DisplayNameResolver,
    @Assisted private val scope: CoroutineScope,
    @Assisted private val mergedChatGuids: List<String>
) {

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope, mergedChatGuids: List<String>): ChatThreadDelegate
    }

    // ============================================================================
    // CONSOLIDATED THREAD STATE
    // Single StateFlow containing all thread-related state for reduced recompositions.
    // ============================================================================
    private val _state = MutableStateFlow(ThreadState())
    val state: StateFlow<ThreadState> = _state.asStateFlow()

    // Scroll-to-message event (SharedFlow for one-time events, not state)
    private val _scrollToGuid = MutableSharedFlow<String>()
    val scrollToGuid: SharedFlow<String> = _scrollToGuid.asSharedFlow()

    /**
     * Load a thread chain for display in the thread overlay.
     * Called when user taps a reply indicator.
     *
     * @param originGuid The GUID of the origin message for the thread.
     * @param excludeOrigin If true, only show replies without the origin message.
     *                      Used when opening thread from Reels where the origin is already visible.
     */
    fun loadThread(originGuid: String, excludeOrigin: Boolean = false) {
        scope.launch {
            val threadMessages = messageRepository.getThreadMessages(originGuid)
            val origin = threadMessages.find { it.guid == originGuid }
            val replies = threadMessages.filter { it.threadOriginatorGuid == originGuid }

            // Get participants and build lookup maps using centralized resolver
            val participants = chatRepository.getParticipantsForChats(mergedChatGuids)
            val lookupMaps = displayNameResolver.buildLookupMaps(participants)

            // Batch load attachments for all thread messages
            val messageGuids = threadMessages.map { it.guid }
            val allAttachments = attachmentRepository.getAttachmentsForMessages(messageGuids)
                .groupBy { it.messageGuid }

            // Batch load reactions for all thread messages
            val allReactions = messageRepository.getReactionsForMessages(messageGuids)
            val reactionsByMessage = allReactions.groupBy { reaction ->
                reaction.associatedMessageGuid?.let { guid ->
                    if (guid.contains("/")) guid.substringAfter("/") else guid
                }
            }

            // Filter out reactions and placed stickers from thread overlay
            // Reactions have thread_originator_guid set by the server but should only appear as badges
            val filteredReplies = replies.filter { msg ->
                // Skip reactions - they appear as badges, not as thread replies
                if (msg.isReaction) return@filter false

                // Skip placed stickers - they're visual overlays, not actual replies
                val msgAttachments = allAttachments[msg.guid].orEmpty()
                val isPlacedSticker = msg.associatedMessageGuid != null &&
                    msgAttachments.any { it.mimeType?.contains("sticker") == true }
                !isPlacedSticker
            }

            // Get origin reactions for the header (used when excludeOrigin is true, e.g., Reels context)
            val originReactions = if (excludeOrigin && origin != null) {
                reactionsByMessage[origin.guid].orEmpty().mapNotNull { reaction ->
                    val tapback = Tapback.fromApiName(reaction.associatedMessageType ?: "") ?: return@mapNotNull null
                    val isFromMe = reaction.isFromMe
                    val senderName = when {
                        isFromMe -> "You"
                        else -> displayNameResolver.resolveSenderName(
                            reaction.senderAddress,
                            reaction.handleId,
                            lookupMaps,
                            DisplayNameResolver.DisplayMode.FULL
                        ) ?: "Unknown"
                    }
                    ReactionUiModel(
                        tapback = tapback,
                        isFromMe = isFromMe,
                        senderName = senderName
                    )
                }.toStable()
            } else {
                emptyList<ReactionUiModel>().toStable()
            }

            val threadChain = ThreadChain(
                originGuid = originGuid,
                originMessage = if (excludeOrigin) null else origin?.toUiModel(
                    reactions = reactionsByMessage[origin.guid].orEmpty(),
                    attachments = allAttachments[origin.guid].orEmpty(),
                    lookupMaps = lookupMaps
                ),
                replies = filteredReplies.map { msg ->
                    msg.toUiModel(
                        reactions = reactionsByMessage[msg.guid].orEmpty(),
                        attachments = allAttachments[msg.guid].orEmpty(),
                        lookupMaps = lookupMaps
                    )
                }.toStable(),
                reactions = originReactions
            )
            _state.update { it.copy(threadOverlay = threadChain) }
        }
    }

    /**
     * Dismiss the thread overlay.
     */
    fun dismissThreadOverlay() {
        _state.update { it.copy(threadOverlay = null) }
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

    /**
     * Emit a scroll event without dismissing overlays.
     * Used for search highlighting and other non-thread-overlay scrolls.
     */
    fun emitScrollEvent(guid: String) {
        scope.launch {
            _scrollToGuid.emit(guid)
        }
    }
}
