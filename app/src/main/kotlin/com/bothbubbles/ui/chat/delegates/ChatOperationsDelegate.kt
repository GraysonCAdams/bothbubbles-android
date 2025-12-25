package com.bothbubbles.ui.chat.delegates

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import timber.log.Timber
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.UnifiedChatRepository
import com.bothbubbles.services.contacts.ContactBlocker
import com.bothbubbles.services.contacts.DiscordContactService
import com.bothbubbles.services.contacts.sync.GroupContactSyncManager
import com.bothbubbles.services.messaging.MessageSender
import com.bothbubbles.services.spam.SpamReportingService
import com.bothbubbles.services.spam.SpamRepository
import com.bothbubbles.ui.chat.state.OperationsState
import com.bothbubbles.ui.components.message.Tapback
import com.bothbubbles.util.error.ValidationError
import com.bothbubbles.util.error.handle
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Delegate responsible for chat menu actions and operations.
 * Handles archive, star, delete, spam reporting, contact-related actions, and reactions.
 *
 * Uses AssistedInject to receive runtime parameters at construction time,
 * eliminating the need for a separate initialize() call.
 *
 * Phase 2: Uses MessageSender interface instead of MessageSendingService
 * for improved testability.
 */
class ChatOperationsDelegate @AssistedInject constructor(
    private val application: Application,
    private val chatRepository: ChatRepository,
    private val unifiedChatRepository: UnifiedChatRepository,
    private val messageRepository: MessageRepository,
    private val spamRepository: SpamRepository,
    private val spamReportingService: SpamReportingService,
    private val messageSender: MessageSender,
    private val discordContactService: DiscordContactService,
    private val contactBlocker: ContactBlocker,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {

    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatOperationsDelegate
    }


    // Phase 4: messageListDelegate reference removed - ViewModel coordinates reaction toggling

    // ============================================================================
    // CONSOLIDATED OPERATIONS STATE
    // Single StateFlow containing all operations-related state for reduced recompositions.
    // ============================================================================
    private val _state = MutableStateFlow(OperationsState())
    val state: StateFlow<OperationsState> = _state.asStateFlow()

    init {
        observeChatState()
    }

    /**
     * Observe the unified chat entity and update state when archive/star/spam status changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeChatState() {
        scope.launch {
            chatRepository.observeChat(chatGuid)
                .filterNotNull()
                .flatMapLatest { chat ->
                    val unifiedId = chat.unifiedChatId
                    if (unifiedId != null) {
                        unifiedChatRepository.observeChat(unifiedId)
                    } else {
                        flowOf(null)
                    }
                }
                .filterNotNull()
                .collect { unifiedChat ->
                    _state.update { it.copy(
                        isArchived = unifiedChat.isArchived,
                        isStarred = unifiedChat.isStarred,
                        isSpam = unifiedChat.isSpam,
                        isReportedToCarrier = unifiedChat.spamReportedToCarrier
                    )}
                }
        }
    }

    /**
     * Update state from external sources.
     * @deprecated Use observeChatState() for automatic updates instead.
     */
    fun updateState(
        isArchived: Boolean,
        isStarred: Boolean,
        isSpam: Boolean,
        isReportedToCarrier: Boolean
    ) {
        _state.update { it.copy(
            isArchived = isArchived,
            isStarred = isStarred,
            isSpam = isSpam,
            isReportedToCarrier = isReportedToCarrier
        )}
    }

    /**
     * Archive the chat.
     */
    fun archiveChat() {
        scope.launch {
            chatRepository.setArchived(chatGuid, true).handle(
                onSuccess = {
                    _state.update { it.copy(isArchived = true) }
                },
                onError = { appError ->
                    _state.update { it.copy(operationError = appError) }
                }
            )
        }
    }

    /**
     * Unarchive the chat.
     */
    fun unarchiveChat() {
        scope.launch {
            chatRepository.setArchived(chatGuid, false).handle(
                onSuccess = {
                    _state.update { it.copy(isArchived = false) }
                },
                onError = { appError ->
                    _state.update { it.copy(operationError = appError) }
                }
            )
        }
    }

    /**
     * Toggle starred status.
     */
    fun toggleStarred() {
        val currentStarred = _state.value.isStarred
        scope.launch {
            chatRepository.setStarred(chatGuid, !currentStarred).handle(
                onSuccess = {
                    _state.update { it.copy(isStarred = !currentStarred) }
                },
                onError = { appError ->
                    _state.update { it.copy(operationError = appError) }
                }
            )
        }
    }

    /**
     * Delete the chat.
     */
    fun deleteChat() {
        scope.launch {
            chatRepository.deleteChat(chatGuid).handle(
                onSuccess = {
                    _state.update { it.copy(chatDeleted = true) }
                    // Sync group contacts (in case deleted chat was a group)
                    GroupContactSyncManager.triggerSync(application)
                },
                onError = { appError ->
                    _state.update { it.copy(operationError = appError) }
                }
            )
        }
    }

    /**
     * Toggle subject field visibility.
     */
    fun toggleSubjectField() {
        _state.update { it.copy(showSubjectField = !it.showSubjectField) }
    }

    /**
     * Mark chat as safe (not spam).
     */
    fun markAsSafe() {
        scope.launch {
            spamRepository.markAsSafe(chatGuid)
        }
    }

    /**
     * Report chat as spam.
     */
    fun reportAsSpam() {
        scope.launch {
            spamRepository.reportAsSpam(chatGuid)
        }
    }

    /**
     * Report spam to carrier via 7726.
     */
    fun reportToCarrier(): Boolean {
        scope.launch {
            val result = spamReportingService.reportToCarrier(chatGuid)
            if (result is SpamReportingService.ReportResult.Success) {
                _state.update { it.copy(isReportedToCarrier = true) }
            }
        }
        return true
    }

    /**
     * Check if chat has been reported to carrier.
     */
    fun checkReportedToCarrier() {
        scope.launch {
            val isReported = spamReportingService.isReportedToCarrier(chatGuid)
            _state.update { it.copy(isReportedToCarrier = isReported) }
        }
    }

    /**
     * Create intent to add contact.
     */
    fun getAddToContactsIntent(participantPhone: String?, inferredName: String?): Intent {
        val phone = participantPhone ?: ""
        return Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, phone)
            if (inferredName != null) {
                putExtra(ContactsContract.Intents.Insert.NAME, inferredName)
            }
        }
    }

    /**
     * Create intent for Google Meet.
     */
    fun getGoogleMeetIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://meet.google.com/new"))
    }

    /**
     * Create intent for WhatsApp call.
     */
    fun getWhatsAppCallIntent(participantPhone: String?): Intent? {
        val phone = participantPhone?.replace(Regex("[^0-9+]"), "") ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone"))
    }

    /**
     * Create intent for help page.
     */
    fun getHelpIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BlueBubblesApp/bluebubbles-app/issues"))
    }

    /**
     * Block a contact (SMS only).
     * Uses ContactBlocker interface for testability.
     */
    fun blockContact(participantPhone: String?): Boolean {
        val phone = participantPhone ?: return false

        val success = contactBlocker.blockNumber(phone)
        if (!success) {
            _state.update { it.copy(
                operationError = ValidationError.InvalidInput("contact", "Failed to block number")
            )}
        }
        return success
    }

    /**
     * Check if WhatsApp is installed.
     */
    fun isWhatsAppAvailable(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.whatsapp", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ============================================================================
    // DISCORD VIDEO CALL SUPPORT
    // ============================================================================

    /**
     * Check if Discord is installed.
     */
    fun isDiscordInstalled(): Boolean {
        return discordContactService.isDiscordInstalled()
    }

    /**
     * Get the Discord channel ID for a participant.
     */
    fun getDiscordChannelId(participantPhone: String?): String? {
        if (participantPhone.isNullOrBlank()) return null
        return discordContactService.getDiscordChannelId(participantPhone)
    }

    /**
     * Save Discord channel ID for a participant.
     */
    fun saveDiscordChannelId(participantPhone: String?, channelId: String): Boolean {
        if (participantPhone.isNullOrBlank()) return false
        return discordContactService.setDiscordChannelId(participantPhone, channelId)
    }

    /**
     * Clear Discord channel ID for a participant.
     */
    fun clearDiscordChannelId(participantPhone: String?): Boolean {
        if (participantPhone.isNullOrBlank()) return false
        return discordContactService.clearDiscordChannelId(participantPhone)
    }

    /**
     * Validate a Discord channel ID.
     */
    fun isValidDiscordChannelId(channelId: String): Boolean {
        return discordContactService.isValidChannelId(channelId)
    }

    /**
     * Create intent to open Discord DM channel.
     */
    fun getDiscordCallIntent(channelId: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("discord://-/channels/@me/$channelId"))
    }

    /**
     * Clear operation error.
     */
    fun clearError() {
        _state.update { it.copy(operationError = null) }
    }

    // Phase 4: setMessageListDelegate() removed - ViewModel coordinates reaction toggling

    // ============================================================================
    // REACTIONS
    // Phase 4: toggleReaction now returns result - ViewModel handles optimistic update
    // ============================================================================

    /**
     * Data class for reaction toggle result.
     * Phase 4: Returned to ViewModel for coordination of optimistic updates.
     */
    data class ReactionToggleParams(
        val messageGuid: String,
        val tapback: Tapback,
        val isRemoving: Boolean,
        val messageText: String
    )

    /**
     * Send or remove a reaction on a message.
     * Phase 4: Does NOT handle optimistic updates - ViewModel coordinates that.
     * Returns result for ViewModel to decide on refresh/rollback.
     *
     * @param messageGuid The message GUID to react to
     * @param tapback The tapback to toggle
     * @param isRemoving True if removing the reaction, false if adding
     * @param messageText The message text (required by BlueBubbles server)
     * @return Result indicating success or failure
     */
    suspend fun sendReactionToggle(
        messageGuid: String,
        tapback: Tapback,
        isRemoving: Boolean,
        messageText: String
    ): Result<Unit> {
        Timber.d("sendReactionToggle: messageGuid=$messageGuid, tapback=${tapback.apiName}, isRemoving=$isRemoving")

        return if (isRemoving) {
            messageSender.removeReaction(
                chatGuid = chatGuid,
                messageGuid = messageGuid,
                reaction = tapback.apiName,
                selectedMessageText = messageText
            )
        } else {
            // sendReaction returns Result<MessageEntity>, map to Result<Unit>
            messageSender.sendReaction(
                chatGuid = chatGuid,
                messageGuid = messageGuid,
                reaction = tapback.apiName,
                selectedMessageText = messageText
            ).map { }
        }
    }

    // ============================================================================
    // MESSAGE PINNING & STARRING
    // ============================================================================

    /**
     * Toggle the pinned status of a message.
     * Pinned messages appear at the top of the conversation.
     *
     * @param messageGuid The GUID of the message to toggle
     * @return Result with the new pinned state (true = pinned, false = unpinned)
     */
    suspend fun toggleMessagePinned(messageGuid: String): Result<Boolean> {
        Timber.d("toggleMessagePinned: messageGuid=$messageGuid")
        return messageRepository.toggleMessagePinned(messageGuid)
    }

    /**
     * Toggle the starred status of a message.
     * Starred messages can be viewed in the conversation details.
     *
     * @param messageGuid The GUID of the message to toggle
     * @return Result with the new starred state (true = starred, false = unstarred)
     */
    suspend fun toggleMessageStarred(messageGuid: String): Result<Boolean> {
        Timber.d("toggleMessageStarred: messageGuid=$messageGuid")
        return messageRepository.toggleMessageStarred(messageGuid)
    }

    // ============================================================================
    // MESSAGE DELETION
    // ============================================================================

    /**
     * Delete multiple messages locally (soft delete).
     * Sets date_deleted on each message and records tombstones to prevent resurrection during sync.
     *
     * @param guids List of message GUIDs to delete
     */
    fun deleteMessages(guids: List<String>) {
        if (guids.isEmpty()) return
        scope.launch {
            try {
                messageRepository.softDeleteMessages(guids)
                Timber.d("Deleted ${guids.size} messages")
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete messages")
                _state.update { it.copy(
                    operationError = ValidationError.InvalidInput("delete", "Failed to delete messages")
                )}
            }
        }
    }
}
