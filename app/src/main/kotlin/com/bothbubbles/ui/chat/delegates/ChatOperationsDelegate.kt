package com.bothbubbles.ui.chat.delegates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.util.Log
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.contacts.DiscordContactService
import com.bothbubbles.services.messaging.MessageSender
import com.bothbubbles.services.spam.SpamReportingService
import com.bothbubbles.services.spam.SpamRepository
import com.bothbubbles.ui.chat.state.OperationsState
import com.bothbubbles.ui.components.message.ReactionUiModel
import com.bothbubbles.ui.components.message.Tapback
import com.bothbubbles.ui.util.toStable
import com.bothbubbles.util.error.ValidationError
import com.bothbubbles.util.error.handle
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
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
    private val chatRepository: ChatRepository,
    private val spamRepository: SpamRepository,
    private val spamReportingService: SpamReportingService,
    private val messageSender: MessageSender,
    private val discordContactService: DiscordContactService,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {

    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatOperationsDelegate
    }

    companion object {
        private const val TAG = "ChatOperationsDelegate"
    }

    private var messageListDelegate: ChatMessageListDelegate? = null

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
     * Observe the chat entity and update state when archive/star/spam status changes.
     */
    private fun observeChatState() {
        scope.launch {
            chatRepository.observeChat(chatGuid)
                .filterNotNull()
                .collect { chat ->
                    _state.update { it.copy(
                        isArchived = chat.isArchived,
                        isStarred = chat.isStarred,
                        isSpam = chat.isSpam,
                        isReportedToCarrier = chat.spamReportedToCarrier
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
     */
    fun blockContact(context: Context, participantPhone: String?): Boolean {
        val phone = participantPhone ?: return false

        return try {
            val values = android.content.ContentValues().apply {
                put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, phone)
            }
            context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                values
            )
            true
        } catch (e: Exception) {
            _state.update { it.copy(
                operationError = ValidationError.InvalidInput("contact", "Failed to block: ${e.message ?: "unknown error"}")
            )}
            false
        }
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

    /**
     * Set the message list delegate for reaction updates.
     */
    fun setMessageListDelegate(messageList: ChatMessageListDelegate) {
        this.messageListDelegate = messageList
    }

    // ============================================================================
    // REACTIONS
    // ============================================================================

    /**
     * Toggle a reaction on a message.
     * Only works on server-origin messages (IMESSAGE or SERVER_SMS).
     * Uses native tapback API via BlueBubbles server.
     */
    fun toggleReaction(messageGuid: String, tapback: Tapback) {
        val messageList = messageListDelegate ?: return
        val message = messageList.messagesState.value.find { it.guid == messageGuid } ?: return

        // Guard: Only allow on server-origin messages (IMESSAGE or SERVER_SMS)
        // Local SMS/MMS cannot have tapbacks
        if (!message.isServerOrigin) {
            return
        }

        val isRemoving = tapback in message.myReactions
        Log.d(TAG, "toggleReaction: messageGuid=$messageGuid, tapback=${tapback.apiName}, isRemoving=$isRemoving")

        scope.launch {
            // OPTIMISTIC UPDATE: Immediately show the reaction in UI
            val optimisticUpdateApplied = messageList.updateMessageLocally(messageGuid) { currentMessage ->
                val newMyReactions = if (isRemoving) {
                    currentMessage.myReactions - tapback
                } else {
                    currentMessage.myReactions + tapback
                }

                val newReactions = if (isRemoving) {
                    // Remove my reaction from the list
                    currentMessage.reactions.filter { !(it.tapback == tapback && it.isFromMe) }.toStable()
                } else {
                    // Add my reaction to the list
                    (currentMessage.reactions + ReactionUiModel(
                        tapback = tapback,
                        isFromMe = true,
                        senderName = null // Will be filled in on refresh from DB
                    )).toStable()
                }

                currentMessage.copy(
                    myReactions = newMyReactions,
                    reactions = newReactions
                )
            }

            if (optimisticUpdateApplied) {
                Log.d(TAG, "toggleReaction: optimistic update applied for $messageGuid")
            }

            // Call API in background (fire and forget with rollback on failure)
            // Pass selectedMessageText - required by BlueBubbles server for reaction matching
            val messageText = message.text ?: ""
            val result = if (isRemoving) {
                messageSender.removeReaction(
                    chatGuid = chatGuid,
                    messageGuid = messageGuid,
                    reaction = tapback.apiName,
                    selectedMessageText = messageText
                )
            } else {
                messageSender.sendReaction(
                    chatGuid = chatGuid,
                    messageGuid = messageGuid,
                    reaction = tapback.apiName,
                    selectedMessageText = messageText
                )
            }

            result.onSuccess {
                Log.d(TAG, "toggleReaction: API success for $messageGuid")
                // Refresh from database to get the canonical server state
                // This ensures our optimistic update is replaced with the real data
                messageList.updateMessage(messageGuid)
            }.onFailure { error ->
                Log.e(TAG, "toggleReaction: API failed for $messageGuid, rolling back optimistic update", error)
                // ROLLBACK: Revert to database state (which doesn't have the reaction)
                messageList.updateMessage(messageGuid)
            }
        }
    }
}
