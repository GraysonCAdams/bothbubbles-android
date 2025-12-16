package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.ui.chat.state.ChatInfoState
import com.bothbubbles.ui.util.toStable
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate responsible for managing chat metadata and identity information.
 *
 * Handles:
 * - Loading chat metadata (title, group status, participants)
 * - Observing contact updates for display names and avatars
 * - Determining chat titles (resolving from participants for 1:1 chats)
 * - Managing the "save contact" banner for unsaved senders
 *
 * This delegate follows the composition pattern where ChatViewModel
 * delegates specific concerns to focused helper classes.
 *
 * Usage in ChatViewModel:
 * ```kotlin
 * class ChatViewModel @Inject constructor(
 *     val chatInfo: ChatInfoDelegate,
 *     ...
 * ) : ViewModel() {
 *     init {
 *         chatInfo.initialize(chatGuid, viewModelScope, mergedChatGuids)
 *     }
 *
 *     // Access state directly: chatInfo.state
 * }
 * ```
 */
class ChatInfoDelegate @Inject constructor(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val settingsDataStore: SettingsDataStore,
    private val androidContactsService: AndroidContactsService,
    private val smsPermissionHelper: SmsPermissionHelper
) {
    companion object {
        private const val TAG = "ChatInfoDelegate"
    }

    private lateinit var chatGuid: String
    private lateinit var scope: CoroutineScope
    private var mergedChatGuids: List<String> = emptyList()

    private val _state = MutableStateFlow(ChatInfoState())
    val state: StateFlow<ChatInfoState> = _state.asStateFlow()

    /**
     * Initialize the delegate with the chat context.
     * Must be called before accessing state.
     */
    fun initialize(
        chatGuid: String,
        scope: CoroutineScope,
        mergedChatGuids: List<String> = listOf(chatGuid)
    ) {
        this.chatGuid = chatGuid
        this.scope = scope
        this.mergedChatGuids = mergedChatGuids

        loadChat()
        determineChatType()
        observeParticipantsForSaveContactBanner()
    }

    /**
     * Load chat metadata and observe changes.
     */
    private fun loadChat() {
        scope.launch {
            // Observe participants from all chats in merged conversation
            val participantsFlow = chatRepository.observeParticipantsForChats(mergedChatGuids)

            // Combine chat with participants to resolve display name properly
            combine(
                chatRepository.observeChat(chatGuid),
                participantsFlow
            ) { chat, participants -> chat to participants }
                .collect { (chat, participants) ->
                    chat?.let {
                        val chatTitle = resolveChatTitle(it, participants)
                        _state.update { state ->
                            state.copy(
                                chatTitle = chatTitle,
                                isGroup = it.isGroup,
                                avatarPath = participants.firstOrNull()?.cachedAvatarPath,
                                participantNames = participants.map { p -> p.displayName }.toStable(),
                                participantAvatarPaths = participants.map { p -> p.cachedAvatarPath }.toStable(),
                                participantPhone = it.chatIdentifier,
                                isLocalSmsChat = it.isLocalSms,
                                isIMessageChat = it.isIMessage,
                                smsInputBlocked = it.isSmsChat && !smsPermissionHelper.isDefaultSmsApp(),
                                isSnoozed = it.isSnoozed,
                                snoozeUntil = it.snoozeUntil
                            )
                        }
                    }
                }
        }
    }

    /**
     * Determine chat type (local SMS vs iMessage).
     */
    private fun determineChatType() {
        val isLocalSms = messageRepository.isLocalSmsChat(chatGuid)
        val isServerForward = chatGuid.startsWith("SMS;", ignoreCase = true)
        val isSmsChat = isLocalSms || isServerForward
        val isDefaultSmsApp = smsPermissionHelper.isDefaultSmsApp()

        _state.update {
            it.copy(
                isLocalSmsChat = isLocalSms,
                isIMessageChat = !isSmsChat,
                smsInputBlocked = isSmsChat && !isDefaultSmsApp
            )
        }
    }

    /**
     * Observe participants to determine if save contact banner should be shown.
     */
    private fun observeParticipantsForSaveContactBanner() {
        scope.launch {
            // Combine chat info, participants, dismissed banners, and messages
            chatRepository.observeChat(chatGuid)
                .filterNotNull()
                .combine(chatRepository.observeParticipantsForChat(chatGuid)) { chat, participants ->
                    Triple(chat, participants, chat.isGroup)
                }
                .combine(settingsDataStore.dismissedSaveContactBanners) { (chat, participants, isGroup), dismissed ->
                    Triple(participants, isGroup, dismissed)
                }
                .combine(messageRepository.observeMessagesForChat(chatGuid, limit = 1, offset = 0)) { (participants, isGroup, dismissed), messages ->
                    // Check if there are any messages received from the other party (not from me)
                    val hasReceivedMessages = messages.any { !it.isFromMe }
                    object {
                        val participants = participants
                        val isGroup = isGroup
                        val dismissed = dismissed
                        val hasReceivedMessages = hasReceivedMessages
                    }
                }
                .collect { data ->
                    // Only show banner for 1-on-1 chats with unsaved contacts that have received messages
                    if (data.isGroup || !data.hasReceivedMessages) {
                        _state.update { it.copy(showSaveContactBanner = false, unsavedSenderAddress = null) }
                        return@collect
                    }

                    // For chats without participants in the cross-ref table,
                    // check if the chat title looks like a phone number (unsaved contact)
                    val currentState = _state.value
                    val chatTitle = currentState.chatTitle
                    val participantPhone = currentState.participantPhone

                    // Find the first unsaved participant (no cached display name)
                    val unsavedParticipant = data.participants.firstOrNull { participant ->
                        participant.cachedDisplayName == null &&
                            participant.address !in data.dismissed
                    }

                    // If we have an unsaved participant from the DB, use that
                    // Otherwise, check if the chat title looks like a phone/address (no contact name)
                    val unsavedAddress = when {
                        unsavedParticipant != null -> unsavedParticipant.address
                        data.participants.isEmpty() && participantPhone != null &&
                            participantPhone !in data.dismissed &&
                            looksLikePhoneOrAddress(chatTitle) -> participantPhone
                        else -> null
                    }

                    // Get inferred name from participant if available
                    val inferredName = unsavedParticipant?.inferredName

                    _state.update {
                        it.copy(
                            showSaveContactBanner = unsavedAddress != null,
                            unsavedSenderAddress = unsavedAddress,
                            inferredSenderName = inferredName
                        )
                    }
                }
        }
    }

    /**
     * Resolve the display name for a chat, using consistent logic with the conversation list.
     * For 1:1 chats: prefer participant's displayName (from contacts or inferred)
     * For group chats: use chat displayName or generate from participant names
     */
    private fun resolveChatTitle(chat: ChatEntity, participants: List<HandleEntity>): String {
        // For group chats: use explicit group name or generate from participants
        if (chat.isGroup) {
            return chat.displayName?.takeIf { it.isNotBlank() }
                ?: participants.take(3).joinToString(", ") { it.displayName }
                    .let { names -> if (participants.size > 3) "$names +${participants.size - 3}" else names }
                    .ifEmpty { PhoneNumberFormatter.format(chat.chatIdentifier ?: "") }
        }

        // For 1:1 chats: prefer participant's displayName (handles contact lookup, inferred names)
        val primaryParticipant = participants.firstOrNull()
        return primaryParticipant?.displayName
            ?: chat.displayName?.takeIf { it.isNotBlank() }
            ?: PhoneNumberFormatter.format(chat.chatIdentifier ?: primaryParticipant?.address ?: "")
    }

    /**
     * Check if a string looks like a phone number or email address (not a contact name)
     */
    private fun looksLikePhoneOrAddress(text: String): Boolean {
        val trimmed = text.trim()
        // Check for phone number patterns (digits, spaces, dashes, parens, plus)
        val phonePattern = Regex("^[+]?[0-9\\s\\-().]+$")
        // Check for email pattern
        val emailPattern = Regex("^[^@]+@[^@]+\\.[^@]+$")
        return phonePattern.matches(trimmed) || emailPattern.matches(trimmed)
    }

    /**
     * Dismiss the save contact banner for the current unsaved sender.
     */
    fun dismissSaveContactBanner() {
        val address = _state.value.unsavedSenderAddress ?: return
        scope.launch {
            settingsDataStore.dismissSaveContactBanner(address)
            _state.update { it.copy(showSaveContactBanner = false) }
        }
    }

    /**
     * Refresh contact info from device contacts.
     * Called when returning from the system contacts app after adding a contact.
     */
    fun refreshContactInfo() {
        val address = _state.value.unsavedSenderAddress
            ?: _state.value.participantPhone
            ?: return

        scope.launch {
            val displayName = androidContactsService.getContactDisplayName(address)
            val photoUri = androidContactsService.getContactPhotoUri(address)
            if (displayName != null || photoUri != null) {
                // Update the cached display name and photo in the database
                chatRepository.updateHandleCachedContactInfo(address, displayName, photoUri)
                // Hide the save contact banner since they saved the contact
                _state.update { it.copy(showSaveContactBanner = false) }
            }
        }
    }

    /**
     * Update the smsInputBlocked state.
     * Called when default SMS app status changes.
     */
    fun updateSmsInputBlocked(blocked: Boolean) {
        _state.update { it.copy(smsInputBlocked = blocked) }
    }
}
